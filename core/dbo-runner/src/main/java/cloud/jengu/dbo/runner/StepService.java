package cloud.jengu.dbo.runner;

/**
 * One step, performed — the whole integration surface.
 *
 * <p>An integrator writes this and registers it; registration starts
 * consumption. Everything else — pulling, claiming, checkpointing, reporting,
 * vitals — is the runner's, written once. In an OSGi container, registering
 * this as a service is the whole wiring: the runner's activator tracks the
 * type, so a bundle contributes a step the way it contributes anything else.
 *
 * <p><b>No orchestrator is named here</b>, per the contract line the
 * catalogue draws: durability of {@code perform}'s own half-finished work is
 * the implementor's choice — a DBOS workflow on a server, nothing on an edge,
 * a person at a screen behind a UI. The runner owns the global truth either
 * way: what is owed, by whom, and what happened.
 */
public interface StepService {

    /** The step this performs: {@code <module>.<process>.<step>}, opaque and stable. */
    String step();

    /**
     * The declaration this service brings with it, when it performs a step
     * the catalogue has not declared — a linked participant carrying
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
     * holding the claim on behalf of an edge that cannot reach the lane —
     * waits for what it forwarded to. A wedged workflow or a silent edge
     * then blocks here, the claim lapses, and the run reads <i>released</i>:
     * visibly still owed, which is the outcome the design wants
     * (REQ-DBO-PROC-DONE-MEANS-DONE, REQ-DBO-PROC-THE-ROUTER-HOLDS-THE-CLAIM).
     */
    Outcome perform(Work work);
}
