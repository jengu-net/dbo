package cloud.jengu.dbo.tenant;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * The server a tenant's surfaces are mounted on, answering 503 for a tenant
 * that is not serving yet.
 *
 * <p>A bring-up mounts its surfaces as it goes — the records door before the
 * face chain is drained, the vocabularies published, the tenant made somebody's
 * upstream — and a mounted surface answered. So {@code /fhir/metadata} said 200
 * for a tenant still half-way up, which is all a readiness wait asks; a client
 * that started then wrote into a tenant whose bring-up could still fail, be
 * rolled back, and take its context and those writes with it. What the client
 * saw was {@code 404 No context found} for a tenant it had just been talking to.
 *
 * <p>So a surface mounted under {@code /t/{code}/} answers only once its tenant
 * is serving, and until then says so: 503 with {@code Retry-After}, which is
 * the answer every HTTP client already knows means "this exists and is not
 * ready", where a 404 means "nothing here" and a 200 means "go ahead". A
 * surface keeps its place while it waits, so the bring-up is unchanged and
 * nothing the tenant offers has to be mounted in a second, later step that
 * every new surface would have to remember.
 *
 * <p>Everything else — mounting, removing, binding, stopping — is the wrapped
 * server's, which may be this runtime's own or a host's web tier.
 */
final class DoorsOpenWhenServing extends HttpServer {

    /** How long a client is asked to wait before asking again, in seconds. */
    static final String RETRY_AFTER = "2";

    private final HttpServer server;
    private final Predicate<String> serving;

    /**
     * @param server  where the surfaces are actually mounted
     * @param serving whether the tenant with this code is serving
     */
    DoorsOpenWhenServing(HttpServer server, Predicate<String> serving) {
        this.server = server;
        this.serving = serving;
    }

    /** The tenant whose surface a context path is, or null for one that is nobody's. */
    static String tenantOf(String path) {
        if (path == null || !path.startsWith("/t/")) {
            return null;
        }
        int end = path.indexOf('/', 3);
        String code = end < 0 ? path.substring(3) : path.substring(3, end);
        return code.isEmpty() ? null : code;
    }

    @Override
    public HttpContext createContext(String path, HttpHandler handler) {
        String code = tenantOf(path);
        if (code == null || handler == null) {
            return server.createContext(path, handler);
        }
        return server.createContext(path, exchange -> {
            if (serving.test(code)) {
                handler.handle(exchange);
            } else {
                notYet(exchange, code);
            }
        });
    }

    @Override
    public HttpContext createContext(String path) {
        return server.createContext(path);
    }

    private static void notYet(HttpExchange exchange, String code) throws IOException {
        try {
            // The request body is left unread on purpose: nothing in it is
            // acted on, and it is the client's to send again.
            byte[] body = ("tenant " + code + " is coming up; ask again shortly\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", RETRY_AFTER);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void removeContext(String path) {
        server.removeContext(path);
    }

    @Override
    public void removeContext(HttpContext context) {
        server.removeContext(context);
    }

    @Override
    public void bind(InetSocketAddress address, int backlog) throws IOException {
        server.bind(address, backlog);
    }

    @Override
    public void start() {
        server.start();
    }

    @Override
    public void setExecutor(Executor executor) {
        server.setExecutor(executor);
    }

    @Override
    public Executor getExecutor() {
        return server.getExecutor();
    }

    @Override
    public void stop(int delay) {
        server.stop(delay);
    }

    @Override
    public InetSocketAddress getAddress() {
        return server.getAddress();
    }
}
