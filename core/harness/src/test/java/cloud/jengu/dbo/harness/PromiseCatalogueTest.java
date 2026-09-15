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
 * The pilot carrying real weight: the store's own catalogue is
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
     * A promise travels as a sentence, and the sentence has to be honest on
     * its own.
     *
     * <p>Status is derived and correct and lives in a catalogue. That did not
     * help: promises are quoted into design documents and read aloud in
     * meetings, and the catalogue stays behind. An unbuilt one written in the
     * present indicative — <i>binary content LIVES in per-tenant blob
     * storage</i> — is indistinguishable from one that is kept, and a
     * consumer rejected two alternative designs on the strength of exactly
     * that sentence describing a capability that did not exist.
     *
     * <p>So the prose carries its own status. Crude on purpose: a rule that
     * asked whether a sentence READS as intent would be a judgement nobody
     * can enforce, and this is a prefix that survives being copied somewhere
     * the catalogue is not.
     *
     * <p>It holds both ways. A promise that gains its first citation stops
     * being planned, and the build then refuses the word until somebody takes
     * it out — which is the moment to check that the sentence became true
     * rather than merely cited.
     */
    @Test
    @DisplayName("a promise nothing proves says it is planned, in its own words, and one "
            + "that is proven does not")
    @Proving(DboPromises.PRM_NAME_IS_THE_CODE)
    void whatIsNotBuiltReadsAsIntent() {
        Registry.Model model = model();
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (DboPromises promise : DboPromises.values()) {
            boolean planned = model.statusOf(promise) == PromiseStatus.PLANNED;
            boolean saysSo = promise.text().startsWith("Planned — ");
            if (planned && !saysSo) {
                wrong.add(promise.name() + ": nothing proves it and it reads as a description "
                        + "of what this store does");
            }
            if (!planned && saysSo) {
                wrong.add(promise.name() + ": something proves it and it still says planned — "
                        + "take the word out, once the sentence is true rather than cited");
            }
        }
        assertTrue(wrong.isEmpty(), String.join(System.lineSeparator(), wrong));
    }

    /**
     * The gap this feature declared when the catalogue was first written —
     * what happens to stock stamped under a version the pack withdraws —
     * was answered and promoted to a named promise. What the fold
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
        // The stories' joins are projected the same way, and go stale the
        // same way: a promise renamed, retired or newly proven changes what
        // a story may say about itself.
        java.nio.file.Path stories = java.nio.file.Path.of("../..",
                "docs/arc42-003-context/user-stories").toAbsolutePath().normalize();
        for (cloud.jengu.dbo.promise.Story story : PromiseProjection.stories(model())) {
            java.nio.file.Path file = PromiseProjection.storyFile(stories, model(), story);
            String page = java.nio.file.Files.readString(file);
            assertEquals(PromiseProjection.projectedStory(model(), story, page), page,
                    file.getFileName() + "'s joins differ from the catalogue — run "
                            + "./gradlew :core:harness:promiseProjection instead of editing");
        }
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
