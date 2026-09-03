package cloud.jengu.dbo.fleet;

import cloud.jengu.dbo.core.wire.RecordWire;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The reader as a service: a door that reads the fleet <em>when asked</em>
 * and answers with what it just read.
 *
 * <p><b>It holds nothing between reads.</b> That is the second-store decision
 * applied to the service form: a reading kept here, however briefly, would be
 * a second answer to a question the tenant stores answer authoritatively, and
 * the two would disagree exactly when an operator was deciding on it. So the
 * service holds the reader's configuration — which nodes, which secrets — and
 * every request is a fresh fan-out. The cost is that an ask takes as long as
 * the slowest node's timeout; the alternative was being wrong quickly.
 *
 * <p>Behind the deployment's own token, because the answer names every tenant
 * the deployment has. A refusal says nothing about what it refused to show.
 */
public final class FleetService implements AutoCloseable {

    private final FleetReader reader;
    private final byte[] token;
    private final byte[] actToken;
    private final HttpServer server;

    /** A door that only reads: no act token, and therefore no act surface. */
    public FleetService(FleetReader reader, String host, int port, String token)
            throws IOException {
        this(reader, host, port, token, null);
    }

    /**
     * @param token    what a caller must present to read; the answer is every
     *                 tenant's existence, so this is the deployment's token
     *                 and never a tenant's
     * @param actToken what a caller must present to act, and a different
     *                 secret on purpose: an operator looks far more often
     *                 than they act, and the looking must not carry the
     *                 authority to overturn somebody's work. Absent, the act
     *                 surface is <b>not mounted</b> rather than mounted and
     *                 refusing — the same posture the runtime-state door
     *                 takes, because a deployment that has not said who may
     *                 act has not asked for a door to be asked through.
     */
    public FleetService(FleetReader reader, String host, int port, String token,
            String actToken) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("the fleet service needs a token: its answer names "
                    + "every tenant, and a door with no token would serve that openly");
        }
        this.reader = reader;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.actToken = actToken == null || actToken.isBlank() ? null
                : actToken.getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/fleet", this::handle);
        if (this.actToken != null) {
            server.createContext("/fleet/reopen", this::reopen);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Whether this door can act at all — false unless a deployment said so. */
    public boolean acts() {
        return actToken != null;
    }

    public FleetService start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * One reopening, named by tenant and run key.
     *
     * <p>Behind its own token and its own credential: this door decides who
     * may ask the reader to act, and the tenant decides whether the reader
     * may. Both have to say yes, and the tenant's answer is the one that
     * carries the rules.
     */
    private void reopen(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange, actToken)) {
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"invalid_request\"}");
                return;
            }
            Object body = RecordWire.read(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> fields = body instanceof Map<?, ?> map
                    ? asFields(map) : Map.of();
            String tenant = text(fields, "tenant");
            String run = text(fields, "run");
            String reason = text(fields, "reason");
            if (tenant == null || run == null || reason == null) {
                // The reason is required, not optional: a reopening with no
                // reason is an unexplained change to somebody's work, and the
                // record has a place for it precisely so it is never that.
                respond(exchange, 400, "{\"error\":\"invalid_request\",\"detail\":\"a "
                        + "reopening names the tenant, the run and the reason\"}");
                return;
            }
            FleetReader.Acted acted = reader.reopen(tenant, run, reason);
            respond(exchange, acted.outcome() == Reading.Outcome.ANSWERED ? 200 : 409,
                    RecordWire.write(acted));
        } finally {
            exchange.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asFields(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /** Constant-time against the token this door wants; answers the refusal itself. */
    private boolean authorized(HttpExchange exchange, byte[] wanted) throws IOException {
        String presented = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] offered = presented == null || !presented.startsWith("Bearer ")
                ? new byte[0] : presented.substring(7).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(wanted, offered)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return false;
        }
        return true;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            // A path under /fleet that this door does not serve is a 404, not
            // a read: without it, an act asked of a read-only reader would be
            // answered by the reading handler and read as a refusal of the
            // act rather than as its absence.
            String path = exchange.getRequestURI().getPath();
            if (!"/fleet".equals(path) && !"/fleet/".equals(path)) {
                respond(exchange, 404, "{\"error\":\"not_found\",\"detail\":\"this reader "
                        + "serves GET /fleet, and acts only where a deployment gave it an act "
                        + "token\"}");
                return;
            }
            String presented = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] offered = presented == null || !presented.startsWith("Bearer ")
                    ? new byte[0] : presented.substring(7).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(token, offered)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"invalid_request\"}");
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            FleetReader.RunFilter filter;
            try {
                filter = new FleetReader.RunFilter(query.get("process"), query.get("step"),
                        query.get("holder"),
                        query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : null);
            } catch (NumberFormatException notANumber) {
                respond(exchange, 400, "{\"error\":\"invalid_request\",\"detail\":\"limit is a "
                        + "number\"}");
                return;
            }
            // read now, answer, keep nothing
            respond(exchange, 200, RecordWire.write(reader.read(filter)));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq),
                    StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1),
                    StandardCharsets.UTF_8);
            if (!value.isBlank()) {
                out.put(name, value);
            }
        }
        return out;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
