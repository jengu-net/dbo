package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.runner.StepRunner;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waiting for something that arrives over a change feed.
 *
 * <p>Here because three test classes had independently grown the same helper,
 * with the same body and the same thirty seconds, and a fourth wait somewhere
 * else went red on CI on a commit that could not have touched it. The number
 * belongs in one place, and so does the sentence that explains a timeout.
 *
 * <p><b>Why these waits are not budgets.</b> {@code PgChangeFeed} delivers an
 * event only once its transaction is below the local horizon, and that horizon
 * is the local one only while the database is write-quiet. A test whose
 * subject writes continuously — a step runner declares itself on every cycle —
 * never is, so it takes the cluster-wide {@code xmin} path instead, and these
 * classes share one Postgres four at a time. A long transaction in a
 * neighbouring class's database then holds this test's event off the feed for
 * as long as it lasts.
 *
 * <p>That barrier is deliberate and correct: without it a writer committing
 * between a reader's snapshot and its liveness scan is visible in neither and
 * its event is skipped for ever. What follows for a test is that <b>how long
 * arrival takes is a property of the whole suite rather than of the test</b>,
 * so a wait sized to what one machine managed once is a coin flip on every
 * other machine.
 *
 * <p>Where a budget genuinely is the subject — cold start, second boot — it is
 * measured against a number somebody chose, and does not belong here.
 */
final class Eventually {

    /**
     * Long enough that a timeout means something is wrong rather than busy.
     *
     * <p>Nothing waits this long when it works: every loop returns on the
     * first pass that sees its condition. The cost of a generous number is
     * paid only by a test that was going to fail anyway, and the cost of a
     * tight one is paid by everybody else's commits.
     */
    static final Duration PATIENCE = Duration.ofSeconds(240);

    /** Between passes, so a wait is not a busy loop against a shared server. */
    private static final Duration BETWEEN = Duration.ofMillis(50);

    private Eventually() {
    }

    /**
     * Cycles the runner until the condition holds.
     *
     * <p>A cycle is one poll, so arrival is eventual and never a property of a
     * particular pass — which is the whole convention: "did it arrive" is a
     * question about eventual arrival.
     *
     * @param what what is being waited for, as the sentence a failure should
     *             say. It is the difference between a timeout that names the
     *             thing that did not happen and one that says a condition was
     *             false.
     */
    static void cycling(StepRunner runner, String what, BooleanSupplier done) {
        until(what, runner::cycle, done);
    }

    /**
     * The same, driving something other than a runner — a sync round, a
     * dispatch, a reconcile.
     *
     * @param nudge what to do on each pass to give the thing a chance to
     *              happen, or a no-op where something else is driving it
     */
    static void until(String what, Runnable nudge, BooleanSupplier done) {
        long began = System.nanoTime();
        long deadline = began + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            nudge.run();
            if (done.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(BETWEEN.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (done.getAsBoolean()) {
                    return;
                }
                throw new AssertionError("interrupted while waiting for " + what);
            }
        }
        throw new AssertionError(what + " did not happen in "
                + Duration.ofNanos(System.nanoTime() - began).toSeconds() + "s. That is long "
                + "enough that this is not a slow machine: either it never happens, or the "
                + "event it depends on is still behind the feed's transaction horizon and "
                + "something in this database is holding a transaction open.");
    }
}
