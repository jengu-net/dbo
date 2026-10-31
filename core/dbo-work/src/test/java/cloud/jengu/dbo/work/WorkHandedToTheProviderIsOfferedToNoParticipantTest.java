package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A clinic hands an incident to its service provider, and takes it back.
 *
 * <p>Over the work domain's own records, in a map: whether a register row
 * lets the clinic hand the work over is the door's question, and what is
 * decided here is what the work says once it is handed and once it is not.
 */
class WorkHandedToTheProviderIsOfferedToNoParticipantTest {

    private static final StepDeclaration INCIDENTS =
            StepDeclaration.of("care.incidents.work", "1", WorkModel.DOMAIN)
                    .taking("incident", "CommunicationRequest");

    private final Runs runs = new Runs(new WorkInMemory().store());

    @Test
    @DisplayName("work handed to the provider as it is opened names the provider and no "
            + "participant, and handing it again is refused")
    @Proving(DboPromises.PROC_LASTING_WORK_IS_HANDED_TO_THE_PROVIDER_AND_TAKEN_BACK)
    void handedAsItIsOpened() {
        Run handed = runs.opening(INCIDENTS, "incident", "CommunicationRequest/1",
                "Practitioner/admin", null, "mom");

        assertEquals(Run.Addressee.provider("mom"), handed.addressee(),
                "work opened for the provider does not name it");
        assertEquals(Status.RECEIVED, handed.status());
        assertFalse(handed.forParticipant("ward-1", "ward-1"),
                "work handed to the provider was a participant's to take");
        assertThrows(IllegalStateException.class,
                () -> runs.handed(handed, "mom", "Practitioner/admin", "again"),
                "work already handed was handed again");
    }

    @Test
    @DisplayName("work kept is handed with a reason and taken back with one, and taken back it "
            + "is the tenant's own again as it was opened")
    @Proving(DboPromises.PROC_LASTING_WORK_IS_HANDED_TO_THE_PROVIDER_AND_TAKEN_BACK)
    void handedAndTakenBack() {
        Run kept = runs.opening(INCIDENTS, "incident", "CommunicationRequest/2",
                "Practitioner/admin", null);
        assertNull(kept.addressee(), "work kept named somebody it was for");
        assertThrows(IllegalArgumentException.class,
                () -> runs.handed(kept, "mom", "Practitioner/admin", null),
                "work was handed over without saying why");

        Run handed = runs.handed(kept, "mom", "Practitioner/admin", "the device is theirs");
        assertEquals(Run.Addressee.provider("mom"), handed.addressee());

        assertThrows(IllegalArgumentException.class,
                () -> runs.takenBack(handed, "Practitioner/admin", " "),
                "work was taken back without saying why");
        Run back = runs.takenBack(handed, "Practitioner/admin", "we will do it ourselves");
        assertNull(back.addressee(), "work taken back still names the provider");
        assertEquals(kept.assignment(), back.assignment(),
                "work taken back is not as it was opened");
        assertThrows(IllegalStateException.class,
                () -> runs.takenBack(back, "Practitioner/admin", "twice"),
                "work nobody was handed was taken back");
    }

    @Test
    @DisplayName("work that is over is neither handed nor taken back, and a run that does not "
            + "last is never handed")
    @Proving(DboPromises.PROC_LASTING_WORK_IS_HANDED_TO_THE_PROVIDER_AND_TAKEN_BACK)
    void overOrNotLastingIsNotHanded() {
        Run handed = runs.opening(INCIDENTS, "incident", "CommunicationRequest/3",
                "Practitioner/admin", null, "mom");
        Run ended = runs.ended(handed, Status.CANCELLED, "Practitioner/admin", "a duplicate");
        assertThrows(IllegalStateException.class,
                () -> runs.takenBack(ended, "Practitioner/admin", "too late"),
                "work that is over was taken back");

        Run kept = runs.opening(INCIDENTS, "incident", "CommunicationRequest/4",
                "Practitioner/admin", null);
        Run over = runs.ended(kept, Status.COMPLETED, "Practitioner/admin", "fixed");
        assertThrows(IllegalStateException.class,
                () -> runs.handed(over, "mom", "Practitioner/admin", "too late"),
                "work that is over was handed over");

        Run performed = runs.filling(
                StepDeclaration.of("care.incidents.triage", "1", WorkModel.DOMAIN),
                RunKind.PIPELINE,
                cloud.jengu.dbo.core.UuidV7.newId(), java.util.Map.of(), null, null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> runs.handed(performed, "mom", "Practitioner/admin", "theirs"),
                "a run that does not last was handed to the provider");
    }
}
