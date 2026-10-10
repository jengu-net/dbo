package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two appliances hold one step — report your state — and a run of it is for
 * one of them: the other may hold the step, poll first and claim, and never
 * takes it.
 *
 * <p>Over the work domain's own records and feed, in a map: a poll reads the
 * feed and the run each event names, and a claim is the conditional write a
 * tenant makes, so what is decided here is decided on the record.
 */
class ARunForOneParticipantIsTakenByThatOneAloneTest {

    private static final String STEP_ID = "fleet.appliance.report";
    private static final String STEP = "report";

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());
    private final Executor wardOne = new Executor("ward-1", "1", "hogwarts", Scope.BASELINE);
    private final Executor wardTwo = new Executor("ward-2", "1", "hogwarts", Scope.BASELINE);

    @Test
    @DisplayName("a run for one appliance is offered to it alone, and the other's claim is "
            + "refused")
    @Proving(DboPromises.PROC_A_RUN_NAMES_WHO_MAY_TAKE_IT)
    void aRunForOneIsOfferedToItAlone() {
        Run run = started(new Run.Addressee("ward-1", "ward-1"));

        assertEquals(List.of(), keys(participation(wardTwo).poll(50)),
                "the other appliance was offered a run that is not for it");
        assertThrows(Runs.NotForThisParticipant.class,
                () -> runs.claim(run, wardTwo, Duration.ofMinutes(5), "ward-2"),
                "the other appliance claimed a run that is not for it");
        assertEquals(List.of(run.key()), keys(participation(wardOne).poll(50)),
                "the appliance the run is for was not offered it");
        assertTrue(participation(wardOne).claim(run, Duration.ofMinutes(5)).isPresent(),
                "the appliance the run is for could not claim it");
    }

    @Test
    @DisplayName("a run for an appliance that is away waits for it, through a release, and "
            + "the other never takes it meanwhile")
    @Proving(DboPromises.PROC_A_RUN_NAMES_WHO_MAY_TAKE_IT)
    void aRunWaitsForTheOneItIsFor() {
        Run run = started(new Run.Addressee("ward-1", null));
        assertEquals(List.of(), keys(participation(wardTwo).poll(50)),
                "the other appliance was offered a run waiting for one that is away");

        assertEquals(List.of(run.key()), keys(participation(wardOne).poll(50)),
                "the appliance came back and was not offered the run waiting for it");
        Run taken = participation(wardOne).claim(run, Duration.ofMinutes(5)).orElseThrow();
        Run released = runs.released(taken, "the appliance restarted");

        assertEquals(Status.READY, released.status(), "the release did not return the run");
        assertThrows(Runs.NotForThisParticipant.class,
                () -> runs.claim(released, wardTwo, Duration.ofMinutes(5), "ward-2"),
                "a released run forgot who it is for");
    }

    @Test
    @DisplayName("a run naming nobody is offered to every appliance holding its step, and the "
            + "first claim wins")
    @Proving(DboPromises.PROC_A_RUN_NAMES_WHO_MAY_TAKE_IT)
    void aRunForNobodyIsAnybodys() {
        Run run = started(null);

        assertEquals(List.of(run.key()), keys(participation(wardTwo).poll(50)));
        assertEquals(List.of(run.key()), keys(participation(wardOne).poll(50)));
        assertTrue(participation(wardTwo).claim(run, Duration.ofMinutes(5)).isPresent(),
                "the first claim on a run naming nobody did not land");
        assertTrue(participation(wardOne).claim(run, Duration.ofMinutes(5)).isEmpty(),
                "a second claim took a run somebody holds");
    }

    private Run started(Run.Addressee addressee) {
        return runs.filling(StepDeclaration.of(STEP_ID, "1", WorkModel.DOMAIN), RunKind.PIPELINE,
                cloud.jengu.dbo.core.UuidV7.newId(), Map.of(), null, null, null, addressee);
    }

    /** Each appliance on its own cursor, under its own credential: client and executor alike. */
    private Participation participation(Executor appliance) {
        return new Participation(runs, held.feed(), appliance.name(), Set.of(STEP), appliance,
                appliance.name());
    }

    private static List<String> keys(List<Run> offered) {
        return offered.stream().map(Run::key).toList();
    }
}
