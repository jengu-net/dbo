package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the participant holding a run says anything about it as its holder.
 *
 * <p>Over the work domain's own records, in a map: who holds a run is what
 * its record says, and every verb a holder takes is judged against the
 * version it would replace.
 */
class OnlyTheHolderActsOnARunTest {

    private static final String PROCESS = "lab.result";
    private static final String STEP = "verify";

    private final WorkInMemory held = new WorkInMemory();
    /** Run once, just before the next write lands — the other writer arriving in between. */
    private final AtomicReference<Runnable> beforeTheNextWrite = new AtomicReference<>();
    /** A lapse goes back to automation, so a lapsed claim is there for another to take. */
    private final Runs runs = new Runs(racing(held.store()), Steps.of(StepDeclaration.of(
            PROCESS + "." + STEP, "1", WorkModel.DOMAIN)
            .retrying(new cloud.jengu.dbo.core.process.RetryPolicy(List.of("lapsed"), "PT0S",
                    5))));

    private final Executor first = new Executor("analyser-a", "1", "example.lab",
            Scope.BASELINE);
    private final Executor second = new Executor("analyser-b", "1", "example.lab",
            Scope.BASELINE);

    @Test
    @DisplayName("a participant that does not hold a run is refused its checkpoint, its "
            + "milestone, its hand-back and its close, and the run is as it was")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aNonHolderIsRefusedEveryVerb() {
        Run run = runs.pipeline(PROCESS, STEP, "held-by-a");
        Run claimed = runs.claim(run, first, Duration.ofMinutes(5)).orElseThrow();

        refusedAndUnchanged(claimed, () -> runs.checkpoint(claimed, second,
                Map.of("read", 1L), Duration.ofMinutes(5)), "checkpoint");
        refusedAndUnchanged(claimed, () -> runs.milestone(claimed, second, "read",
                Map.of(), Duration.ofMinutes(5)), "milestone");
        refusedAndUnchanged(claimed, () -> runs.released(claimed, second,
                "the service threw: nobody here holds this", Failure.UNKNOWN), "release");
        refusedAndUnchanged(claimed, () -> runs.closed(claimed, second), "close");
        refusedAndUnchanged(claimed, () -> runs.closed(claimed, second,
                List.of("Observation/o1/1")), "close over a result");
        refusedAndUnchanged(claimed, () -> runs.refused(claimed, second, "not yours"),
                "refusal");
    }

    @Test
    @DisplayName("a holder whose lapsed claim housekeeping handed back is refused every verb, "
            + "and the run stays where housekeeping routed it")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aFormerHolderIsRefusedEveryVerb() {
        Run run = runs.pipeline(PROCESS, STEP, "handed-back");
        Run claimed = runs.claim(run, first, Duration.ZERO).orElseThrow();
        assertEquals(1, Participation.releaseLapsed(runs),
                "housekeeping did not hand the lapsed claim back");
        Run handedBack = runs.byKey(run.key()).orElseThrow();

        refusedAndUnchanged(handedBack, () -> runs.checkpoint(claimed, first,
                Map.of("read", 1L), Duration.ofMinutes(5)), "checkpoint");
        refusedAndUnchanged(handedBack, () -> runs.milestone(claimed, first, "read",
                Map.of(), Duration.ofMinutes(5)), "milestone");
        refusedAndUnchanged(handedBack, () -> runs.released(claimed, first,
                "the service threw: not claimed", Failure.UNKNOWN), "release");
        refusedAndUnchanged(handedBack, () -> runs.closed(claimed, first), "close");
    }

    @Test
    @DisplayName("a holder's claim handed back and taken by another while its release is on "
            + "the way: the late release changes nothing, and the new claim stands")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aLateReleaseLosesTheRace() {
        Run run = runs.pipeline(PROCESS, STEP, "raced");
        Run claimedByA = runs.claim(run, first, Duration.ZERO).orElseThrow();

        // Between the moment A's release reads the run and the moment it
        // writes, housekeeping hands A's lapsed claim back and B takes it.
        beforeTheNextWrite.set(() -> {
            Participation.releaseLapsed(runs);
            runs.claim(runs.byKey(run.key()).orElseThrow(), second, Duration.ofMinutes(5))
                    .orElseThrow();
        });
        Runs.NotHeld refused = assertThrows(Runs.NotHeld.class, () -> runs.released(claimedByA,
                        first, "the service threw: not claimed", Failure.UNKNOWN),
                "a release by a participant whose claim was taken over landed");

        Run after = runs.byKey(run.key()).orElseThrow();
        assertTrue(after.heldBy(second), "B's claim did not survive A's late release: "
                + after.assignment() + " " + after.status());
        assertTrue(after.automation(), "A's late release sent B's run to people: " + after);
        assertTrue(refused.getMessage().contains("not claimed by analyser-a"),
                "refused, but not for holding nothing: " + refused.getMessage());

        // And B, which does hold it, is answered as a holder.
        Run closed = runs.closed(after, second);
        assertEquals(Status.COMPLETED, closed.status());
    }

    @Test
    @DisplayName("housekeeping that finds a claim lapsed does not hand it back once its holder "
            + "has reported, however close the two writes land")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void housekeepingLosesToAHolderThatReported() {
        Run run = runs.pipeline(PROCESS, STEP, "reported");
        Run claimed = runs.claim(run, first, Duration.ZERO).orElseThrow();
        List<Run> lapsed = runs.lapsed(java.time.Instant.now());
        assertEquals(1, lapsed.size(), "the claim did not lapse: " + lapsed);

        // The holder reports between housekeeping finding the claim and
        // handing it back.
        beforeTheNextWrite.set(() -> runs.checkpoint(claimed, first, Map.of("read", 1L),
                Duration.ofMinutes(5)));
        assertTrue(runs.handBack(lapsed.get(0), java.time.Instant.now()).isEmpty(),
                "housekeeping handed back a claim whose holder had just reported");

        Run after = runs.byKey(run.key()).orElseThrow();
        assertTrue(after.heldBy(first), "the holder's report was undone: " + after.assignment());
    }

    private void refusedAndUnchanged(Run before, Executable verb, String what) {
        Run stored = runs.byKey(before.key()).orElseThrow();
        assertThrows(Runs.NotHeld.class, verb,
                "a " + what + " by a participant that does not hold the run landed");
        Run after = runs.byKey(before.key()).orElseThrow();
        assertEquals(stored.versionId(), after.versionId(),
                "a refused " + what + " still wrote a version: " + after);
        assertEquals(stored.assignment(), after.assignment(), "a refused " + what
                + " changed who holds the run");
        assertEquals(stored.status(), after.status(), "a refused " + what
                + " changed where the run stands");
    }

    /** The store, letting a test put another writer between a read and the write after it. */
    private ObjectStore racing(ObjectStore store) {
        return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class}, (proxy, method, args) -> {
                    if ("put".equals(method.getName())) {
                        Runnable other = beforeTheNextWrite.getAndSet(null);
                        if (other != null) {
                            other.run();
                        }
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }
}
