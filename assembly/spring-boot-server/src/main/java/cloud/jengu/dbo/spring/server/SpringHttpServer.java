package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.core.api.Audience;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.Reach;
import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * A server that binds nothing, because the application already did.
 *
 * <p>The tenant runtime owns exactly one {@link HttpServer} and every surface
 * it offers registers a context on it. That is one seam, and filling it puts
 * all of them — the records door, a tenant's own authority, provisioning, the
 * step surface, maintenance, erasure, content, the ops readouts — on the
 * application's own port, through its own filters, with its own TLS, in one
 * move.
 *
 * <p>{@code HttpServer} is an abstract class rather than a sealed factory, so
 * a subclass whose {@code createContext} records the handler and whose
 * lifecycle methods do nothing is a complete and legal one that never touches
 * a socket. Nothing in the runtime knows the difference, which is the point:
 * a surface written against the servlet API would be a second implementation
 * of a door that already exists, drifting from the first.
 */
public final class SpringHttpServer extends HttpServer {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.server");

    /**
     * Contexts by path, longest match first.
     *
     * <p>Reverse order, so the first key at or before a request's path is the
     * longest one that could match it — the rule the JDK server applies, kept
     * because the runtime relies on it: {@code /t/code/fhir} and
     * {@code /t/code/fhir/$something} are both contexts and only one of them
     * is meant to answer.
     */
    private final NavigableMap<String, Mounted> mounted =
            new TreeMap<>(java.util.Comparator.reverseOrder());

    @Override
    public synchronized HttpContext createContext(String path, HttpHandler handler) {
        Mounted context = new Mounted(this, path, handler);
        mounted.put(path, context);
        return context;
    }

    @Override
    public synchronized HttpContext createContext(String path) {
        return createContext(path, null);
    }

    @Override
    public synchronized void removeContext(String path) {
        mounted.remove(path);
    }

    @Override
    public synchronized void removeContext(HttpContext context) {
        removeContext(context.getPath());
    }

    /** The paths this application currently answers on behalf of the store. */
    public synchronized List<String> paths() {
        return new ArrayList<>(mounted.keySet());
    }

    /**
     * Serves one request, if the store has a door at this path.
     *
     * @return whether a surface answered. False means no context matched, and
     *         the caller's own routing carries on — an application's
     *         controllers and the store's surfaces share a port, and the
     *         store is not entitled to 404 for the whole of it.
     */
    public boolean serve(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Mounted context = contextFor(request.getRequestURI());
        if (context == null || context.getHandler() == null) {
            return false;
        }
        ServletHttpExchange exchange =
                new ServletHttpExchange(request, response, context);
        try {
            context.getHandler().handle(exchange);
        } finally {
            // Belt and braces, and it is the servlet container that makes it
            // necessary. The surfaces clear what a request's credential bound
            // — who is asking, what may be disclosed and under what purpose,
            // which organisations the answer may come from, which audience it
            // is answered as — because a thread is reused. On the serving
            // distribution a thread is created per request and dies with it,
            // so an omission there costs nothing; here the thread goes back
            // to a pool and serves whoever is next.
            //
            // The handler set is not closed. The next surface added will be
            // written by somebody who has not read this, and the cost of
            // being wrong is one caller's organisational reach applied to
            // another caller's request.
            Caller.clear();
            Disclosure.clear();
            Reach.clear();
            Audience.clear();
            exchange.close();
        }
        return true;
    }

    /** The longest context at or before this path, as the JDK server chooses. */
    private synchronized Mounted contextFor(String path) {
        for (Map.Entry<String, Mounted> candidate : mounted.tailMap(path, true).entrySet()) {
            if (path.startsWith(candidate.getKey())) {
                return candidate.getValue();
            }
        }
        return null;
    }

    // ─── What a host already did, and this must not do again ───────────

    /**
     * Nothing, and every one of these is deliberate.
     *
     * <p>The application bound the port, started the server, chose the
     * threads and will stop it. A tenant runtime closing would otherwise take
     * an application's whole web tier down with it.
     */
    @Override
    public void bind(InetSocketAddress address, int backlog) {
        LOG.debug("the store does not bind: this application's web tier is already listening");
    }

    @Override
    public void start() {
        // Already serving.
    }

    @Override
    public void stop(int delay) {
        // The application's to stop.
    }

    @Override
    public void setExecutor(Executor executor) {
        // The application's threads, and its choice of them.
    }

    @Override
    public Executor getExecutor() {
        return null;
    }

    /**
     * No address of its own.
     *
     * <p>The runtime asks, and answers with the port the deployment was
     * declared at instead — which is right, because what the outside world
     * reaches is a fact the application has and this object does not.
     */
    @Override
    public InetSocketAddress getAddress() {
        return null;
    }

    /** A context: what was mounted where, and on what. */
    private static final class Mounted extends HttpContext {

        private final HttpServer server;
        private final String path;
        private HttpHandler handler;
        private Authenticator authenticator;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<Filter> filters = new ArrayList<>();

        private Mounted(HttpServer server, String path, HttpHandler handler) {
            this.server = server;
            this.path = path;
            this.handler = handler;
        }

        @Override
        public HttpHandler getHandler() {
            return handler;
        }

        @Override
        public void setHandler(HttpHandler handler) {
            this.handler = handler;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public HttpServer getServer() {
            return server;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public List<Filter> getFilters() {
            return filters;
        }

        @Override
        public Authenticator setAuthenticator(Authenticator authenticator) {
            Authenticator before = this.authenticator;
            this.authenticator = authenticator;
            return before;
        }

        @Override
        public Authenticator getAuthenticator() {
            return authenticator;
        }
    }
}
