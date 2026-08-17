package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Coarsening;
import cloud.jengu.dbo.core.api.DeclaredFace;
import cloud.jengu.dbo.core.api.DomainFace;
import cloud.jengu.dbo.fhir.common.FhirFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#38: what the engine requires from a face is a named contract, declared
 * rather than assumed.
 *
 * <p>The property that matters most here is the last one: a capability the
 * engine learns about later must not break a face that predates it. Faces move
 * independently — R4 and R5 already run side by side — so a contract that
 * broke every implementation when it grew would fail at exactly the moment a
 * new FHIR version arrives, which is what faces exist for.
 */
class DomainFaceIT {

    /** A capability nobody had heard of when the FHIR face was written. */
    interface SomethingNewer {
        String describe();
    }

    @Test
    @DisplayName("#38: a face provides what it declared, and the engine can use it")
    void aFaceProvidesWhatItDeclared() {
        DomainFace face = FhirFace.of("r4");

        Coarsening coarsening = face.require(Coarsening.class);

        assertEquals("1980", coarsening.coarsen("Patient", "birthDate", "1980-03-15"));
        assertTrue(face.capabilities().contains(Coarsening.class));
    }

    @Test
    @DisplayName("#38: asking for something a face never declared is refused, and named")
    void anUndeclaredCapabilityIsRefused() {
        DomainFace face = FhirFace.of("r4");

        assertFalse(face.capability(SomethingNewer.class).isPresent(),
                "not providing a capability is an ordinary answer, not a failure");

        DomainFace.CapabilityMissingException refused = assertThrows(
                DomainFace.CapabilityMissingException.class,
                () -> face.require(SomethingNewer.class));

        assertTrue(refused.getMessage().contains("fhir-r4"), refused.getMessage());
        assertTrue(refused.getMessage().contains("SomethingNewer"),
                "unsupported and misconfigured look identical from outside unless the refusal "
                        + "says which: " + refused.getMessage());
    }

    @Test
    @DisplayName("#38: a new capability does not break a face that predates it")
    void theContractGrowsWithoutBreakingFaces() {
        DomainFace older = FhirFace.of("r4");
        DomainFace newer = DeclaredFace.named("fhir-r5")
                .providing(Coarsening.class, older.require(Coarsening.class))
                .providing(SomethingNewer.class, () -> "a capability added later")
                .build();

        // the older face still works, knowing nothing of the newer capability
        assertEquals("1980", older.require(Coarsening.class)
                .coarsen("Patient", "birthDate", "1980-03-15"));
        assertFalse(older.capabilities().contains(SomethingNewer.class));

        assertEquals("a capability added later", newer.require(SomethingNewer.class).describe());
    }

    @Test
    @DisplayName("#38: declaring the same capability twice is a mistake, not a merge")
    void declaringTwiceIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DeclaredFace.named("confused")
                        .providing(Coarsening.class, Coarsening.NONE)
                        .providing(Coarsening.class, (t, e, v) -> "surprise")
                        .build());

        assertTrue(refused.getMessage().contains("twice"), refused.getMessage());
    }

    @Test
    @DisplayName("#38: a face that generalises nothing is a valid face")
    void afaceMayProvideNothing() {
        DomainFace bare = DeclaredFace.named("minimal").build();

        assertTrue(bare.capabilities().isEmpty());
        assertFalse(bare.capability(Coarsening.class).isPresent(),
                "a domain with no coarse forms declares none, rather than stubbing one that lies");
    }
}
