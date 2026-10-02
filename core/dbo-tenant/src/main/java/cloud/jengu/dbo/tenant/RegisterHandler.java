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
 * authorising it.</b> A tenant authorises a register by writing its digest
 * into its own declaration, which is handed in at the configuration door
 * under that scope. Whoever may say what the tenant agrees to is whoever must
 * be able to read what it is agreeing to — and a credential that merely reads
 * records has no business learning what the deployment is entitled to open.
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
    }

    private final TenantAuthority authority;
    private final Supplier<Reading> reading;
    private final String base;

    public RegisterHandler(TenantAuthority authority, Supplier<Reading> reading, String base) {
        this.authority = authority;
        this.reading = reading;
        this.base = base;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request", "the register is read, not written: a "
                        + "tenant authorises one through its own declaration");
                return;
            }
            if (!permitted(exchange)) {
                return;
            }
            String rest = exchange.getRequestURI().getPath().substring(base.length());
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
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

    static Map<String, Object> register(Reading now) {
        List<FleetRegister.Row> rows = now.register();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("digest", FleetRegister.digestOf(rows));
        answer.put("rows", rows(rows));
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

    private static List<Map<String, Object>> rows(List<FleetRegister.Row> rows) {
        List<Map<String, Object>> said = new ArrayList<>();
        for (FleetRegister.Row row : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("step", row.step());
            one.put("slot", row.slot());
            one.put("type", row.type());
            one.put("required", row.required());
            one.put("posture", row.posture().name().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-'));
            one.put("digest", row.digest());
            said.add(one);
        }
        return said;
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
                    "reading the register needs the '" + SCOPE + "' scope, the one a tenant "
                            + "authorises a register with");
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
