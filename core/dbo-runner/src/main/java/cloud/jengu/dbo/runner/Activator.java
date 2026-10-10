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
 * the configuration, which is what lets this drop into a server
 * process, a worker process, or a dev embedding without any of them changing.
 *
 * <p>Outside OSGi this class is simply never called: an embedder constructs
 * {@link StepRunner} and hands it lanes directly.
 */
public final class Activator implements BundleActivator {

    /** Framework properties, with the poll cadence the only dial. */
    static final String POLL_MILLIS = "dbo.runner.poll.millis";
    static final String HOLD_MILLIS = "dbo.runner.hold.millis";
    /**
     * The step codes the host is about to register, comma-separated: the
     * runner asks no lane for work until it holds them all. Absent, it asks
     * from the start, as a container whose steps are bundles always has.
     */
    static final String AWAITS = "dbo.runner.awaits";

    private StepRunner runner;
    private ServiceTracker<StepService, StepService> services;
    private ServiceTracker<Lane, Lane> lanes;
    private ServiceTracker<HeartbeatStatistics, HeartbeatStatistics> statistics;

    @Override
    public void start(BundleContext context) {
        runner = new StepRunner(
                Duration.ofMillis(millis(context, HOLD_MILLIS, 300_000)),
                Duration.ofMillis(millis(context, POLL_MILLIS, 2_000)));
        String awaits = context.getProperty(AWAITS);
        if (awaits != null && !awaits.isBlank()) {
            runner.awaiting(java.util.Arrays.stream(awaits.split(","))
                    .map(String::trim).filter(code -> !code.isEmpty()).toList());
        }
        services = new ServiceTracker<>(context, StepService.class,
                new ServiceTrackerCustomizer<>() {

                    @Override
                    public StepService addingService(ServiceReference<StepService> ref) {
                        StepService service = context.getService(ref);
                        if (ref.getProperty(StepService.FOR_THE_FLEET) != null) {
                            // THE DEPLOYMENT'S, not a tenant's. Taking it up
                            // here would have this runner poll every lane it
                            // holds for a step no tenant declares, and try to
                            // introduce it to each of them — refused once per
                            // cycle, for as long as the worker runs.
                            context.ungetService(ref);
                            return null;
                        }
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
                        // This lane, not its tenant: a site holds a lane to
                        // the tenant in its cloud and one to its own place of
                        // it, and either may go while the other stays.
                        runner.detach(lane);
                        context.ungetService(ref);
                    }
                });
        // What a worker adds to its heartbeats. A contributor claiming the
        // store's namespace is refused by name and never asked.
        statistics = new ServiceTracker<>(context, HeartbeatStatistics.class,
                new ServiceTrackerCustomizer<>() {

                    @Override
                    public HeartbeatStatistics addingService(
                            ServiceReference<HeartbeatStatistics> ref) {
                        HeartbeatStatistics contributor = context.getService(ref);
                        try {
                            runner.contributing(contributor);
                        } catch (IllegalArgumentException refused) {
                            org.slf4j.LoggerFactory.getLogger(StepRunner.class).error(
                                    "heartbeat statistics refused: {}", refused.getMessage());
                            context.ungetService(ref);
                            return null;
                        }
                        return contributor;
                    }

                    @Override
                    public void modifiedService(ServiceReference<HeartbeatStatistics> ref,
                            HeartbeatStatistics contributor) {
                    }

                    @Override
                    public void removedService(ServiceReference<HeartbeatStatistics> ref,
                            HeartbeatStatistics contributor) {
                        runner.withdrawing(contributor);
                        context.ungetService(ref);
                    }
                });
        services.open();
        statistics.open();
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
        if (statistics != null) {
            statistics.close();
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
