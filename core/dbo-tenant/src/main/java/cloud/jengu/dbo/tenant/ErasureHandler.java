package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.work.Run;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The door a consumer asks for an erasure through.
 *
 * <p>It is deliberately <b>not</b> an operation on the maintenance surface,
 * which archives, restores and reshapes — things done to the store. Erasing a
 * person is an act performed for somebody, on instruction, and what it leaves
 * behind has to be showable to a regulator. So this door does one thing:
 * it opens a run of {@link PersonErasure} and answers with it.
 *
 * <p><b>What comes back is the receipt.</b> The run's key, what it found and
 * how far it got — not a bare 200. A caller that asked twice gets the same run
 * rather than a second account of one erasure.
 *
 * <p><b>Its own scope, outside the resource grammar.</b> A credential that may
 * erase a person is structurally blind to the store's resource surface rather
 * than filtered away from it, for the same reason a directory credential and a
 * participation credential are.
 */
public final class ErasureHandler implements HttpHandler {

    /** What a credential must carry to reach this door and nothing else. */
    public static final String SCOPE = cloud.jengu.dbo.auth.Scopes.ERASURE;

    private final TenantAuthority authority;
    private final PersonErasure erasure;
    /** Resolves the reference a consumer holds to the vault's own person. */
    private final Function<String, Optional<String>> subjects;

    public ErasureHandler(TenantAuthority authority, PersonErasure erasure,
            Function<String, Optional<String>> subjects) {
        this.authority = authority;
        this.erasure = erasure;
        this.subjects = subjects;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request", "an erasure is requested with POST");
                return;
            }
            if (!permitted(exchange)) {
                return;
            }
            Object body = RecordWire.read(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String subject = body instanceof Map<?, ?> fields
                    && fields.get("subject") != null ? String.valueOf(fields.get("subject")) : null;
            if (subject == null || subject.isBlank()) {
                fail(exchange, 400, "invalid_request",
                        "name the subject by the reference this store knows it as");
                return;
            }
            // The reference a consumer holds is pseudonymous, and resolving it
            // to the vault's person is this side's act. A door that accepted a
            // national identifier would take identifying data in a request and
            // put it in a work record's key.
            Optional<String> person = subjects.apply(subject);
            if (person.isEmpty()) {
                fail(exchange, 400, "invalid_request",
                        "that reference names nothing this store could resolve to a person");
                return;
            }
            // A run either way, including for somebody this store never held.
            // The caller asked, and what it needs afterwards is a record that
            // it asked and what came back — which a bare 200 would not give
            // it. Whether anybody was there is the RUN's answer, in its tally,
            // and deliberately not a second field here saying the same word
            // about a different question.
            Run run = erasure.erase(person.get());
            respond(exchange, 202, Map.of(
                    "run", run.key(),
                    "process", PersonErasure.PROCESS,
                    "step", PersonErasure.STEP,
                    "open", run.open(),
                    "tally", run.tally()));
        } catch (IllegalArgumentException refused) {
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException failed) {
            // The run carries what actually happened; this only says the ask
            // did not complete, because a body describing an erasure that may
            // have half-run is worse than a status.
            fail(exchange, 500, "erasure_failed", "the erasure did not complete");
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
                    "erasing a person needs the '" + SCOPE + "' scope, which nothing else grants");
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
