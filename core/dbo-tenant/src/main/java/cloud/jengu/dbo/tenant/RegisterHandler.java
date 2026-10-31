package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.wire.RecordWire;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the deployment does to this tenant's data, as the tenant reads it.
 *
 * <p>Two questions, one door. {@code GET /t/<code>/register} is the register:
 * every row the deployment's steps open of this tenant's data, the steps it
 * declined, the digest it would write down to authorise what it read, and
 * whether what is done has changed since it last did. {@code GET
 * /t/<code>/register/incidents} is the account kept against it: work running
 * under a row it never authorised, and openings its trail shows that the
 * register does not.
 *
 * <p><b>The configuration scope, because reading the register is half of
 * authorising it.</b> A tenant authorises the rows it read at {@code PUT
 * /t/<code>/register/authorisation}, under that scope, and the register then
 * says who of it agreed to each row and when; a tenant that has never done so
 * still has its declaration's {@code authorised} read. Whoever may say what the
 * tenant agrees to is whoever must be able to read what it is agreeing to —
 * and a credential that merely reads records has no business learning what
 * the deployment is entitled to open.
 *
 * <p><b>Derived on every ask, never stored.</b> The register is computed from
 * the deployment's declaration and the tenant's, and the incidents from the
 * tenant's own records, so this door and the joiner that acts on them cannot
 * disagree about what is true.
 */
public final class RegisterHandler implements HttpHandler {

    /** What a credential must carry to read this door. */
    public static final String SCOPE = Scopes.CONFIGURATION;

    /** What the door answers from, all of it about one tenant. */
    public interface Reading {
        List<FleetRegister.Row> register();

        Set<String> declined();

        /** Empty when the tenant has never authorised a register. */
        Optional<Boolean> changed();

        List<FleetRegister.Row> unapproved();

        List<UnapprovedProcessing.Incident> unapprovedProcessing();

        List<RegisterVersusTrail.Incident> disagreements();

        /**
         * Who of the tenant authorised each row at its door, by digest, or
         * empty where it never has and its declaration is what is read.
         */
        default Optional<Map<String, Authorised>> authorised() {
            return Optional.empty();
        }
    }

    /** One row a tenant authorised at its door: who of it agreed, and when. */
    public record Authorised(String by, java.time.Instant at) {
    }

    /** What authorising at the door does: exactly these rows, as whoever asked. */
    public interface Authorising {
        /**
         * @throws IllegalArgumentException naming a digest the register does
         *         not carry
         * @throws IllegalStateException where the tenant authorises elsewhere
         */
        void authorise(List<String> digests, String by);
    }

    private final TenantAuthority authority;
    private final Supplier<Reading> reading;
    private final String base;
    private final Authorising authorising;

    /** A door that reads the register and authorises none of it. */
    public RegisterHandler(TenantAuthority authority, Supplier<Reading> reading, String base) {
        this(authority, reading, base, (digests, by) -> {
            throw new IllegalStateException("this door reads the register; the tenant "
                    + "authorises it through its declaration");
        });
    }

