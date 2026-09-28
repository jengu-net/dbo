package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.Performing;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.work.Run;
import dev.dbos.transact.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one workflow class, dispatching on the step.
 *
 * <p><b>Concrete, and this bundle's, because the durable layer records the
 * implementation's class name.</b> A consumer that registered an application's
 * own bean as the workflow would have the queue carry a class name from
 * somebody's application, which the joiner writing the item cannot know. One
 * class here and a lookup inside it is what lets the two ends agree on a
 * constant.
 *
 * <p><b>What it dispatches to is a {@link StepService}</b>, the same interface
 * a step a single tenant declared is written as. There is no fleet-shaped step
 * API and there was no reason for one: the two levels differ in who declared
 * the step and how the work was found, and a step code belongs to exactly one
 * level — so the store always knows which side offers the work and an author
 * never has to say. What a service is handed carries the tenant, because one
 * of these answers for every tenant in the fleet.
 *
 * <p><b>It keeps nothing between asks.</b> What it holds is the registry of
 * which service performs which step — configuration, not state — and every
 * call is answered from the item it was handed. A performer that remembered
 * anything about a run between asks would be a consumer that cannot be
 * restarted, which is the one thing work that crosses a queue has to survive.
 */
public final class FleetPerformer implements FleetWork.Performs {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.fleet");

    /**
     * How long a performer holds a run while it works.
     *
     * <p>Long enough that ordinary work finishes inside it, and bounded so a
     * consumer that dies does not hold a tenant's run for ever: the hold
     * lapsing is how this store already says a performer stopped answering.
     */
    private static final Duration HOLD = Duration.ofMinutes(10);

    /** The tenant's lane and the run, claimed on it. */
    public record Held(Lane lane, Run run) {
    }

    /** How the run named by an item is found and held. */
    @FunctionalInterface
    public interface Claims {
        Optional<Held> held(String tenant, String step, String runKey, Duration holdFor);
    }

    /** Which service performs which step. Configuration, and the only thing held. */
    private final Map<String, StepService> services = new ConcurrentHashMap<>();
    private final Claims claims;

    public FleetPerformer(Claims claims) {
        this.claims = claims;
    }

    /** The service that performs this step, from now on. */
    public void performing(String stepCode, StepService service) {
        services.put(stepCode, service);
    }

    /** Withdraws it, leaving the step performed by nothing here. */
    public void stopPerforming(String stepCode) {
        services.remove(stepCode);
    }

    /**
     * One item.
     *
     * <p>Four strings and nothing else, because that is what a queue can hold.
     * The lane and the run are found on this side from them and never travel,
     * and a tenant gone while its work sat in the queue has no lane to report
     * through — which is not the service's problem to discover halfway
     * through, so the item is left for a pass when the tenant is back.
     */
    @Override
    @Workflow(name = "perform")
    public void perform(String tenant, String step, String runId, String runKey) {
        StepService service = services.get(step);
        if (service == null) {
            // LOUD, not quiet. A consumer listens to its own steps' queues and
            // to nothing else, so an item for a step nothing here performs
            // means the listening is wrong — and returning quietly would mark
            // the workflow done, drain the queue and lose a tenant's work
            // without anybody being told. Failing leaves the item where
            // somebody can see it.
            throw new IllegalStateException("this consumer took an item for '" + step
                    + "' and performs no such step, so its queue listening and the queues it "
                    + "registered disagree: tenant=" + tenant + " run=" + runId);
        }
        Optional<Held> held = claims.held(tenant, step, runKey, HOLD);
        if (held.isEmpty()) {
            LOG.info("no lane into {} for {}, so its work waits: run={}", tenant, step, runId);
            return;
        }
        // THE SAME MAPPING A RUNNER USES. How an outcome becomes a report is
        // not this side's to decide differently — see Performing.
        Outcome said = Performing.performed(held.get().lane(), held.get().run(), service, HOLD);
        if (said instanceof Outcome.Failed failed) {
            // Said once, at INFO, because it is the deployment's own work
            // failing rather than a request being refused — and the reason
            // itself is on the run, in the tenant's own store, where somebody
            // asking about that run will find it.
            LOG.info("a fleet step released its run: tenant={} step={} run={}",
                    tenant, step, runId);
        }
    }
}
