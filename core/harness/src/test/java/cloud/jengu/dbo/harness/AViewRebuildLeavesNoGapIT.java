package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ShapeTooNewException;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's validation view rebuilt while somebody reads.
 *
 * <p>A rebuild emptied the view and then built a new one, and an object newer
 * than the tenant's pack is refused by comparing its stamp with the view — so
 * a read landing in the gap found no view, compared nothing, and served the
 * object. The round that rebuilds a tenant's view whenever a profile arrives
 * runs on its own thread, so the gap was open to every reader, and a search
 * that should have been refused whole answered short.
 */
@Tag("integration")
class AViewRebuildLeavesNoGapIT {

    @Test
    @DisplayName("an object newer than the pack is refused on every read while the view is "
            + "being rebuilt")
    @Proving(DboPromises.SHAPE_NEWER_DATA_REFUSED)
    void aRebuildNeverServesWhatThePackDoesNotCover() throws Exception {
        SharedTenants.Tenant tenant = SharedTenants.of(SharedTenants.Shape.R4_RESHAPE);
        FhirStoreFacade store = tenant.store();
        String profile = "https://gap.dbo.test/StructureDefinition/" + UUID.randomUUID();
        String shape = store.create(basicShape(profile, "3.0.0")).id();
        String tooNew = store.create("""
                {"resourceType":"Basic","code":{"text":"note"},
                 "meta":{"profile":["%s"]}}""".formatted(profile)).id();
        // The pack moves back below the stamp the object carries.
        store.update(shape, null, "{\"id\":\"" + shape + "\","
                + basicShape(profile, "2.0.0").strip().substring(1));
        try {
            AtomicBoolean rebuilding = new AtomicBoolean(true);
            Thread round = Thread.ofVirtual().start(() -> {
                while (rebuilding.get()) {
                    store.shapesChanged();
                }
            });
            int served = 0;
            int refused = 0;
            try {
                long until = System.nanoTime() + 10_000_000_000L;
                while (System.nanoTime() < until) {
                    try {
                        store.read("Basic", tooNew);
                        served++;
                    } catch (ShapeTooNewException expected) {
                        refused++;
                    }
                }
            } finally {
                rebuilding.set(false);
                round.join();
            }
            assertTrue(refused > 0, "nothing was read, so nothing here was asked");
            assertEquals(0, served, "the object newer than the pack was served " + served
                    + " times of " + (served + refused) + " while the view was rebuilt");
        } finally {
            // It would be refused for every class after this one too.
            tenant.engine().delete("Basic", tooNew, null);
        }
    }

    @Test
    @DisplayName("a profile written while the view is being rebuilt is taken, rather than failing "
            + "on rows the rebuild expanded first")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void aProfileWrittenDuringARebuildIsTaken() throws Exception {
        SharedTenants.Tenant tenant = SharedTenants.of(SharedTenants.Shape.R4_RESHAPE);
        FhirStoreFacade store = tenant.store();
        AtomicBoolean rebuilding = new AtomicBoolean(true);
        // The round that rebuilds a tenant's view when a profile arrives,
        // which is also what expands the profile into rows.
        Thread round = Thread.ofVirtual().start(() -> {
            while (rebuilding.get()) {
                try {
                    store.shapesChanged();
                } catch (RuntimeException alsoRacing) {
                    // counted below by what the writes answered
                }
            }
        });
        java.util.List<String> refused = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                String profile = "https://gap.dbo.test/StructureDefinition/written-"
                        + UUID.randomUUID();
                try {
                    store.create(basicShape(profile, "1.0.0"));
                } catch (RuntimeException failed) {
                    refused.add(failed.getClass().getSimpleName() + ": " + failed.getMessage());
                }
            }
        } finally {
            rebuilding.set(false);
            round.join();
        }
        assertEquals(java.util.List.of(), refused,
                "a profile written while the view was rebuilt was not taken");
    }

    private static String basicShape(String url, String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"Gap","status":"active","kind":"resource","abstract":false,
                 "type":"Basic",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(url, version);
    }
}
