package cloud.jengu.dbo.runner;

/**
 * One step, performed — the whole integration surface.
 *
 * <p>An integrator writes this and registers it; registration starts
 * consumption. Everything else — pulling, claiming, checkpointing, reporting,
 * the heartbeat — is the runner's, written once. In an OSGi container, registering
 * this as a service is the whole wiring: the runner's activator tracks the
 * type, so a bundle contributes a step the way it contributes anything else.
 *
 * <p><b>No orchestrator is named here</b>, per the contract line the
 * catalogue draws: durability of {@code perform}'s own half-finished work is
 * the implementor's choice — a DBOS workflow on a server, nothing on a worker,
 * a person at a screen behind a UI. The runner owns the global truth either
 * way: what is owed, by whom, and what happened.
 *
 * <p><b>Called on several threads at once.</b> A runner cycles each lane on
 * a thread of its own, so a service performing for two tenants, or for a
 * tenant in the cloud and its place on site, is performing twice at the same
 * time. Everything one call needs arrives in its {@link Work}.
 */
public interface StepService {

    /** The step this performs: {@code <module>.<process>.<step>}, opaque and stable. */
    /**
     * The service property that says a registration is the DEPLOYMENT's.
     *
     * <p>One interface, two levels, and something has to route. Not the author:
     * a step is written the same way either way, and which level declared the
     * code is the store's to know. Not the step code either — the runner is in
     * a worker and has no view of what the deployment declares.
     *
     * <p>So the ASSEMBLY says it, at the registration, because it is the one
     * party that already knows: a bean it took up as a fleet performer is
     * marked, and the runner leaves a marked one alone rather than polling a
     * tenant's lane for a step no tenant declares. Without this the runner
     * takes up every fleet bean beside it, tries to introduce each one to every
     * tenant it holds a lane into, and is refused once per cycle.
     */
    String FOR_THE_FLEET = "dbo.step.fleet";

    String step();

    /**
     * The declaration this service brings with it, when it performs a step
     * the catalogue has not declared — a participant on a lane carrying
     * its own capability. Empty is the honest default: a service performing
     * an installed step brings nothing, because the module already
     * contributed it. The runner introduces it beside the candidacy, so the
     * catalogue learns the step the moment presence can be derived.
     */
    default java.util.Optional<cloud.jengu.dbo.core.process.StepDeclaration> declaration() {
        return java.util.Optional.empty();
    }

    /**
     * Performs one run's work.
     *
     * <p>The {@link Work} arrives whole — the run and the objects it
     * references (REQ-DBO-PROC-INPUTS-ARRIVE-WITH-THE-WORK); a service never fetches,
     * which is what keeps the same service honest on a runner with nothing to
     * fetch from. Long work checkpoints through {@link Work#progress}, which
     * extends the claim — counts are the evidence, never a heartbeat.
     *
     * <p>Throwing is the same as returning {@link Outcome#failed}: the run is
     * released with the reason — released is not done — and a later cycle may
     * take it again (REQ-DBO-PROC-FAILURE-IS-RELEASED).
     *
     * <p><b>The outcome describes work that has already happened.</b> A run
     * closes on what this returns and the store has no view below that seam,
     * so returning {@code done} before the work is done leaves the store
     * holding a true-looking record of something that did not occur — and
     * nobody looks for work the store says is finished. A service with
     * durable execution underneath waits for its workflow rather than
     * returning its handle; a service that forwards the work — a router,
     * holding the claim on behalf of a routee that cannot reach the lane —
     * waits for what it forwarded to. A wedged workflow or a silent routee
     * then blocks here, the claim lapses, and the run reads <i>released</i>:
     * visibly still owed, which is the outcome the design wants
     * (REQ-DBO-PROC-DONE-MEANS-DONE, REQ-DBO-PROC-THE-ROUTER-HOLDS-THE-CLAIM).
     */
    Outcome perform(Work work);

    /**
     * That function, as a service performing that step.
     *
     * <p>Two methods is one more than a lambda can be, and most of the time the
     * step is a constant beside the code that performs it. This is for the
     * cases where writing a class to say a name would be the longer half of the
     * step — a test, or an application with a handful of small ones.
     */
    static StepService performing(String step, java.util.function.Function<Work, Outcome> doing) {
        return new StepService() {
            @Override
            public String step() {
                return step;
            }

            @Override
            public Outcome perform(Work work) {
                return doing.apply(work);
            }
        };
    }
}
