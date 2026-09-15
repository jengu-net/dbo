package cloud.jengu.dbo.probe;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.ProvingLane;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import java.util.Map;

/**
 * A driver bundle: it registers a step service and a lane, and that is all.
 *
 * <p>Everything the embeddable promise says was proved by tests that
 * construct the runner themselves — which proves the seam and says nothing
 * about the wiring. This bundle is the other half. It knows nothing about the
 * runner beyond the two types it registers, holds no store, no transport and
 * no orchestrator, and does no wiring: if the work is performed, the
 * whiteboard did it.
 *
 * <p><b>It reports through a system property</b>, which looks crude and is
 * the point. The framework loads this bundle in its own class space, so a
 * test cannot hold a reference to anything in here and read it — anything
 * that could would be the test reaching inside the container, which is the
 * shape of proof this exists to replace. A property is the narrowest channel
 * that crosses the boundary without opening one.
 */
public final class ProbeActivator implements BundleActivator {

    /** Set to the run's key once the step has actually performed. */
    public static final String PERFORMED = "dbo.probe.performed";

    /** The step this driver contributes — its own, declared nowhere else. */
    public static final String STEP = "dbo.probe.assay.report";

    @Override
    public void start(BundleContext context) {
        System.clearProperty(PERFORMED);
        // The lane first: a runner that saw the service and had nowhere to
        // poll would sit quietly, and the test would read the same silence it
        // reads when the whiteboard is broken.
        context.registerService(Lane.class,
                ProvingLane.offering(STEP).with("specimen", "Basic", "{\"n\":1}").lane(),
                null);
        context.registerService(StepService.class, new Report(), null);
    }

    @Override
    public void stop(BundleContext context) {
        // The framework unregisters both on stop; the runner lets them go.
    }

    /** The step itself: it records that it ran, and finishes. */
    private static final class Report implements StepService {

        @Override
        public String step() {
            return STEP;
        }

        @Override
        public Outcome perform(Work work) {
            work.progress().milestone("read", Map.of("specimens", 1L));
            System.setProperty(PERFORMED, work.run().key());
            return Outcome.done(Map.of("reported", 1L));
        }
    }
}
