package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.sync.ConfigApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Asking that what is declared be applied now.
 *
 * <p>A deployment applies its declarations on its own, on the beat of its own
 * scan. That is the right default and the wrong only option: somebody who has
 * just changed a declaration wants it applied now rather than in a couple of
 * seconds' time, somebody who has just fixed one wants to know whether the fix
 * took, and a deployment whose applying is switched off has no other way at
 * all. All three are the same act, so they are the same door.
 *
 * <p><b>Authoring is the API.</b> The ask opens a run and answers with it,
 * exactly as an erasure does, because an application with no record is the one
 * thing the whole line of work here is against — and because "who applied that,
 * and when" is the first question anybody asks after a configuration lands
 * wrong. There is deliberately no second entry point that applies without
 * writing one.
 *
 * <p>Behind its own scope, for the reason the erasure door is: changing what a
 * tenant is, is not the same right as writing records into it.
 *
 * <p><b>Two things can be applied here, and the body says which.</b> A body
 * carrying declarations applies them to this tenant — value sets, profiles,
 * search parameters, whatever a declarer holds — as one recorded pass, which is
 * what a loader posting them one at a time never gets: forty-six read,
 * forty-four applied, two cards naming the files somebody has to open. An empty
 * body asks the deployment to apply the declarations it reads itself, and is
 * only meaningful where those live.
 *
 * <p><b>A body may be a list, or it may be a READ.</b> A list says nothing
 * about whether it is all of them, or about whether it is the same list as
 * last time, so it is applied every time and nothing is ever withdrawn. A body
 * that carries a {@code marker} is a source in every respect but where the
 * bytes came from: this store can tell whether it already agreed with exactly
 * that read and answer with nothing, and — where the declarer also says
 * {@code complete} — it can take what the read no longer names as withdrawn.
 * That is the difference between a loader that reapplies a zone every two
 * minutes and one that costs a read, and it is the declarer's to claim rather
 * than this store's to infer.
 *
 * <p>Nothing reaches back afterwards. The correlation the declarer sent is
 * echoed on the run and never parsed, and whoever declared it closes their own
 * run by re-evaluating against what this one says — the moment this store
 * called them instead, both systems would have to be up together.
 */
public final class ConfigurationHandler implements HttpHandler {

    /** What a credential must carry to reach this door and nothing else. */
    public static final String SCOPE = cloud.jengu.dbo.auth.Scopes.CONFIGURATION;

    private final TenantAuthority authority;
    private final Supplier<ConfigApplication.Outcome> apply;
    private final java.util.function.BiFunction<String,
            java.util.List<ConfigApplication.Declared>, ConfigApplication.Outcome> applyHere;
    private final java.util.function.Function<cloud.jengu.dbo.sync.ConfigSource.Fetch,
            ConfigApplication.Outcome> applyRead;

