package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantKinds;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's kind: a label it declares or its declaration gives it, published
 * for something to select it by, and deciding nothing in the store.
 */
class ATenantSaysWhatKindItIsTest {

    private static TenantSpec spec(String extra) {
        return TenantSpec.parse("""
                {"code":"clinic","face":"r5"%s,
                 "types":[{"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(extra));
    }

    private static TenantFacts facts(TenantSpec spec) {
        return PublishedFacts.of(spec, new TenantFacts.Resolved(true, true, false, false), false);
    }

    @Test
    @DisplayName("a tenant that says nothing is an ordinary tenant, a face root is a face, and "
            + "a projection the store makes says it is one")
    @Proving(DboPromises.TEN_A_TENANT_SAYS_WHAT_KIND_IT_IS)
    void whatADeclarationMakesATenant() {
        assertEquals(TenantKinds.TENANT, spec("").kind());
        assertEquals(TenantKinds.FACE, TenantSpec.parse("""
                {"code":"fhir-r5","face":"r5","faceRoot":true,
                 "types":[{"name":"StructureDefinition","identity":"canonical",
                   "handling":"operational"}]}""").kind());

        List<ConfigApplication.Declared> world = new ArrayList<>();
        world.add(declared("fhir-r4", """
                {"code":"fhir-r4","face":"r4","faceRoot":true,
                 "types":[{"name":"CodeSystem","identity":"canonical","handling":"replicated"}]}"""));
        world.add(declared("rl", """
                {"code":"rl","face":"r5","zoneRoot":true,
                 "types":[{"name":"CodeSystem","identity":"canonical","handling":"replicated"}]}"""));
        world.add(declared("gringotts", """
                {"code":"gringotts","face":"r4","zone":"rl",
                 "dependencies":[{"name":"rl","types":["CodeSystem"]}],
                 "types":[{"name":"CodeSystem","identity":"canonical","handling":"replicated"}]}"""));
        List<ConfigApplication.Declared> made = ZoneProjections.neededBy(world);
        assertFalse(made.isEmpty(), "no projection was made");
        for (ConfigApplication.Declared projection : made) {
            assertEquals(TenantKinds.PROJECTION, TenantSpec.parse(new String(
                    projection.payload(), StandardCharsets.UTF_8)).kind(), projection.name());
        }
    }

    @Test
    @DisplayName("a kind outside dbo. is any name, published as a fact an activity selects on, "
            + "and the activity runs for that kind alone")
    @Proving(DboPromises.TEN_A_TENANT_SAYS_WHAT_KIND_IT_IS)
    void aFreeKindIsSelectedOn() {
        TenantSpec small = spec(",\"kind\":\"clinic.small\"");
        assertEquals("clinic.small", small.kind());
        assertEquals("clinic.small", facts(small).properties().get(TenantFacts.KIND));

        TenantActivities activities = new TenantActivities();
        List<String> billed = new ArrayList<>();
        activities.register(TenantPoint.SURFACES, "(" + TenantFacts.KIND + "=clinic.small)",
                "billing small clinics", tenant -> {
                    billed.add(String.valueOf(tenant.facts().properties().get(TenantFacts.KIND)));
                    return null;
                });
        activities.runAt(TenantPoint.SURFACES,
                new TenantActivities.Provisioned(facts(small), null, null, "r5"), (n, e) -> { });
        activities.runAt(TenantPoint.SURFACES,
                new TenantActivities.Provisioned(facts(spec("")), null, null, "r5"), (n, e) -> { });
        assertEquals(List.of("clinic.small"), billed);
    }

    @Test
    @DisplayName("a dbo. kind the store does not know, a kind that disagrees with being a face "
            + "root, and a kind that is not a name are refused by name")
    @Proving(DboPromises.TEN_A_TENANT_SAYS_WHAT_KIND_IT_IS)
    void aKindThatCannotBeIsRefused() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> spec(",\"kind\":\"dbo.hospital\""));
        assertTrue(unknown.getMessage().contains("dbo.hospital")
                && unknown.getMessage().contains(TenantKinds.PROJECTION), unknown.getMessage());

        IllegalArgumentException face = assertThrows(IllegalArgumentException.class,
                () -> spec(",\"kind\":\"dbo.face\""));
        assertTrue(face.getMessage().contains("faceRoot"), face.getMessage());

        IllegalArgumentException root = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"fhir-r5","face":"r5","faceRoot":true,"kind":"clinic.small",
                         "types":[{"name":"StructureDefinition","identity":"canonical",
                           "handling":"operational"}]}"""));
        assertTrue(root.getMessage().contains(TenantKinds.FACE), root.getMessage());

        assertThrows(IllegalArgumentException.class, () -> spec(",\"kind\":\"a clinic\""));
    }

    private static ConfigApplication.Declared declared(String code, String json) {
        return new ConfigApplication.Declared(TenantDeclarationModel.TYPE, code + ".json",
                json.getBytes(StandardCharsets.UTF_8));
    }
}
