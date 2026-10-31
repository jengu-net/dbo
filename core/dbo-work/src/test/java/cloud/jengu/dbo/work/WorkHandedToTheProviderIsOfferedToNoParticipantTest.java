package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("the provider's person holding the work lets it go, and it stays handed for "
            + "another of the provider's people; nobody else lets it go for them")
    @Proving(DboPromises.PROC_A_PERSON_OF_THE_PROVIDER_ACTS_ON_WORK_THEY_HOLD)
    void theHolderLetsItGo() {
        String ada = "https://mom.example/t/mom/fhir/Practitioner/ada";
        Run handed = runs.opening(INCIDENTS, "incident", "CommunicationRequest/5",
                "Practitioner/admin", null, "mom");
        Run held = runs.takenForTheProvider(handed, "mom",
                "https://mom.example/t/mom/fhir/PractitionerRole/support", ada).orElseThrow();

        assertThrows(IllegalStateException.class,
                () -> runs.letGo(held, "https://mom.example/t/mom/fhir/Practitioner/grace",
                        "not mine"),
                "somebody who does not hold the work let it go");
        assertThrows(IllegalArgumentException.class, () -> runs.letGo(held, ada, null),
                "the work was let go without saying why");
        Run released = runs.letGo(held, ada, "needs a part we do not stock");
        assertEquals(Run.Addressee.provider("mom"), released.addressee(),
                "work let go is no longer handed to the provider");
        assertEquals(handed.assignment(), released.assignment(),
                "work let go is still held");
        assertFalse(released.heldBy(ada, java.time.Instant.now()),
                "the person who let the work go still holds it");
        assertEquals(true, runs.takenForTheProvider(released, "mom",
                "https://mom.example/t/mom/fhir/PractitionerRole/support",
                "https://mom.example/t/mom/fhir/Practitioner/grace").isPresent(),
                "another of the provider's people could not take work let go");
    }

    @Test
    @DisplayName("the holder joins a colleague to handed work and takes them off, the tenant "
            + "takes anybody off, and taking the work back clears whoever joined it")
    @Proving(DboPromises.PROC_THE_PROVIDERS_PEOPLE_JOIN_HANDED_WORK)
    void colleaguesJoinAndLeave() {
        String ada = "https://mom.example/t/mom/fhir/Practitioner/ada";
        String grace = "https://mom.example/t/mom/fhir/Practitioner/grace";
        String alan = "https://mom.example/t/mom/fhir/Practitioner/alan";
        Run handed = runs.opening(INCIDENTS, "incident", "CommunicationRequest/6",
                "Practitioner/admin", null, "mom");
        Run held = runs.takenForTheProvider(handed, "mom",
                "https://mom.example/t/mom/fhir/PractitionerRole/support", ada).orElseThrow();

        Run joined = runs.joined(runs.joined(held, grace, ada), alan, ada);
        assertEquals(List.of(grace, alan), joined.addressee().joined(),
                "the colleagues the holder named are not on the work");
        assertEquals(joined.addressee(), runs.byKey(joined.key()).orElseThrow().addressee(),
                "who joined the work did not survive being read back");
        assertTrue(runs.items(joined).stream().anyMatch(act -> "joined".equals(act.step())),
                "the work's account does not say somebody joined it");
        assertThrows(IllegalStateException.class, () -> runs.joined(joined, grace, ada),
                "a colleague was joined twice");
        assertThrows(IllegalStateException.class, () -> runs.joined(joined, ada, ada),
                "the holder joined the work they hold");

        Run without = runs.left(joined, alan, "Practitioner/admin");
        assertEquals(List.of(grace), without.addressee().joined(),
                "the tenant could not take a colleague off its work");
        assertThrows(IllegalStateException.class, () -> runs.left(without, alan, ada),
                "somebody not joined was taken off");
        Run back = runs.takenBack(without, "Practitioner/admin", "our own technician");
        assertNull(back.addressee(), "work taken back still names who joined it");

        Run kept = runs.opening(INCIDENTS, "incident", "CommunicationRequest/7",
                "Practitioner/admin", null);
        assertThrows(IllegalStateException.class, () -> runs.joined(kept, grace, ada),
                "somebody of the provider joined work the tenant kept");
    }

    @Test
    @DisplayName("what is written under open work is kept among what it produced, said under "
            + "it naming who wrote, and leaves the work where it stood")
    @Proving(DboPromises.PROC_WORK_THAT_LASTS_IS_WRITTEN_IN_WHILE_OPEN)
    void writtenUnderOpenWork() {
        String grace = "https://mom.example/t/mom/fhir/Practitioner/grace";
        Run open = runs.opening(INCIDENTS, "incident", "CommunicationRequest/8",
                "Practitioner/admin", null, "mom");
        Run written = runs.written(open, List.of("Basic/1/1"), grace);
        assertEquals(List.of("Basic/1/1"), written.produced().versions(),
                "what was written is not among what the work produced");
        assertEquals(open.status(), written.status(), "writing moved the work");
        assertTrue(runs.items(written).stream().anyMatch(act -> "written".equals(act.step())
                        && act.assignment() != null && grace.equals(act.assignment().claimant())),
                "the work's account does not say who wrote under it");
        Run over = runs.ended(written, Status.COMPLETED, "Practitioner/admin", "fixed");
        assertThrows(IllegalStateException.class,
                () -> runs.written(over, List.of("Basic/2/1"), grace),
                "something was written under work that is over");
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
