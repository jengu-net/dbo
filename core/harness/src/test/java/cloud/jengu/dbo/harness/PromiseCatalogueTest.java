package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promise.Proofs;
import cloud.jengu.dbo.promise.PromiseStatus;
import cloud.jengu.dbo.promise.Registry;
import cloud.jengu.dbo.promise.Report;
import cloud.jengu.dbo.promises.DboAreas;
import cloud.jengu.dbo.promises.DboFeatures;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilot carrying real weight (#140): the store's own catalogue is
 * registered by its compilation, the citations in this module's tests are
 * indexed by theirs, and the composed model reads both — the first time the
 * whole pipeline runs outside its own fixtures.
 */
class PromiseCatalogueTest {

    private static Registry.Model model() {
        ClassLoader loader = PromiseCatalogueTest.class.getClassLoader();
        return Registry.load(loader).model(Proofs.load(loader));
    }

    @Test
    @DisplayName("the built classpath registers the catalogue — compile-time registration, "
            + "outside the fixture harness for the first time")
    @Proving(DboPromises.PRM_REGISTERED_AT_COMPILE_TIME)
    void catalogueIsRegistered() {
        assertTrue(Registry.load(PromiseCatalogueTest.class.getClassLoader()).catalogues()
                        .contains(DboPromises.class),
                "the annotation processor registered the catalogue during its own build");
    }

    @Test
    @DisplayName("the SHAPE promises are PROVEN by the tests that actually prove them")
    @Proving({DboPromises.PRM_PROOFS_INDEXED_AT_COMPILE_TIME, DboPromises.PRM_STATUS_IS_DERIVED})
    void shapePromisesAreProven() {
        Registry.Model model = model();
        assertEquals(PromiseStatus.PROVEN,
                model.statusOf(DboPromises.SHAPE_WRITTEN_UNDER_STAMPED));
        assertTrue(model.citing(DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP)
                        .contains("cloud.jengu.dbo.harness.ShapeStampIT#stampRidesTheWire"),
                "the citation names the real proof site");
    }

    /**
     * The gap this feature declared when the catalogue was first written —
     * what happens to stock stamped under a version the pack withdraws —
     * was answered by #133 and promoted to a named promise. What the fold
     * shows now is the point of the mechanism: unstated ground was visible
     * until somebody stated it, and then it stopped being a gap.
     */
    @Test
    @DisplayName("SHAPE_VERSIONING is fully proven — its declared gap was stated and promoted")
    @Proving(DboPromises.PRM_COVERAGE_IS_A_FOLD)
    void theGapWasPromoted() {
        Map<PromiseStatus, Long> coverage = model().coverage(DboFeatures.SHAPE_VERSIONING);
        assertEquals(0L, coverage.getOrDefault(PromiseStatus.GAP, 0L),
                "the gap became REQ-DBO-SHAPE-STAMP-OUTLIVES-ITS-PACK: " + coverage);
        assertEquals(PromiseStatus.PROVEN,
                model().statusOf(DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK));
        assertEquals(0L, coverage.getOrDefault(PromiseStatus.PLANNED, 0L), coverage.toString());
    }

    @Test
    @DisplayName("the GDPR area folds its constraints; every PDI promise it covers is proven")
    @Proving(DboPromises.PRM_COVERAGE_IS_A_FOLD)
    void gdprFolds() {
        Map<PromiseStatus, Long> coverage = model().coverage(DboAreas.GDPR);
        assertEquals(0L, coverage.getOrDefault(PromiseStatus.PLANNED, 0L),
                "nothing under GDPR is merely planned: " + coverage);
        assertEquals(6L, coverage.get(PromiseStatus.PROVEN), coverage.toString());
    }

    /**
     * The ratchet is a unit test: the generated block in req-catalogue.md is
     * regenerated in memory on every build and compared whole — a hand-edit
     * and a projection gone stale after a catalogue change are the same
     * refusal (REQ-DBO-PRM-PROJECTION-IS-GENERATED).
     */
    @Test
    @DisplayName("the catalogue projection matches the model — regenerate, never hand-edit")
    @Proving({DboPromises.PRM_CATALOGUE_READ_WHOLE, DboPromises.PRM_PROJECTION_IS_GENERATED})
    void projectionIsCurrent() throws Exception {
        java.nio.file.Path catalogue = java.nio.file.Path.of("../..",
                "docs/arc42-006-runtime/req-catalogue.md").toAbsolutePath().normalize();
        String onDisk = java.nio.file.Files.readString(catalogue);
        assertEquals(PromiseProjection.projected(model(), onDisk), onDisk,
                "req-catalogue.md's generated block differs from the model — run "
                        + "./gradlew :core:harness:promiseProjection instead of editing");
    }

    @Test
    @DisplayName("the rendered report carries the areas, the codes and the statuses")
    @Proving(DboPromises.PRM_NAME_IS_THE_CODE)
    void reportRenders() {
        String report = Report.render(model());
        assertTrue(report.contains("AREA-GDPR"), report.substring(0, Math.min(600, report.length())));
        assertTrue(report.contains("REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED | PROVEN"), report);
        assertTrue(report.contains("REQ-DBO-SHAPE-STAMP-OUTLIVES-ITS-PACK | PROVEN"),
                "the promoted gap now reads as a proven promise: " + report);
    }
}