    public RegisterHandler(TenantAuthority authority, Supplier<Reading> reading, String base,
            Authorising authorising) {
        this.authority = authority;
        this.reading = reading;
        this.base = base;
        this.authorising = authorising;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            Optional<TenantAuthority.AuthContext> asking = permitted(exchange);
            if (asking.isEmpty()) {
                return;
            }
            String rest = exchange.getRequestURI().getPath().substring(base.length());
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            if ("authorisation".equals(rest) && "PUT".equals(exchange.getRequestMethod())) {
                authorise(exchange, asking.get());
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request", "the register is read; the rows a "
                        + "tenant authorises are PUT at " + base + "/authorisation");
                return;
            }
            Reading now = reading.get();
            switch (rest) {
                case "" -> respond(exchange, 200, register(now));
                case "incidents" -> respond(exchange, 200, incidents(now));
                default -> fail(exchange, 404, "invalid_request",
                        "this door answers the register and its incidents; it was asked for '"
                                + rest + "'");
            }
        } catch (RuntimeException failed) {
            fail(exchange, 500, "register_read_failed", "the register could not be read");
        } finally {
            exchange.close();
        }
    }

    /**
     * The rows the tenant authorises, as whoever the credential names: the
     * person it was issued to where it names one, otherwise its client.
     */
    private void authorise(HttpExchange exchange, TenantAuthority.AuthContext asking)
            throws IOException {
        Object body;
        try {
            body = RecordWire.read(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            fail(exchange, 400, "invalid_request", "the authorisation is JSON naming rows");
            return;
        }
        if (!(body instanceof Map<?, ?> named) || !(named.get("rows") instanceof List<?> listed)
                || listed.stream().anyMatch(digest -> !(digest instanceof String))) {
            fail(exchange, 400, "invalid_request", "the authorisation names the digests of "
                    + "the rows authorised, as rows, and every row it leaves out is withdrawn");
            return;
        }
        try {
            authorising.authorise(listed.stream().map(String::valueOf).distinct().toList(),
                    asking.fhirUser() != null ? asking.fhirUser() : asking.clientId());
        } catch (IllegalArgumentException unread) {
            fail(exchange, 422, "invalid_request", String.valueOf(unread.getMessage()));
            return;
        } catch (IllegalStateException elsewhere) {
            fail(exchange, 409, "not_here", String.valueOf(elsewhere.getMessage()));
            return;
        }
        respond(exchange, 200, register(reading.get()));
    }

    static Map<String, Object> register(Reading now) {
        List<FleetRegister.Row> rows = now.register();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("digest", FleetRegister.digestOf(rows));
        List<Map<String, Object>> said = rows(rows);
        // Who agreed to each row and when, where the tenant said so at its
        // door; a row its declaration authorises says only that.
        Optional<Map<String, Authorised>> atTheDoor = now.authorised();
        Set<String> unapproved = new java.util.HashSet<>();
        now.unapproved().forEach(row -> unapproved.add(row.digest()));
        for (Map<String, Object> one : said) {
            String digest = String.valueOf(one.get("digest"));
            Authorised given = atTheDoor.map(door -> door.get(digest)).orElse(null);
            if (given != null) {
                one.put("authorisedBy", given.by());
                one.put("authorisedAt", given.at().toString());
            } else if (atTheDoor.isEmpty() && !unapproved.contains(digest)) {
                one.put("authorisedIn", "declaration");
            }
        }
        answer.put("rows", said);
        answer.put("declined", List.copyOf(new java.util.TreeSet<>(now.declined())));
        // Absent rather than false when nothing was ever authorised: never
        // having read a register is a different answer from having read this
        // one, and a reader must be able to tell them apart.
        now.changed().ifPresent(changed -> answer.put("changedSinceAuthorised", changed));
        answer.put("unapproved", rows(now.unapproved()));
        return answer;
    }

    static Map<String, Object> incidents(Reading now) {
        List<Map<String, Object>> unauthorised = new ArrayList<>();
        for (UnapprovedProcessing.Incident incident : now.unapprovedProcessing()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", incident.step());
            row.put("slot", incident.slot());
            row.put("type", incident.type());
            incident.since().ifPresent(since -> row.put("since", since.toString()));
            row.put("days", incident.days());
            row.put("says", incident.says());
            unauthorised.add(row);
        }
        List<Map<String, Object>> disagreements = new ArrayList<>();
        for (RegisterVersusTrail.Incident incident : now.disagreements()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", incident.step());
            row.put("slot", incident.slot());
            row.put("type", incident.type());
            row.put("id", incident.id());
            row.put("by", incident.by());
            row.put("run", incident.runKey());
            row.put("says", incident.says());
            disagreements.add(row);
        }
        return Map.of("unauthorised", unauthorised, "disagreements", disagreements);
    }

    static List<Map<String, Object>> rows(List<FleetRegister.Row> rows) {
        List<Map<String, Object>> said = new ArrayList<>();
        for (FleetRegister.Row row : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("step", row.step());
            // A jurisdiction's process is over whatever its members exchange,
            // not over one slot of one step, so it has neither; nor has a
            // part a holder of handed work starts, which opens nothing.
            if (row.slot() != null) {
                one.put("slot", row.slot());
                one.put("type", row.type());
            }
            one.put("authority", row.authority().name().toLowerCase(java.util.Locale.ROOT));
            if (row.by() != null) {
                one.put("by", row.by());
            }
            if (row.partOf() != null) {
                one.put("partOf", row.partOf());
            }
            if (!row.subProcessors().isEmpty()) {
                one.put("subProcessors", row.subProcessors());
            }
            one.put("required", row.required());
            one.put("posture", row.posture().name().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-'));
            one.put("digest", row.digest());
            said.add(one);
        }
        return said;
    }

    private Optional<TenantAuthority.AuthContext> permitted(HttpExchange exchange)
            throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        Optional<TenantAuthority.AuthContext> context =
                bearer == null ? Optional.empty() : authority.validate(bearer);
        if (context.isEmpty() || !context.get().scopes().contains(SCOPE)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "the register is read and authorised with the '" + SCOPE + "' scope");
            return Optional.empty();
        }
        return context;
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