    /**
     * @param applyHere a list, applied every time and withdrawing nothing
     * @param applyRead a read, which may be one this scope already agreed with
     *                  and may be complete. Kept apart from {@code applyHere}
     *                  rather than inferred from a null marker: the two differ
     *                  in whether an unchanged post is a rewrite or a read,
     *                  and a seam that decided that from a missing field would
     *                  make it depend on a declarer forgetting one
     */
    public ConfigurationHandler(TenantAuthority authority,
            Supplier<ConfigApplication.Outcome> apply,
            java.util.function.BiFunction<String,
                    java.util.List<ConfigApplication.Declared>,
                    ConfigApplication.Outcome> applyHere,
            java.util.function.Function<cloud.jengu.dbo.sync.ConfigSource.Fetch,
                    ConfigApplication.Outcome> applyRead) {
        this.authority = authority;
        this.apply = apply;
        this.applyHere = applyHere;
        this.applyRead = applyRead;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request",
                        "applying what is declared is asked for with POST");
                return;
            }
            if (!permitted(exchange)) {
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            ConfigApplication.Outcome outcome = body.isBlank()
                    ? applyWhatIsRead(exchange)
                    : applyWhatWasSent(exchange, body);
            if (outcome == null) {
                return;
            }
            // What the pass did, in the numbers the run carries. A caller that
            // declared something and wants to know whether it took reads
            // applied; one that wants to know whether anybody has to fix
            // something reads skipped, and then reads the run.
            java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("process", ConfigApplication.PROCESS);
            answer.put("step", ConfigApplication.STEP);
            answer.put("read", outcome.read());
            answer.put("applied", outcome.applied());
            // Said apart from applied: a caller polling a repository
            // reads this to know its last post changed nothing, which
            // "applied" cannot tell it.
            answer.put("unchanged", outcome.unchanged());
            answer.put("skipped", outcome.skipped());
            answer.put("withdrawn", outcome.withdrawn());
            if (outcome.run() != null) {
                answer.put("run", outcome.run());
            }
            // Always present, empty included. A field that appears only when
            // something went wrong is one a caller learns to read by its
            // absence, and absence is also what a version that never sent it
            // looks like.
            java.util.List<Object> cards = new java.util.ArrayList<>();
            for (ConfigApplication.Card card : outcome.cards()) {
                cards.add(Map.of("declaration", String.valueOf(card.declaration()),
                        "reason", String.valueOf(card.reason())));
            }
            answer.put("cards", cards);
            respond(exchange, 200, answer);
        } catch (RuntimeException failed) {
            // The run carries what actually happened. This says only that the
            // ask did not complete, because a body describing an application
            // that may have half-run is worse than a status.
            fail(exchange, 500, "apply_failed", "the application did not complete");
        } finally {
            exchange.close();
        }
    }

    /** What the deployment reads for itself. */
    private ConfigApplication.Outcome applyWhatIsRead(HttpExchange exchange) throws IOException {
        if (apply == null) {
            fail(exchange, 400, "invalid_request",
                    "this tenant reads no declarations of its own: send the ones to apply");
            return null;
        }
        return apply.get();
    }

    /**
     * What a declarer sent. Its own name for the set is echoed onto the run and
     * never read: what a commit is called is the declarer's business, and this
     * store having an opinion about it would be this store deciding when
     * somebody else's configuration is the same configuration.
     */
    private ConfigApplication.Outcome applyWhatWasSent(HttpExchange exchange, String body)
            throws IOException {
        Object read = RecordWire.read(body);
        if (!(read instanceof Map<?, ?> fields)) {
            fail(exchange, 400, "invalid_request", "send an object");
            return null;
        }
        Object declared = fields.get("declarations");
        if (!(declared instanceof java.util.List<?> items) || items.isEmpty()) {
            fail(exchange, 400, "invalid_request",
                    "name the declarations to apply, or send nothing at all to apply what "
                            + "this deployment reads for itself");
            return null;
        }
        java.util.List<ConfigApplication.Declared> declarations = new java.util.ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> one) || one.get("type") == null
                    || one.get("name") == null || one.get("payload") == null) {
                fail(exchange, 400, "invalid_request",
                        "every declaration carries a type, a name and a payload");
                return null;
            }
            declarations.add(new ConfigApplication.Declared(
                    String.valueOf(one.get("type")), String.valueOf(one.get("name")),
                    RecordWire.write(one.get("payload")).getBytes(StandardCharsets.UTF_8)));
        }
        String marker = fields.get("marker") == null
                ? null : String.valueOf(fields.get("marker"));
        boolean complete = Boolean.TRUE.equals(fields.get("complete"))
                || "true".equals(String.valueOf(fields.get("complete")));
        if (marker == null) {
            if (complete) {
                // Refused rather than ignored. A declarer that says this is
                // all of them is asking for what it does not name to be taken
                // away, and a body that cannot say WHICH read it is cannot be
                // held to that claim on the next pass — so the claim would be
                // honoured once and then quietly mean nothing.
                fail(exchange, 400, "invalid_request",
                        "a complete set is a read this store can recognise again: send a "
                                + "marker with it, or send the declarations without claiming "
                                + "they are all of them");
                return null;
            }
            String correlation = fields.get("correlation") == null
                    ? null : String.valueOf(fields.get("correlation"));
            return applyHere.apply(correlation, declarations);
        }
        // The marker is what the run is correlated with, because it is what
        // being settled is computed from. A correlation sent beside it names
        // the same read a second time and is not kept: two answers to "what
        // did this scope last agree with" is no answer, and the skip is the
        // thing that would stop working.
        return applyRead.apply(new cloud.jengu.dbo.sync.ConfigSource.Fetch(
                declarations, marker, complete));
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
                    "applying what is declared needs the '" + SCOPE
                            + "' scope, which nothing else grants");
            return false;
        }
        return true;
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] bytes = RecordWire.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        respond(exchange, status, Map.of("error", error, "detail", detail));
    }
}
