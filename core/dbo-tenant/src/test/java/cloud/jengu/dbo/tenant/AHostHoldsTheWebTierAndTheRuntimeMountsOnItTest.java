package cloud.jengu.dbo.tenant;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runtime mounts on a server somebody else is running, and leaves it alone.
 *
 * <p>Every surface a tenant offers is a context on one {@code HttpServer}:
 * the records door, the tenant's own authority, provisioning, steps,
 * maintenance, erasure, content, the ops readouts. That is the whole of what
 * a host has to fill, which is why a host with a web tier of its own can have
 * all of them at once without a second implementation of any.
 *
 * <p>No database here, and none needed. What is being proved is the seam
 * rather than what comes through it: who binds the port, who starts the
 * server, who reports the port, and who takes it down. A tenant coming up on
 * a host's server is a claim for a world with a store in it, and it is a
 * different claim from this one.
 *
 * <p>The hub context is the evidence a surface reaches the supplied server at
 * all. It is the one this runtime mounts before any tenant exists — a
 * deployment with an upstream broker gets it in the constructor — so it is
 * the only door reachable without a database, and it travels the same
 * {@code createContext} every other door travels.
 */
class AHostHoldsTheWebTierAndTheRuntimeMountsOnItTest {

    /** Any port a host might be answering on, and not one bound here. */
    private static final int DECLARED = 8443;

    @Test
    @DisplayName("a runtime handed a server mounts its surfaces on it, binds nothing, starts "
            + "nothing, answers with the port it was told, and does not take the server down")
    void aRuntimeHandedAServerMountsOnItAndLeavesItAlone(@TempDir Path specs) {
        Recording hostsServer = new Recording();
        TenantRuntimeManager runtime = new TenantRuntimeManager(specs, new ProvisionsNothing(),
                "app.example", DECLARED, null, withABroker(),
                cloud.jengu.dbo.fhir.common.FhirVersions.installed(),
                cloud.jengu.dbo.core.process.Steps.installed(), hostsServer);

        assertTrue(hostsServer.mounted.contains("/hub"),
                "the runtime mounted nothing on the server it was handed, so a host's web tier "
                        + "would answer 404 to every surface a tenant offers");
        assertFalse(hostsServer.bound,
                "the runtime bound a server the host had already bound");
        assertFalse(hostsServer.started,
                "the runtime started a server the host is already running");
        assertFalse(hostsServer.executorSet,
                "the runtime replaced the host's executor, so requests the host serves would "
                        + "run on threads the runtime chose");
        assertEquals(DECLARED, runtime.port(),
                "the runtime answered with something other than the port it was told the "
                        + "deployment is reached on — a supplied server has no address of its "
                        + "own, so every self-link and every issuer would be wrong");

        runtime.close();

        assertFalse(hostsServer.stopped,
                "closing the runtime stopped the host's server, so an application's whole web "
                        + "tier goes down because a tenant runtime was closed");
    }

    @Test
    @DisplayName("a runtime handed no server still binds one of its own, which is what the "
            + "serving distribution does and what a declared port of 0 rests on")
    void aRuntimeHandedNoServerBindsItsOwn(@TempDir Path specs) {
        TenantRuntimeManager runtime = new TenantRuntimeManager(specs, new ProvisionsNothing(),
                "127.0.0.1", 0, null, null);
        try {
            // 0 means "bind anywhere and then say where". An answer of 0 would
            // mean the declared port had shadowed the bound one, which is the
            // way the change above breaks this path.
            assertTrue(runtime.port() > 0,
                    "the runtime reported port " + runtime.port() + ", so it either did not "
                            + "bind or answered with the port it was declared at instead of "
                            + "the one it got");
        } finally {
            runtime.close();
        }
    }

    /** A deployment with an upstream broker, which is what mounts the hub. */
    private static TenantRuntimeManager.AuthorityConfig withABroker() {
        return new TenantRuntimeManager.AuthorityConfig(new byte[32],
                "https://app.example",
                new cloud.jengu.dbo.auth.IdentityHub.Upstream(
                        "https://broker.example", "a-client", "a-secret", null),
                "urn:example:subject");
    }

    /** The host's server: it says what was done to it and does none of it. */
    private static final class Recording extends HttpServer {

        private final List<String> mounted = new ArrayList<>();
        private boolean bound;
        private boolean started;
        private boolean stopped;
        private boolean executorSet;

        @Override
        public void bind(InetSocketAddress address, int backlog) {
            bound = true;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void setExecutor(Executor executor) {
            executorSet = true;
        }

        @Override
        public Executor getExecutor() {
            return null;
        }

        @Override
        public void stop(int delay) {
            stopped = true;
        }

        @Override
        public HttpContext createContext(String path, HttpHandler handler) {
            mounted.add(path);
            return new Mounted(this, path, handler);
        }

        @Override
        public HttpContext createContext(String path) {
            return createContext(path, null);
        }

        @Override
        public void removeContext(String path) {
            mounted.remove(path);
        }

        @Override
        public void removeContext(HttpContext context) {
            removeContext(context.getPath());
        }

        /**
         * No address, which is the fact this whole arrangement turns on: a
         * server standing in for somebody else's web tier has none of its own,
         * and the port the outside world reaches is the host's to declare.
         */
        @Override
        public InetSocketAddress getAddress() {
            return null;
        }
    }

    /**
     * What {@code createContext} hands back.
     *
     * <p>Nothing in this runtime reads it — every call site drops it — so
     * this exists to be a legal answer rather than a useful one. Written out
     * instead of returning null because the next caller that does read it
     * should meet a context, not a fault three frames away from the stub that
     * caused it.
     */
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

    /** A stub provisioner: this test never brings a tenant up. */
    private static final class ProvisionsNothing implements TenantDatabaseProvisioner {

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            throw new UnsupportedOperationException("no tenant comes up in this test");
        }

        @Override
        public void deprovision(String tenantCode) {
        }
    }

}
