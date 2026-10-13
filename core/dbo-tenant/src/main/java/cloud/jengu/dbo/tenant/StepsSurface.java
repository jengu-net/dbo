package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.work.Run;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * A tenant's steps that start themselves, as a deployment reads and steers
 * them: {@code /t/<code>/steps}.
 *
 * <ul>
 *   <li>{@code GET} — each step: what starts it, whether it is paused, its
 *       last tick and run and how that run stands, its next tick, and how
 *       many ticks were missed and skipped;</li>
 * <li>{@code POST <step>/now} — a run of it now, outside its occasion; its next tick still
 * comes when due;</li>
 *   <li>{@code POST <step>/pause}, {@code POST <step>/resume} — recorded as
 *       acts under the tenant's system run, saying who and the
 *       {@code because} the body gives.</li>
 * </ul>
 *
 * <p>Reading and running need a credential that may act in work, bounded to
 * the step for running as the step door bounds it. Pausing and resuming need
 * the supervisory scope over the step: when a tenant's work happens is a judgment about it,
 * which a worker does not hold.
 */
final class StepsSurface implements HttpHandler {

    private final Function<String, Optional<TenantAuthority.AuthContext>> validated;
    private final SelfStarting starting;
    private final StepSurface starter;

    StepsSurface(Function<String, Optional<TenantAuthority.AuthContext>> validated,
            SelfStarting starting, StepSurface starter) {
        this.validated = validated;
        this.starting = starting;
        this.starter = starter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath()
                    .substring(exchange.getHttpContext().getPath().length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            TenantAuthority.AuthContext asking = asking(exchange).orElse(null);
            if (asking == null || !Scopes.admitsWork(asking.scopes())
                    && !Scopes.admitsSupervision(asking.scopes())) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                fail(exchange, asking == null ? 401 : 403, "access_denied",
                        "this surface admits a credential that may act in work or supervise "
                                + "it");
                return;
            }
            String by = asking.fhirUser() != null ? asking.fhirUser() : asking.clientId();
            cloud.jengu.dbo.core.api.Caller.set(by);
            if (relative.isEmpty()) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    fail(exchange, 405, "invalid_request", "the steps are read with GET");
                    return;
                }
                respond(exchange, 200, status());
                return;
            }
            int slash = relative.lastIndexOf('/');
            if (slash < 0 || !"POST".equals(exchange.getRequestMethod())) {
                fail(exchange, 404, "not_found", "a step is acted on by POSTing to "
                        + "<step>/now, <step>/pause or <step>/resume");
                return;
            }
            String step = relative.substring(0, slash);
            String act = relative.substring(slash + 1);
            Object body = body(exchange);
            String because = Json.strOpt(body, "because");
            switch (act) {
                case "now" -> {
                    if (!Scopes.worksAsTheTenant(asking.scopes())
                            && !Scopes.workSteps(asking.scopes()).contains(step)) {
                        fail(exchange, 403, "access_denied", "this credential may act in work "
                                + "for " + Scopes.workSteps(asking.scopes()) + ", not " + step);
                        return;
                    }
                    TenantSpec.Step declared = starting.declaredHere(step);
                    Run run = starting.now(step, starter.filled(declared, body),
                            asking.clientId());
                    respond(exchange, 201, "{\"run\":" + quote(run.id()) + ",\"key\":"
                            + quote(run.key()) + ",\"parent\":" + quote(run.parent()) + "}");
                }
                case "pause", "resume" -> {
                    if (!Scopes.supervisesEverything(asking.scopes())
                            && !Scopes.supervisedSteps(asking.scopes()).contains(step)) {
                        fail(exchange, 403, "access_denied", "pausing and resuming a step is "
                                + "supervising it, and this credential supervises "
                                + Scopes.supervisedSteps(asking.scopes()) + ", not " + step);
                        return;
                    }
                    Run recorded = "pause".equals(act) ? starting.pause(step, by, because)
                            : starting.resume(step, by, because);
                    respond(exchange, 200, "{\"step\":" + quote(step) + ",\"paused\":"
                            + "pause".equals(act) + ",\"act\":" + quote(recorded.key())
                            + ",\"id\":" + quote(recorded.id()) + "}");
                }
                default -> fail(exchange, 404, "not_found", "'" + act + "' is not something a "
                        + "step is asked to do; it is asked to run now, pause or resume");
            }
        } catch (IllegalArgumentException refused) {
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException failed) {
            fail(exchange, 500, "failed", "the request did not complete");
        } finally {
            cloud.jengu.dbo.core.api.Caller.clear();
            exchange.close();
        }
    }

    private String status() {
        StringBuilder steps = new StringBuilder();
        for (SelfStarting.Status one : starting.status()) {
            TenantSpec.Starts starts = one.starts();
            if (steps.length() > 0) {
                steps.append(',');
            }
            steps.append("{\"step\":").append(quote(one.step()))
                    .append(",\"starts\":{")
                    .append(starts.every() != null ? "\"every\":" + quote(starts.every())
                            : starts.change() != null ? "\"change\":" + quote(starts.change())
                            : "\"due\":" + quote(starts.due()) + ",\"at\":"
                                    + quote(starts.at()) + ",\"after\":"
                                    + quote(starts.after().toString()))
                    .append(starts.missed() == null ? "" : ",\"missed\":"
                            + quote(starts.missed().name().toLowerCase(java.util.Locale.ROOT)))
                    .append(",\"keep\":").append(quote(starts.keep().toString()))
                    .append(",\"where\":")
                    .append(quote(starts.where().name().toLowerCase(java.util.Locale.ROOT)))
                    .append("},\"paused\":").append(one.paused())
                    .append(",\"lastTick\":").append(instant(one.lastTick()))
                    .append(",\"lastRun\":").append(nullable(one.lastRun()))
                    .append(",\"lastOutcome\":").append(nullable(one.lastOutcome()))
                    .append(",\"lastStarted\":").append(instant(one.lastStarted()))
                    .append(",\"next\":").append(instant(one.next()))
                    .append(",\"missed\":").append(one.missed())
                    .append(",\"skipped\":").append(one.skipped())
                    .append(",\"started\":").append(one.started())
                    .append(",\"removed\":").append(one.removed())
                    .append('}');
        }
        return "{\"system\":" + quote(starting.system().key()) + ",\"steps\":[" + steps + "]}";
    }

    private Optional<TenantAuthority.AuthContext> asking(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        return bearer == null ? Optional.empty() : validated.apply(bearer);
    }

    private static Object body(HttpExchange exchange) throws IOException {
        String text = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        return text.isBlank() ? java.util.Map.of() : Json.parse(text);
    }

    private static String instant(Instant at) {
        return at == null ? "null" : quote(at.toString());
    }

    private static String nullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        respond(exchange, status, "{\"error\":" + quote(error) + ",\"detail\":"
                + quote(detail) + "}");
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String quote(String value) {
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
