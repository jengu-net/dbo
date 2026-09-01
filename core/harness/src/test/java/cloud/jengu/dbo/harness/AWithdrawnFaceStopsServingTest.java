package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.fhir.common.WatchedVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A face taken from the registry stops working when the bundle providing it
 * goes.
 *
 * <p>The failure without this is the quiet kind: a bundle is uninstalled and
 * every tenant carries on being served by it, because the classes are loaded
 * and the object is reachable. Nothing errors. The store is simply answering
 * from something nobody can see, and the next restart changes behaviour for
 * reasons nobody can reconstruct.
 *
 * <p>Off unless asked for, so this is what the container does when told to
 * watch — not what it does normally.
 */
class AWithdrawnFaceStopsServingTest {

    @Test
    @DisplayName("a face used after its provider went says where it was taken")
    void aWithdrawnFaceRefusesAndSaysWhereItCameFrom() {
        WatchedVersions watched = new WatchedVersions(FhirVersions.installed());
        FhirVersion held = watched.require("r4");
        assertEquals("r4", held.code(), "a face that is installed answers as itself");

        watched.withdrawn("r4");

        WatchedVersions.Withdrawn refusal =
                assertThrows(WatchedVersions.Withdrawn.class, held::payloadVersion);
        assertTrue(refusal.getMessage().contains("r4"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("AWithdrawnFaceStopsServingTest"),
                "the refusal must say where the face was taken, since nothing can "
                        + "reconstruct that afterwards: " + refusal.getMessage());
    }

    @Test
    @DisplayName("and a face reinstalled under the same code is a new one, not the old one back")
    void aReinstalledFaceIsANewFace() {
        WatchedVersions watched = new WatchedVersions(FhirVersions.installed());
        FhirVersion held = watched.require("r4");
        watched.withdrawn("r4");

        FhirVersion taken = watched.require("r4");

        assertEquals("r4", taken.code(), "what is installed now serves now");
        assertThrows(WatchedVersions.Withdrawn.class, held::code,
                "the one taken before is still withdrawn — a caller holding it was "
                        + "holding the bundle that went, not the code");
    }

    @Test
    @DisplayName("the diagnostic says nothing about what was being read")
    void theDiagnosticNamesNoData() {
        WatchedVersions watched = new WatchedVersions(FhirVersions.installed());
        FhirVersion held = watched.require("r4");
        watched.withdrawn("r4");

        String message = assertThrows(WatchedVersions.Withdrawn.class, held::domain).getMessage();

        // §14: a search carries identifier=system|value, and a diagnostic that
        // quoted the request would put in a log what the payload encrypts.
        assertTrue(message.contains("face") && message.contains("bundle"), message);
        assertTrue(!message.contains("identifier") && !message.contains("Patient"),
                "a diagnostic must not name what was being read: " + message);
    }
}
