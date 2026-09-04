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
 */
public final class ConfigurationHandler implements HttpHandler {

    /** What a credential must carry to reach this door and nothing else. */
    public static final String SCOPE = cloud.jengu.dbo.auth.Scopes.CONFIGURATION;

    private final TenantAuthority authority;
    private final Supplier<ConfigApplication.Outcome> apply;

    public ConfigurationHandler(TenantAuthority authority,
            Supplier<ConfigApplication.Outcome> apply) {
        this.authority = authority;
        this.apply = apply;
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
            ConfigApplication.Outcome outcome = apply.get();
            // What the pass did, in the numbers the run carries. A caller that
            // declared something and wants to know whether it took reads
            // applied; one that wants to know whether anybody has to fix
            // something reads skipped, and then reads the run.
            respond(exchange, 200, Map.of(
                    "process", ConfigApplication.PROCESS,
                    "step", ConfigApplication.STEP,
                    "read", outcome.read(),
                    "applied", outcome.applied(),
                    "skipped", outcome.skipped(),
                    "withdrawn", outcome.withdrawn()));
        } catch (RuntimeException failed) {
            // The run carries what actually happened. This says only that the
            // ask did not complete, because a body describing an application
            // that may have half-run is worse than a status.
            fail(exchange, 500, "apply_failed", "the application did not complete");
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
