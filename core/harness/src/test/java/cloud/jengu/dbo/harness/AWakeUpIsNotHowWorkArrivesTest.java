package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.InProcessWakeups;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.ProvingLane;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.work.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wake-up ends a sleep. It does not deliver work, and work does not depend
 * on it arriving.
 *
 * <p>Both halves are asserted here because each is a different failure. A
 * wake-up that delivered work would be the coordination the claim exists to
 * make unnecessary, with a second answer beside the run record's account of
 * who holds what. A runner that only worked when woken would have turned a
 * latency improvement into a lost run the first time a notification went
 * missing — and a delivery mechanism nobody notices going absent is the shape
 * of the defect this whole area came from.
 */
class AWakeUpIsNotHowWorkArrivesTest {

    /** A service that counts what it was asked to perform. */
    private record Counting(String step, AtomicInteger performed, CountDownLatch done)
            implements StepService {

        @Override
        public Outcome perform(Work work) {
            performed.incrementAndGet();
            done.countDown();
            return Outcome.done();
        }
    }

    @Test
    @Proving(DboPromises.PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES)
    @DisplayName("a runner whose lane can say nothing still does the work, on its poll")
    void theWorkArrivesWithoutAWakeUpAtAll() throws Exception {
        // A lane that offers no wake-ups — which is every lane that existed
        // before this, and is still the honest answer for a binding whose far
        // side cannot reach back. Nothing here is degraded; it waits.
        ProvingLane lane = ProvingLane.offering("clinic.admission.admit").lane();
        assertTrue(lane.wakeups().isEmpty(),
                "the harness lane claims a wake-up it cannot deliver, so this test would "
                        + "prove the opposite of what it says");

        AtomicInteger performed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(20))
                .register(new Counting("clinic.admission.admit", performed, done))
                .attach(lane)
                .start()) {
            assertTrue(done.await(10, TimeUnit.SECONDS),
                    "the work was never performed, so the poll is no longer the fallback "
                            + "underneath the wake-up — it has become something the runner "
                            + "relies on being told");
        }
        assertEquals(1, performed.get());
    }

    @Test
    @Proving(DboPromises.PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES)
    @DisplayName("and a wake-up carries nothing: the store tells it to look, and it claims "
            + "the way it always did")
    void aWakeUpSaysLookAgainAndNothingElse() throws Exception {
        InProcessWakeups wakeups = new InProcessWakeups();
        AtomicInteger looked = new AtomicInteger();
        wakeups.wake(looked::incrementAndGet);
        assertTrue(wakeups.listening());

        // What the store side does when a run becomes claimable. The run is
        // handed over on this call and is deliberately not passed on: a
        // listener is told to look, and finds the work itself.
        wakeups.appeared(Run.named("clinic.admission.admit/one"));
        assertEquals(1, looked.get(), "nobody was told a run became claimable");

        // That a wake-up carries nothing is not asserted here, because it
        // cannot be: `Wakeups.wake` takes a Runnable, so there is no argument
        // for a run to arrive in and the compiler is the check. Widening it to
        // a Consumer<Run> is the change that would make a wake-up a delivery,
        // and it would break every caller rather than quietly passing.
    }

    @Test
    @Proving(DboPromises.PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES)
    @DisplayName("a listener that throws does not stop the others, nor the work that "
            + "already happened")
    void oneBadListenerIsNotAnOutage() {
        InProcessWakeups wakeups = new InProcessWakeups();
        AtomicInteger after = new AtomicInteger();
        wakeups.wake(() -> {
            throw new IllegalStateException("no");
        });
        wakeups.wake(after::incrementAndGet);

        // Does not throw: the run was written before anybody was told, and a
        // listener cannot un-write it.
        wakeups.appeared(Run.named("clinic.admission.admit/two"));
        assertEquals(1, after.get(),
                "one listener's failure stopped another, so who gets woken depends on the "
                        + "order somebody registered in");
    }

    @Test
    @Proving(DboPromises.PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES)
    @DisplayName("and listening stops when the lane goes, so a wake-up cannot arrive for a "
            + "tenant nobody serves")
    void listeningEndsWithTheLane() throws Exception {
        InProcessWakeups wakeups = new InProcessWakeups();
        AutoCloseable listening = wakeups.wake(() -> { });
        assertTrue(wakeups.listening());

        listening.close();
        assertFalse(wakeups.listening(),
                "a subscription outlived its lane, so this runner would be woken on behalf "
                        + "of a tenant it no longer serves");
    }
}
