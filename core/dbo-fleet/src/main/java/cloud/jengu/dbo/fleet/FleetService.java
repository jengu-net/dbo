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
    private final HttpServer server;

    /**
     * @param token what a caller must present; the answer is every tenant's
     *              existence, so this is the deployment's token and never a
     *              tenant's
     */
    public FleetService(FleetReader reader, String host, int port, String token)
            throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("the fleet service needs a token: its answer names "
                    + "every tenant, and a door with no token would serve that openly");
        }
        this.reader = reader;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/fleet", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public FleetService start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
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
