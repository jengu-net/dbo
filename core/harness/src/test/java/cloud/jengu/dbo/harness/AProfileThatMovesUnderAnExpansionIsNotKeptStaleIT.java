package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.element.ElementStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A profile that moves between the view being built and its definitions
 * being expanded.
 *
 * <p>Both the write that moves a profile and the round that sees one arrive
 * rebuild the tenant's view and then expand what it holds, and a profile that
 * states only what it changes is snapshotted from the view. The round runs on
 * a thread of its own, so a profile can move after its view was built and
 * before it expands. Here that is made to happen in order: the profile moves
 * beneath the face, as a lane or another node writes it, and the expansion
 * runs before any rebuild has seen it.
 */
@Tag("integration")
class AProfileThatMovesUnderAnExpansionIsNotKeptStaleIT {

    @Test
    @DisplayName("an expansion that finds a profile newer than its view keeps nothing from the "
            + "view, and what is written next is stamped by what the profile says now")
    @Proving({DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
            DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES})
    void aMovedProfileIsNotKeptAsWhatItUsedToSay() {
        SharedTenants.Tenant tenant = SharedTenants.of(SharedTenants.Shape.R4_RESHAPE);
        FhirStoreFacade store = tenant.store();
        ObjectStore engine = tenant.engine();
        assertTrue(store instanceof ElementStore, "the tenant's face is not the element face");
        String profile = "https://moved.dbo.test/StructureDefinition/" + UUID.randomUUID();
        String id = store.create(basicShape(profile, "2.0.0")).id();

        // The profile moves beneath the face, after its view was built.
        StoredObject held = engine.get("StructureDefinition", id).orElseThrow();
        engine.put(new PutRequest("StructureDefinition", id, held.versionId(),
                ("{\"id\":\"" + id + "\"," + basicShape(profile, "9.0.0").strip().substring(1))
                        .getBytes(StandardCharsets.UTF_8)));
        // The round's expansion, between its rebuild and the move.
        ((ElementStore) store).expandDefinitionsHeld();
        // And the rebuild that follows the move, as its write or the round does.
        store.shapesChanged();

        String note = store.create("""
                {"resourceType":"Basic","code":{"text":"note"},
                 "meta":{"profile":["%s"]}}""".formatted(profile)).id();
        String served = store.read("Basic", note);
        assertTrue(served.contains("\"valueString\":\"9.0.0\""),
                "a write after the profile moved was stamped by what it used to say: " + served);
    }

    private static String basicShape(String url, String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"Moved","status":"active","kind":"resource","abstract":false,
                 "type":"Basic",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(url, version);
    }
}
