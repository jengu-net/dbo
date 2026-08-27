package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine depends on no face, asserted from the bundle manifest
 * rather than by reading code.
 *
 * <p>The inward contract lives in {@code cloud.jengu.dbo.core.face} — what a
 * face <b>implements</b> — beside {@code core.api}, which is what a face
 * <b>calls</b>. Two directions, two packages, so the direction is visible on
 * the import line rather than inferred from context.
 *
 * <p>bnd computes {@code Import-Package} from bytecode, so a dependency cannot
 * be present in the code and absent from the manifest. That makes "the engine
 * knows nothing about FHIR" a fact this test can read, instead of a rule a
 * reviewer has to notice being broken — which is how a coarsening function
 * that knew about FHIR dates ended up inside the engine in the first place.
 */
class EngineKnowsNoFaceIT {

    @Test
    @DisplayName("the engine bundle imports nothing from any face")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void theEngineImportsNoFacePackage() throws Exception {
        List<String> imports = importPackagesOf("cloud.jengu.dbo.core");

        // Not "or empty": an absent Import-Package would make the real
        // assertion below pass by finding nothing.
        assertTrue(imports.stream().anyMatch(i -> i.startsWith("java.")),
                "the manifest must have been read and carry real imports, or the check below "
                        + "proves nothing: " + imports);

        List<String> faceImports = imports.stream()
                .filter(i -> i.startsWith("cloud.jengu.dbo.fhir")
                        || i.startsWith("org.hl7.fhir")
                        || i.startsWith("ca.uhn.fhir"))
                .toList();

        assertEquals(List.of(), faceImports,
                "the engine imported a face package — its concepts are meant to be statable "
                        + "without naming a domain, and this is where that stops being true");
    }

    @Test
    @DisplayName("the participant bundle names no orchestrator, so the same one runs on an edge")
    void theParticipantNamesNoOrchestrator() throws Exception {
        List<String> imports = importPackagesOf("cloud.jengu.dbo.work");

        assertTrue(imports.stream().anyMatch(i -> i.startsWith("java.")),
                "the manifest must have been read and carry real imports: " + imports);
        assertEquals(List.of(), imports.stream()
                        .filter(i -> i.startsWith("dev.dbos") || i.startsWith("org.hl7.fhir")
                                || i.startsWith("ca.uhn.fhir") || i.startsWith("cloud.jengu.dbo.fhir"))
                        .toList(),
                "a participant that imported an orchestrator would not be the same binary on "
                        + "an edge that has none, and #46 requires a step to be runnable by a "
                        + "DBOS workflow, by a synchronous call, or by neither");
    }

    /** Whether this header exports exactly that package, rather than mentioning it. */
    private static boolean exports(String header, String packageName) {
        for (String clause : header.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            if (clause.split(";")[0].trim().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** The package a bundle exports, for the marker this test asked about. */
    private static String exportedPackage(String markerPackage) {
        return markerPackage.endsWith(".core") ? markerPackage + ".face" : markerPackage;
    }

    /** Reads Import-Package from the manifest of the bundle exporting a package. */
    private static List<String> importPackagesOf(String markerPackage) throws Exception {
        String marker = markerPackage.replace('.', '/') + "/UuidV7.class";
        List<String> found = new ArrayList<>();
        for (URL url : Collections.list(
                EngineKnowsNoFaceIT.class.getClassLoader().getResources("META-INF/MANIFEST.MF"))) {
            try (InputStream in = url.openStream()) {
                Manifest manifest = new Manifest(in);
                String exported = manifest.getMainAttributes().getValue("Export-Package");
                // The package must be EXPORTED, not merely mentioned: bnd names
                // it in the uses: clause of every bundle that passes one of
                // these types around, so a substring match picks whichever of
                // them the classpath happens to put first — and then this test
                // reports that bundle's imports as the engine's.
                if (exported == null || !exports(exported, exportedPackage(markerPackage))) {
                    continue;
                }
                String imported = manifest.getMainAttributes().getValue("Import-Package");
                if (imported == null) {
                    return List.of();
                }
                // split on commas that are not inside an attribute's quotes
                for (String entry : imported.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                    found.add(entry.split(";")[0].trim());
                }
                return found;
            }
        }
        throw new IllegalStateException("no bundle manifest exports " + markerPackage
                + ".face — the engine bundle was not on the test classpath, so this test "
                + "would have passed by finding nothing (marker: " + marker + ")");
    }
}
