package cloud.jengu.dbo.samples.ward;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import java.util.Map;

/**
 * A step of the clinic's own, shipped as a bundle: it registers the step it
 * performs, and that is the whole of its wiring.
 *
 * <p>Hogwarts declares {@code hogwarts.ward.observe} and nothing in the
 * clinic's application performs it as a bean. This bundle does, from inside
 * the framework the application owns: registering a {@code StepService} is
 * what a bundle does to contribute a step, and the runner's whiteboard takes
 * it up beside the beans the assembly registered. No lane, no token and no
 * tenant are named here; those are the application's, configured once.
 *
 * <p>What it counts says where it ran. {@code bundle} in the tally is this
 * bundle's id, which only code loaded by a bundle can know.
 */
// --8<-- [start:bundle]
public final class ObservingTheWard implements BundleActivator {

    /** The step Hogwarts declares and this bundle performs. */
    public static final String STEP = "hogwarts.ward.observe";

    @Override
    public void start(BundleContext context) {
        long bundle = context.getBundle().getBundleId();
        context.registerService(StepService.class, StepService.performing(STEP,
                work -> Outcome.done(Map.of("observed", 1L, "bundle", bundle))), null);
    }

    @Override
    public void stop(BundleContext context) {
        // The framework withdraws what this registered, and the runner lets it go.
    }
}
// --8<-- [end:bundle]
