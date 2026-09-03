package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.Trackables;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What this tenant knows about the things behind its participants.
 *
 * <p>A routed tree could be reported and normalised, and nothing outside the
 * container could ask what it said. {@code Trackables} has a good read
 * surface — by id, what is behind a router, what a reporter observed, the
 * whole subtree — and every one of those was reachable only from inside the
 * framework holding the store. So a real appliance reported the instruments
 * behind it, the store normalised them, and the only consumer that wanted the
 * answer had to keep its own copy of what it had forwarded. That second
 * normalisation of state the store already normalises is the thing the model
 * was accepted to avoid.
 *
 * <p><b>Not a verb on the lane</b>, and worth saying because that is where it
 * would have been convenient. A lane is a <em>participant's</em> surface —
 * what this participant may do — and an operator asking what state a fleet is
 * in is not a participant act. A bench that could read the tree would be
 * reading about benches it has no business knowing.
 *
 * <p><b>Not on the maintenance surface either.</b> Maintenance is things done
 * <em>to</em> the store — archives, restores, reshapes. This is a question
 * about what the tenant knows, which is why it stands beside replication
 * rather than inside maintenance, with a scope of its own.
 *
 * <p><b>Runs are answered here too, as envelopes.</b> What is held by a person,
 * what automation is running and what is waiting on a retry is the state of
 * the tenant's work, and a deployment reading its fleet asks that question
 * as often as it asks what is behind a connector. The answer is the run's
 * envelope — key, process, step, kind, holder, tally, the step's version —
 * and never its payload: the envelope was designed to disclose state and not
 * subject, and this door is the reason that mattered. A caller who wants the
 * run itself reads it on the tenant's surface under the tenant's authority,
 * which the fleet scope does not carry.
 *
 * <p><b>No freshness rule, deliberately.</b> Nothing here filters stale rows
 * or thresholds on how long ago something was seen. {@code attested} says who
 * last saw a thing and when, and what that means depends on the hop's cadence,
 * which only the caller knows. A read that quietly dropped anything older than
 * some interval would reintroduce the judgement the model refused to make, in
 * the one place nobody would look for it.
 */
public final class FleetHandler implements HttpHandler {

    /** What a credential must carry to reach this door and nothing else. */
    public static final String SCOPE = cloud.jengu.dbo.auth.Scopes.FLEET;

    /** Rows per answer when the caller does not say: a list of everything answers nothing. */
    static final int DEFAULT_RUN_LIMIT = 200;

    private final TenantAuthority authority;
    private final Trackables trackables;
    private final Runs runs;
    private final String base;

    public FleetHandler(TenantAuthority authority, Trackables trackables, Runs runs,
            String base) {
        this.authority = authority;
        this.trackables = trackables;
        this.runs = runs;
        this.base = base;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String verb = exchange.getRequestURI().getPath().substring(base.length());
            while (verb.startsWith("/")) {
                verb = verb.substring(1);
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request",
                        "the fleet questions are posted, because each carries what it asks "
                                + "about");
                return;
            }
            if (!permitted(exchange)) {
                return;
            }
            Map<String, Object> body = asFields(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            switch (verb) {
                // The query that matters. Depth is the whole point of the
                // model: an operator asks what is behind a connector and gets
                // everything under it, one shape at every level. A door that
                // answered only a flat set would make every caller rebuild the
                // tree, and they would each do it differently — which is what
                // normalising it centrally was for.
                case "subtree" -> respond(exchange, trackables.subtree(required(body, "router")));
                case "behind" -> respond(exchange, trackables.behind(required(body, "router")));
                case "observedBy" -> respond(exchange,
                        trackables.observedBy(required(body, "reporter")));
                case "trackable" -> {
                    Optional<Trackable> one = trackables.byId(required(body, "id"));
                    if (one.isEmpty()) {
                        // Absent rather than an error: a thing this tenant has
                        // never been told about is an ordinary answer to the
                        // question, and the caller asking is usually asking
                        // exactly that.
                        respond(exchange, Map.of("trackable", Map.of()));
                        return;
                    }
                    respond(exchange, one.get());
                }
                case "all" -> respond(exchange, trackables.all());
                case "runs" -> respond(exchange, Map.of("runs", envelopes(body)));
                default -> fail(exchange, 404, "invalid_request",
                        "this door answers subtree, behind, observedBy, trackable, all and "
                                + "runs; it was asked for '" + verb + "'");
            }
        } catch (IllegalArgumentException refused) {
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException failed) {
            fail(exchange, 500, "fleet_read_failed", "the question did not complete");
        } finally {
            exchange.close();
        }
    }

    private boolean permitted(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        Optional<TenantAuthority.AuthContext> context =
                bearer == null ? Optional.empty() : authority.validate(bearer);
        if (context.isEmpty() || !context.get().scopes().contains(SCOPE)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "reading the fleet needs the '" + SCOPE + "' scope. A participation "
                            + "credential does not carry it: what a bench may do and what a "
                            + "deployment may ask about every bench are different questions");
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asFields(String json) {
        Object read = RecordWire.read(json);
        if (read instanceof Map<?, ?> fields) {
            return (Map<String, Object>) fields;
        }
        throw new IllegalArgumentException("the body must be an object");
    }

    /**
     * The runs matching the filter, as envelopes. Every filter is an envelope
     * value, so this is the same query the console runs; absent means any,
     * and a holder is spelt as it is on the wire.
     */
    private List<Map<String, Object>> envelopes(Map<String, Object> body) {
        String holderWord = optional(body, "holder");
        Holder holder = holderWord == null || "any".equalsIgnoreCase(holderWord) ? null
                : Holder.valueOf(holderWord.toUpperCase(java.util.Locale.ROOT));
        String limitWord = optional(body, "limit");
        int limit = limitWord == null ? DEFAULT_RUN_LIMIT : Integer.parseInt(limitWord);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Run run : runs.matching(optional(body, "process"), optional(body, "step"),
                holder, limit)) {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("key", run.key());
            envelope.put("process", run.process());
            envelope.put("step", run.step());
            envelope.put("kind", run.kind().wire());
            envelope.put("holder", run.holder().wire());
            envelope.put("tally", run.tally());
            if (run.stepVersion() != null) {
                envelope.put("stepVersion", run.stepVersion());
            }
            out.add(envelope);
        }
        return out;
    }

    private static String optional(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String required(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("'" + field + "' is required");
        }
        return String.valueOf(value);
    }

    private static void respond(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = RecordWire.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        byte[] bytes = RecordWire.write(Map.of("error", error, "detail", detail))
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
