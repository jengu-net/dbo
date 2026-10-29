package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A participant at a busy place, whose work feed is mostly the store's own.
 *
 * <p>A poll read fifty events of the work feed and kept the runs for its
 * steps. At a place where the feed carried millions of the sync stream's runs,
 * the participant's cursor was eight million events behind, and a run started
 * there now would have been reached in about eight days.
 */
class AParticipantBehindItsFeedStillFindsItsWorkTest {

    private static final String STEP_ID = "fleet.appliance.report";
    private static final String STEP = "report";

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());
    private final Executor ward = new Executor("ward-1", "1", "hogwarts", Scope.BASELINE);

    @Test
    @DisplayName("a run started behind thousands of the store's own runs is offered on the next "
            + "poll, and what is started after it is read from the feed")
    @Proving(DboPromises.PROC_A_PARTICIPANT_BEHIND_ITS_FEED_STILL_FINDS_ITS_WORK)
    void aRunBehindTheStoresOwnWorkIsFound() {
        Participation participation = new Participation(runs, held.feed(), ward.name(),
                Set.of(STEP), ward, ward.name());
        for (int round = 0; round < 3_000; round++) {
            runs.pipeline("dbo.sync.stream", "apply");
        }
        Run started = started();

        assertEquals(List.of(started.key()), keys(participation.poll(50)),
                "a run behind the store's own work was not offered on the next poll");
        assertEquals(0L, participation.lag(), "having found its work, the participant is still "
                + "behind the events that would have told it");

        Run next = started();
        assertEquals(List.of(next.key()), keys(participation.poll(50)),
                "the feed did not carry what was started after the participant caught up");
    }

    private Run started() {
        return runs.filling(StepDeclaration.of(STEP_ID, "1", WorkModel.DOMAIN), RunKind.PIPELINE,
                cloud.jengu.dbo.core.UuidV7.newId(), Map.of(), null, null, null, null);
    }

    private static List<String> keys(List<Run> offered) {
        return offered.stream().map(Run::key).toList();
    }
}
