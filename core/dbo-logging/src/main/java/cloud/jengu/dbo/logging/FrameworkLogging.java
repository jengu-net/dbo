package cloud.jengu.dbo.logging;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The container's own events, in the same stream as everything else.
 *
 * <p>Without this the runtime has two logs: dbo's, structured and levelled,
 * and the framework's, written straight to the console in its own format. The
 * second one is where the interesting failures live — a bundle that would not
 * resolve, an activator that threw — so an operator ends up reading the one
 * log that is hardest to read, at exactly the moment it matters.
 *
 * <p>This listens through the <b>standard OSGi API</b> rather than reaching
 * into Felix. {@code felix.log.logger} would take an implementation-specific
 * logger object, and injecting one requires launching the framework from our
 * own code — which the serving distribution deliberately does not do, because
 * what runs in production being exactly the standard launcher is a property
 * worth more than tidier framework logs.
 *
 * <p><b>What this therefore does not capture:</b> anything the framework
 * writes before this bundle starts, and Felix's own console writes for events
 * it does not also publish. {@code felix.log.level} governs those, and the
 * launcher keeps it in step with {@code dbo.log.level}.
 */
public final class FrameworkLogging implements BundleActivator {

    /**
     * Resolved on first use, never at class load.
     *
     * <p>A static {@code Logger} field here initialises slf4j while this very
     * bundle is starting — before the mediator has registered the provider it
     * is looking for. slf4j caches that miss permanently and the whole runtime
     * falls back to no-op logging: the binding silences itself by using
     * itself. slf4j caches loggers anyway, so asking per event costs nothing.
     */
    private static Logger log() {
        return LoggerFactory.getLogger("dbo.container");
    }

    private FrameworkListener frameworkListener;
    private BundleListener bundleListener;

    @Override
    public void start(BundleContext ctx) {
        frameworkListener = event -> {
            String bundle = event.getBundle() == null
                    ? "framework" : event.getBundle().getSymbolicName();
            switch (event.getType()) {
                // The ones worth waking somebody for: a bundle that failed to
                // resolve or an activator that threw arrives here, and it is
                // the difference between "a tenant did not come up" and
                // knowing why.
                case FrameworkEvent.ERROR ->
                        log().error("container error: bundle={}", bundle, event.getThrowable());
                case FrameworkEvent.WARNING ->
                        log().warn("container warning: bundle={}", bundle, event.getThrowable());
                case FrameworkEvent.INFO ->
                        log().info("container: bundle={}", bundle);
                case FrameworkEvent.STARTED ->
                        log().info("container started: bundles={}",
                                ctx.getBundles().length);
                case FrameworkEvent.STOPPED ->
                        log().info("container stopped");
                default -> {
                    // start-level changes and refreshes: real, and not worth
                    // a line of an operator's attention
                }
            }
        };
        ctx.addFrameworkListener(frameworkListener);

        // Per-bundle lifecycle is DEBUG: nineteen bundles starting is nineteen
        // lines saying the expected thing. It is the first place to look when
        // the container is wrong, and noise every other time.
        bundleListener = event -> {
            if (log().isDebugEnabled()) {
                log().debug("bundle {}: {}", kindOf(event.getType()),
                        event.getBundle().getSymbolicName());
            }
        };
        ctx.addBundleListener(bundleListener);
    }

    @Override
    public void stop(BundleContext ctx) {
        if (frameworkListener != null) {
            ctx.removeFrameworkListener(frameworkListener);
        }
        if (bundleListener != null) {
            ctx.removeBundleListener(bundleListener);
        }
    }

    private static String kindOf(int type) {
        return switch (type) {
            case BundleEvent.INSTALLED -> "installed";
            case BundleEvent.RESOLVED -> "resolved";
            case BundleEvent.STARTED -> "started";
            case BundleEvent.STOPPED -> "stopped";
            case BundleEvent.UPDATED -> "updated";
            case BundleEvent.UNINSTALLED -> "uninstalled";
            case BundleEvent.UNRESOLVED -> "unresolved";
            default -> "event-" + type;
        };
    }
}
