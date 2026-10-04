package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One claimed run, performed by a service and reported on its lane.
 *
 * <p>Here rather than in the runner because two things perform work now: a
 * runner polling a tenant's lane for the steps that tenant declared, and a
 * fleet consumer taking items from the queue of a step the deployment
 * declared. What differs is how the work was found. What must not differ is
 * this — how an outcome becomes a report — because "done with a tally" and
 * "released with a reason" are the two things a run can end as, and a second
 * implementation of that mapping is a second answer to what a run means.
 *
 * <p><b>The lane is asked for everything.</b> The inputs are resolved through
 * it, progress extends the hold through it, and the outcome lands through it —
 * so a service reached this way meets the tenant's rules whichever side found
 * the work, which is the whole of what makes the two levels one API.
 */
public final class Performing {

    private Performing() {
    }

    /**
     * Performs it, reports it, and says what happened.
     *
     * <p>Throwing is released rather than swallowed: released is not done, and
     * where the run goes next is the failure's to decide — back to automation
     * for a fault the step declared will pass, to a person otherwise — from
     * wherever the work had got to, which is why what is released is the
     * latest run and not the claim.
     *
     * @param lane     into the tenant whose work this is
     * @param claimed  the run, already held by this identity
     * @param service  what performs it
     * @param holdFor  how long each report extends the hold
     * @return what the service said, a {@code Failed} carrying what it threw,
     *         a {@code Refused} when the tenant would not hold its result, or
     *         a {@code Lost} when the run stopped being this participant's
     *         before it was finished — reported nowhere, because it is
     *         somebody else's to finish
     */
    public static Outcome performed(Lane lane, Run claimed, StepService service,
            Duration holdFor) {
        // The LATEST run, not the claim. Every report returns a new version and
        // the next one has to commit to it; reporting against the claim would
        // report against a run that has since moved.
        AtomicReference<Run> latest = new AtomicReference<>(claimed);
        try {
            Work work = new Work(lane.tenant(), claimed, lane.inputs(claimed),
                    new Work.Progress() {
                        @Override
                        public void checkpoint(Map<String, Long> counts) {
                            latest.set(lane.checkpoint(latest.get(), counts, holdFor));
                        }

                        @Override
                        public void milestone(String milestone, Map<String, Long> counts) {
                            latest.set(lane.milestone(latest.get(), milestone, counts, holdFor));
                        }
                    });
            Outcome outcome = service.perform(work);
            if (outcome instanceof Outcome.Done done) {
                Run reported = done.tally().isEmpty() ? latest.get()
                        : lane.checkpoint(latest.get(), done.tally(), holdFor);
                if (done.writes().isEmpty()) {
                    lane.closed(reported);
                } else {
                    // The result goes to the tenant whole, and the tenant
                    // answers with the run as it left it: closed over what it
                    // wrote, or ended because it would not write it.
                    Run ended = lane.committed(reported, null, done.writes());
                    if (ended.refused() != null) {
                        return new Outcome.Refused(ended.refused());
                    }
                }
            } else if (outcome instanceof Outcome.Failed failed) {
                // A service saying it failed has said nothing about whether
                // it will pass, so it goes to people unless it threw a fault
                // the step declared will.
                lane.released(latest.get(), failed.reason(), Failure.UNKNOWN);
            } else if (outcome instanceof Outcome.Refused refused) {
                // Only the tenant refuses a result. A service saying so on
                // the tenant's behalf has failed, and is released like one
                // rather than ending work nobody refused.
                String reason = "the service answered as the tenant: " + refused.reason();
                lane.released(latest.get(), reason, Failure.UNKNOWN);
                return Outcome.failed(reason);
            }
            return outcome;
        } catch (Runs.NotHeld lost) {
            // Not ours any more: somebody acted on the run first. Whatever was
            // refused — the inputs, a report, the close — nothing is released,
            // because a release in this participant's name would undo what the
            // other did.
            return new Outcome.Lost(lost.getMessage());
        } catch (RuntimeException thrown) {
            String reason = "the service threw: " + thrown.getMessage();
            try {
                lane.released(latest.get(), reason, Failure.of(thrown));
            } catch (Runs.NotHeld lost) {
                // The service failed on work that had already stopped being
                // ours; its failure is nobody's to route.
                return new Outcome.Lost(lost.getMessage());
            }
            return Outcome.failed(reason);
        }
    }
}
