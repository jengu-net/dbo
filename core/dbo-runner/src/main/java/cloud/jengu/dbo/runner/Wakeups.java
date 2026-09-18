package cloud.jengu.dbo.runner;

/**
 * A lane that can say when it may have work, so a runner need not wait out
 * its tick.
 *
 * <p><b>A wake-up is not an assignment.</b> It carries nothing: not the run,
 * not its inputs, not a claim. All it says is <em>look again</em>, and the
 * runner then polls and claims exactly as it does without one. That line is
 * where this whole mechanism lives or fails. A queue that handed work to a
 * runner would be the coordination {@link StepRunner} says the claim exists to
 * make unnecessary — the claim race is the scheduler — and it would put a
 * second answer beside the run record's account of what is owed and by whom.
 *
 * <p><b>The poll is the fallback, not the mechanism.</b> A runner waits for a
 * wake-up only up to its poll interval and then looks anyway, so a wake-up
 * that is never delivered is a latency bug rather than a lost run. That is
 * deliberate and is the property worth testing: the defect this whole area
 * came from was a failure that repeated in silence, and a delivery mechanism
 * nobody notices going missing would be the same shape again.
 *
 * <p>Offered by the binding rather than required of it. A lane with no way to
 * be told — the HTTP one, whose far side cannot reach back — simply does not
 * implement it, and nothing above the facade can tell the difference except by
 * how long it waited.
 */
@FunctionalInterface
public interface Wakeups {

    /**
     * Asks to be told when this lane may have work.
     *
     * <p>The callback is invoked on whatever thread noticed, may be invoked
     * spuriously, and must not block: it exists to end a sleep.
     *
     * @param woken run when there may be work
     * @return what stops the listening, closed when the lane is detached
     */
    AutoCloseable wake(Runnable woken);
}
