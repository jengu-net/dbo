package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.embedded.DboRegistrar;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.runner.transport.StreamCarrier;
import jakarta.servlet.ServletContext;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The clinic's WebSocket, on its own port, as a carrier the store opens every
 * tenant's door on.
 *
 * <p>Two registrations and nothing else. The socket goes on the application's
 * servlet container, which the embedded server already provides — Tomcat
 * brings the standard WebSocket API, so no library is added for it. And the
 * carrier goes on the container's whiteboard, where the store looks: from then
 * on each tenant this node serves opens a door on it, and each that comes up
 * later does too.
 *
 * <p>Stopping this withdraws the carrier, which closes every door on it and so
 * every socket; a worker asking meanwhile hears its store is unreachable and
 * asks again. Starting it again opens the doors again, and the workers come
 * back on their next ask.
 */
@Component
public final class OpeningTheSocket implements SmartLifecycle {

    /** Where a worker connects for one tenant's door. */
    public static final String PATH = "/stream/{tenant}";

    private static final Logger LOG = LoggerFactory.getLogger("dbo.sample.socket");

    private final EmbeddedRuntime container;
    private final ObjectProvider<ServletContext> servlet;
    private final AnsweringOverASocket carrier = new AnsweringOverASocket();
    private boolean mounted;
    private DboRegistrar.Registration carrying;

    OpeningTheSocket(EmbeddedRuntime container, ObjectProvider<ServletContext> servlet) {
        this.container = container;
        this.servlet = servlet;
    }

    // --8<-- [start:opening]
    @Override
    public synchronized void start() {
        if (!mounted) {
            ServletContext context = servlet.getIfAvailable();
            ServerContainer sockets = context == null ? null
                    : (ServerContainer) context.getAttribute(ServerContainer.class.getName());
            if (sockets == null) {
                // Not a web application, or a server without WebSocket: there
                // is nowhere to carry the stream over, and nothing else here
                // depends on it.
                LOG.info("no WebSocket container here: the stream is not carried over a socket");
                return;
            }
            try {
                sockets.addEndpoint(ServerEndpointConfig.Builder
                        .create(AnsweringOverASocket.Connection.class, PATH)
                        .configurator(new ServerEndpointConfig.Configurator() {
                            @Override
                            public <T> T getEndpointInstance(Class<T> type) {
                                return type.cast(carrier.connection());
                            }
                        })
                        .build());
            } catch (DeploymentException refused) {
                throw new IllegalStateException("the socket could not be mounted at " + PATH,
                        refused);
            }
            mounted = true;
        }
        carrying = container.registrar().register(StreamCarrier.class, carrier, Map.of());
    }
    // --8<-- [end:opening]

    @Override
    public synchronized void stop() {
        if (carrying != null) {
            carrying.close();
            carrying = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return carrying != null;
    }
}
