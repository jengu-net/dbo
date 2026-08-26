package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promise.Proofs;
import cloud.jengu.dbo.promise.PromiseStatus;
import cloud.jengu.dbo.promise.Registry;
import cloud.jengu.dbo.promise.Report;
import cloud.jengu.dbo.promises.DboAreas;
import cloud.jengu.dbo.promises.DboFeatures;
import cloud.jengu.dbo.promises.DboPromises;
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
    void catalogueIsRegistered() {
        assertTrue(Registry.load(PromiseCatalogueTest.class.getClassLoader()).catalogues()
                        .contains(DboPromises.class),
                "the annotation processor registered the catalogue during its own build");
    }

    @Test
    @DisplayName("the SHAPE promises are PROVEN by the tests that actually prove them")
    void shapePromisesAreProven() {
        Registry.Model model = model();
        assertEquals(PromiseStatus.PROVEN,
                model.statusOf(DboPromises.SHAPE_WRITTEN_UNDER_STAMPED));
        assertTrue(model.citing(DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP)
                        .contains("cloud.jengu.dbo.harness.ShapeStampIT#stampRidesTheWire"),
                "the citation names the real proof site");
    }

    @Test
    @DisplayName("the gap counts against SHAPE_VERSIONING's coverage until somebody states it")
    void theGapCounts() {
        Map<PromiseStatus, Long> coverage = model().coverage(DboFeatures.SHAPE_VERSIONING);
        assertEquals(7L, coverage.get(PromiseStatus.PROVEN));
        assertEquals(1L, coverage.get(PromiseStatus.GAP));
    }

    @Test
    @DisplayName("the GDPR area folds its constraints; every PDI promise it covers is proven")
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
    void projectionIsCurrent() throws Exception {
        java.nio.file.Path catalogue = java.nio.file.Path.of("../..",
                "docs/arc42-006-runtime/req-catalogue.md").toAbsolutePath().normalize();
        String onDisk = java.nio.file.Files.readString(catalogue);
        assertEquals(PromiseProjection.projected(model(), onDisk), onDisk,
                "req-catalogue.md's generated block differs from the model — run "
                        + "./gradlew :core:harness:promiseProjection instead of editing");
    }

    @Test
    @DisplayName("the rendered report carries the areas, the codes and the gap, verbatim")
    void reportRenders() {
        String report = Report.render(model());
        assertTrue(report.contains("AREA-GDPR"), report.substring(0, Math.min(600, report.length())));
        assertTrue(report.contains("REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED | PROVEN"), report);
        assertTrue(report.contains("nobody has stated what happens when a pack withdraws"),
                report);
    }
}
