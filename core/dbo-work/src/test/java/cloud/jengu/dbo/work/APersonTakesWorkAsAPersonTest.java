package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A person who takes work holds it as a person.
 *
 * <p>Over the work domain's own records, in a map: what a person's claim
 * writes is the run's record, and that is what every list of work reads.
 */
class APersonTakesWorkAsAPersonTest {

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());
    private final Executor workplace = new Executor("ward-screen", "1", "example.ward",
            Scope.BASELINE);

    @Test
    @DisplayName("a person opening a run at a workplace holds it as a person, and no device is "
            + "named as what holds it")
    @Proving(DboPromises.PROC_RUN_SAYS_WHO_HOLDS_IT)
    void aPersonOpeningARunHoldsItAsAPerson() {
        Run run = runs.pipeline("ward.round", "check");
        Runner runner = new Runner(runs, held.feed(),
                new Declarations(held.store(), held.feed(), Duration.ofMinutes(1)),
                new Declarations.Declared("ward.round", "check", workplace.name(),
                        workplace.version(), workplace.provider(), Scope.BASELINE,
                        "ward-screen"), Duration.ofMinutes(5));

        Run taken = runner.open(run.key(), "PractitionerRole/nurse").orElseThrow();

        assertEquals(Holder.PERSON, taken.holder(), "a person's claim made the run automation's");
        assertNull(taken.assignment().executor(), "a device was named as holding a person's run");
        assertEquals("PractitionerRole/nurse", taken.assignment().role());
        assertEquals(Status.IN_PROGRESS, taken.status());
    }
}
