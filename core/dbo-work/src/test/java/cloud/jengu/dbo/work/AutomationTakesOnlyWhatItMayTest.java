package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What automation is offered and what it may claim: what is open to it, and
 * nothing a person has been given.
 *
 * <p>Over the work domain's own records and feed, in a map: a poll reads the
 * feed and the run each event names, and a claim is the conditional write a
 * tenant makes, so what is decided here is decided on the record.
 */
class AutomationTakesOnlyWhatItMayTest {

    private static final String PROCESS = "ward.round";
    private static final String STEP = "check";

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());
    private final Executor worker = new Executor("worker", "1", "example.worker", Scope.BASELINE);

    @Test
    @DisplayName("a run open to people alone is offered to no automation, and a claim on it "
            + "is refused and leaves it theirs")
    @Proving(DboPromises.PROC_CLAIM_IS_THE_INTERSECTION)
    void aRunForPeopleIsNeitherOfferedNorTaken() {
        Run run = runs.pipeline(PROCESS, STEP);
        Run forPeople = runs.forPeople(run, "it needs somebody");

        List<Run> offered = new Participation(runs, held.feed(), "worker", Set.of(STEP), worker)
                .poll(50);

        assertTrue(offered.stream().noneMatch(one -> one.key().equals(run.key())),
                "automation was offered a run open to people alone: " + offered);
        boolean refused;
        try {
            refused = runs.claim(forPeople, worker, Instant.now().plus(Duration.ofMinutes(5)))
                    .isEmpty();
        } catch (IllegalStateException closed) {
            refused = true;
        }
        assertTrue(refused, "automation claimed a run open to people alone");
        Run after = runs.byKey(run.key()).orElseThrow();
        assertEquals(Status.READY, after.status(), "the run is no longer waiting: " + after);
        assertFalse(after.automation());
        assertNull(after.assignment() == null ? null : after.assignment().executor(),
                "an executor was named on a run open to people alone");
    }
}
