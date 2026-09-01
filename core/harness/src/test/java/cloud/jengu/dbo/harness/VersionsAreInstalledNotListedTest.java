package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which faces a container serves is what is installed, not a list two strings
 * long (R6, REQ-DBO-VER-CONCURRENT-VERSIONS).
 *
 * <p>A tenant spec used to be refused unless its version was exactly {@code r4}
 * or {@code r5} — so R6 was rejected by the name of the requirement asking for
 * it, and a face for a domain with no clinical vocabulary at all could not be
 * declared. A face announces itself now, and the question at bring-up is
 * whether anything is registered under the code.
 */
class VersionsAreInstalledNotListedTest {

    @Test
    @DisplayName("a face announces itself, so the versions are the ones installed")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void facesAnnounceThemselves() {
        Set<String> codes = FhirVersions.installed().codes();

        assertTrue(codes.containsAll(Set.of("r4", "r5")),
                "the installed faces are not announcing themselves: " + codes);
    }

    @Test
    @DisplayName("and each of them can build one tenant's runtime pieces")
    void aVersionBuildsWhatABringUpNeeds() {
        FhirVersion r4 = FhirVersions.installed().require("r4");
        FhirVersion.ForTypes declared = r4.forTypes(List.of(
                new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER, Set.of("urn:t"),
                        Handling.operational())));

        assertEquals("r4", r4.domain());
        assertEquals("4.0", r4.payloadVersion());
        assertNotNull(r4.face().capability(cloud.jengu.dbo.core.face.Payloads.class).orElse(null),
                "a version that cannot read a payload is not a face");
        assertEquals(1, declared.registrations().size());
        assertNotNull(declared.portableRendering());
    }

    @Test
    @DisplayName("a version nothing provides is refused, and told what would have worked")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void anUninstalledVersionIsRefusedByName() {
        FhirVersions versions = FhirVersions.of(FhirVersions.installed().require("r4"));

        FhirVersions.UnknownVersion refusal = assertThrows(FhirVersions.UnknownVersion.class,
                () -> versions.require("r6"));

        assertTrue(refusal.getMessage().contains("r6"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("r4"),
                "a refusal that does not say what is installed cannot be acted on: "
                        + refusal.getMessage());
    }

    @Test
    @DisplayName("two faces under one code is a mistake, not a merge")
    void oneCodeIsOneFace() {
        FhirVersion r4 = FhirVersions.installed().require("r4");

        assertThrows(IllegalArgumentException.class, () -> FhirVersions.of(r4, r4));
    }

    @Test
    @DisplayName("a spec still naming the field after a FHIR version is refused, "
            + "because two names for one field is what survives a decade")
    void theOldNameIsRefusedRatherThanHonoured() {
        // Transitional and deliberately not a catalogue promise: once nothing
        // writes the old name the refusal is vestigial. What it must never be
        // is silence — a spec quietly coming up with no face declared, or two
        // spellings both working and no way to tell which one a deployment
        // obeys.
        String old = """
                {"code":"vana","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""";

        IllegalArgumentException refusal =
                assertThrows(IllegalArgumentException.class, () -> TenantSpec.parse(old));

        // Both names, and the old one is what makes this assertion mean
        // anything: drop the refusal and the parse still fails, with "missing
        // field: face" — which satisfies a check for the new name alone while
        // telling a spec author nothing about what they actually did wrong.
        assertTrue(refusal.getMessage().contains("fhirVersion")
                        && refusal.getMessage().contains("face"),
                "the refusal has to name what was written and what to write: "
                        + refusal.getMessage());
    }

    @Test
    @DisplayName("and a spec is no longer the place the version list lives")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void aSpecAcceptsAVersionItCannotKnowAbout() {
        // The spec's job is that a version is named; whether anything serves it
        // is answered where the answer is known.
        TenantSpec spec = new TenantSpec("uus", "r6", List.of(
                new FhirTypeConfig("Patient", IdentityClass.INTERNAL, Set.of(),
                        Handling.operational())));

        assertEquals("r6", spec.face());
        assertThrows(IllegalArgumentException.class, () -> new TenantSpec("uus", "  ", List.of(
                new FhirTypeConfig("Patient", IdentityClass.INTERNAL, Set.of(),
                        Handling.operational()))));
    }
}
