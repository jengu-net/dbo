package cloud.jengu.dbo.embedded;

import org.osgi.framework.ServiceRegistration;

import java.util.Hashtable;
import java.util.Map;

/**
 * How a bean becomes something the container can see.
 *
 * <p>The runtime's extension points are whiteboards: a bundle registers a
 * {@code StepService} and the runner performs its step, registers a
 * {@code TenantObserver} and the tenant feeds it. The services ARE the
 * configuration, which is what lets the same code drop into a cloud process,
 * an edge process or an embedding without any of them changing. An
 * application holding one of these assemblies has beans where the runtime
 * expects bundles, and this is the one line between them.
 *
 * <p><b>Nothing here names OSGi to a caller.</b> An assembly hands over a
 * type, an instance and some properties and gets back something to close.
 * A {@code BundleContext} reachable from an assembly would be a
 * {@code BundleContext} reachable from an application the first time somebody
 * wanted one, and then it is the supported API.
 */
public final class DboRegistrar {

    private final EmbeddedRuntime runtime;

    DboRegistrar(EmbeddedRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Puts an instance where the container's whiteboards look.
     *
     * @param properties what the whiteboard selects on — the tenant a
     *                   service is for, the point a listener wants, the
     *                   consumer name an observer reads as. A whiteboard
     *                   refuses a registration missing what it needs, and the
     *                   assemblies catch that at context refresh instead, so
     *                   that the bean's own author hears it.
     * @return the registration, closed when the application stops
     */
    public <T> Registration register(Class<T> type, T service, Map<String, String> properties) {
        Hashtable<String, Object> said = new Hashtable<>(properties);
        ServiceRegistration<T> registered = runtime.context().registerService(type, service, said);
        return () -> {
            try {
                registered.unregister();
            } catch (IllegalStateException alreadyGone) {
                // The container went first. Nothing to withdraw, and a
                // shutdown that logs like a crash is where a real crash goes
                // to hide.
            }
        };
    }

    /** What an assembly holds until the application stops. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
