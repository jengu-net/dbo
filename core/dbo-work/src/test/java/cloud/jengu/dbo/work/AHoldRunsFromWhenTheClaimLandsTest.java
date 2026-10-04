package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim holds for its whole duration, counted from when it is written.
 *
 * <p>Over the work domain's own records, in a map, read through a store whose
 * every read takes a second of the store's clock — a loaded database, said
 * without waiting for one. The question is where a deadline is measured
 * from, and a slow read is what makes the two answers differ.
 */
class AHoldRunsFromWhenTheClaimLandsTest {

    private static final String PROCESS = "lab.result";
    private static final String STEP = "verify";
    private static final Duration A_READ = Duration.ofSeconds(1);

    private final WorkInMemory held = new WorkInMemory();
    private final Ticking clock = new Ticking(Instant.parse("2026-10-04T08:00:00Z"));
    /** The store's clock as it read when the last write landed. */
    private final AtomicReference<Instant> landed = new AtomicReference<>();
    private final Runs runs = new Runs(slow(held.store()), Steps.of(StepDeclaration.of(
            PROCESS + "." + STEP, "1", WorkModel.DOMAIN)), Runs.Claimable.NOBODY, clock);

    private final Executor first = new Executor("worker-a", "1", "example.lab",
            Scope.BASELINE);
    private final Executor second = new Executor("worker-b", "1", "example.lab",
            Scope.BASELINE);

    @Test
    @DisplayName("a claim with a short hold is nobody else's to take until the whole hold has "
            + "passed since it landed, however long the reads before it took")
    @Proving(DboPromises.PROC_A_HOLD_RUNS_FROM_WHEN_THE_CLAIM_LANDS)
    void aShortHoldStillHoldsFromWhenItLanded() {
        Run run = runs.pipeline(PROCESS, STEP, "short-hold");
        Duration hold = Duration.ofMillis(2500);

        Run claimed = runs.claim(run, first, hold).orElseThrow();

        assertEquals(landed.get().plus(hold), claimed.assignment().until(),
                "the deadline was not measured from when the claim was written");
        // The next participant's own read takes a second: well inside the
        // hold, counted from the write, and it must lose.
        assertTrue(runs.claim(run, second, hold).isEmpty(),
                "another participant took a run inside the hold of the claim that took it");
        assertTrue(runs.byKey(run.key()).orElseThrow().heldBy(first),
                "the first claim does not stand");
    }

    @Test
    @DisplayName("a checkpoint moves the deadline to the hold counted from when it is written")
    @Proving(DboPromises.PROC_A_HOLD_RUNS_FROM_WHEN_THE_CLAIM_LANDS)
    void aCheckpointExtendsFromWhenItLanded() {
        Run run = runs.pipeline(PROCESS, STEP, "checkpointed");
        Run claimed = runs.claim(run, first, Duration.ofSeconds(1)).orElseThrow();

        Run reported = runs.checkpoint(claimed, first, Map.of("read", 1L),
                Duration.ofSeconds(3));

        assertEquals(landed.get().plus(Duration.ofSeconds(3)), reported.assignment().until(),
                "the extended deadline was not measured from when the checkpoint was written");
    }

    /** Every read takes {@link #A_READ} of the store's clock; a write notes when it landed. */
    private ObjectStore slow(ObjectStore store) {
        return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "get", "getByIdentifier", "select", "page", "count" ->
                                clock.advance(A_READ);
                        case "put", "putIfAbsent" -> landed.set(clock.instant());
                        default -> { }
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }

    /** A clock that moves only when told to. */
    private static final class Ticking extends Clock {

        private final AtomicReference<Instant> now;

        Ticking(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(at -> at.plus(by));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
