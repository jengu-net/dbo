package cloud.jengu.dbo.runner;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.time.Duration;

/**
 * Installing this bundle into an existing container is the whole deployment.
 *
 * <p>Whiteboard, both ways: any bundle registering a {@link StepService}
 * contributes a step, and the host registers one {@link Lane} per tenant it
 * offers work from. When either side appears the runner wires it; when it
 * goes, the runner lets it go. Nothing here is configured — the services ARE
 * the configuration, which is what lets this drop into the platform's cloud
 * process, its edge process, or a dev embedding without any of them changing.
 *
 * <p>Outside OSGi this class is simply never called: an embedder constructs
 * {@link StepRunner} and hands it lanes directly.
 */
public final class Activator implements BundleActivator {

    /** Framework properties, with the poll cadence the only dial. */
    static final String POLL_MILLIS = "dbo.runner.poll.millis";
    static final String HOLD_MILLIS = "dbo.runner.hold.millis";

    private StepRunner runner;
    private ServiceTracker<StepService, StepService> services;
    private ServiceTracker<Lane, Lane> lanes;

    @Override
    public void start(BundleContext context) {
        runner = new StepRunner(
                Duration.ofMillis(millis(context, HOLD_MILLIS, 300_000)),
                Duration.ofMillis(millis(context, POLL_MILLIS, 2_000)));
        services = new ServiceTracker<>(context, StepService.class,
                new ServiceTrackerCustomizer<>() {

                    @Override
                    public StepService addingService(ServiceReference<StepService> ref) {
                        StepService service = context.getService(ref);
                        runner.register(service);
                        return service;
                    }

                    @Override
                    public void modifiedService(ServiceReference<StepService> ref,
                            StepService service) {
                    }

                    @Override
                    public void removedService(ServiceReference<StepService> ref,
                            StepService service) {
                        runner.unregister(service.step());
                        context.ungetService(ref);
                    }
                });
        lanes = new ServiceTracker<>(context, Lane.class,
                new ServiceTrackerCustomizer<>() {

                    @Override
                    public Lane addingService(ServiceReference<Lane> ref) {
                        Lane lane = context.getService(ref);
                        runner.attach(lane);
                        return lane;
                    }

                    @Override
                    public void modifiedService(ServiceReference<Lane> ref, Lane lane) {
                    }

                    @Override
                    public void removedService(ServiceReference<Lane> ref, Lane lane) {
                        runner.detach(lane.tenant());
                        context.ungetService(ref);
                    }
                });
        services.open();
        lanes.open();
        runner.start();
    }

    @Override
    public void stop(BundleContext context) {
        if (services != null) {
            services.close();
        }
        if (lanes != null) {
            lanes.close();
        }
        if (runner != null) {
            runner.close();
        }
    }

    private static long millis(BundleContext context, String property, long absent) {
        String value = context.getProperty(property);
        return value == null ? absent : Long.parseLong(value);
    }
}
