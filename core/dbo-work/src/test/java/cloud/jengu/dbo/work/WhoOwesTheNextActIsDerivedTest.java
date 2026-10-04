package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who owes a run's next act, read off what the run stores, and asked of many
 * runs at once from the envelope.
 *
 * <p>Over the work domain's own records, in a map: the question is answered
 * from the envelope WorkModel extracts, which is what a tenant's index holds.
 */
class WhoOwesTheNextActIsDerivedTest {

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());
    private final Executor worker = new Executor("worker", "1", "example.worker", Scope.BASELINE);

    @Test
    @DisplayName("a claimed run waits for its owner, an unclaimed one for a machine or a "
            + "person by who may take it, and one that is over for nothing — and each list "
            + "holds exactly those")
    @Proving(DboPromises.PROC_WHO_OWES_THE_NEXT_ACT_IS_DERIVED)
    void whoIsAwaitedIsDerived() {
        Instant now = Instant.now();
        Run machine = runs.pipeline("ward.round", "check", "for-a-machine", List.of());
        Run owner = runs.claim(runs.pipeline("ward.round", "check", "taken", List.of()), worker,
                now.plusSeconds(600)).orElseThrow();
        Run person = runs.released(runs.pipeline("ward.round", "check", "for-a-person",
                List.of()), "nobody said this would pass", Failure.UNKNOWN);
        Run nothing = runs.closed(runs.pipeline("ward.round", "check", "done", List.of()));

        assertEquals(Awaits.MACHINE, machine.awaits(now));
        assertEquals(Awaits.OWNER, owner.awaits(now));
        assertEquals(Awaits.PERSON, person.awaits(now));
        assertEquals(Awaits.NOTHING, nothing.awaits(now));
        for (Awaits who : Awaits.values()) {
            assertEquals(List.of(switch (who) {
                case MACHINE -> machine.key();
                case OWNER -> owner.key();
                case PERSON -> person.key();
                case NOTHING -> nothing.key();
            }), runs.awaiting(who).stream().map(Run::key).toList(),
                    "the list of what awaits " + who.wire() + " holds something else");
        }
    }
}
