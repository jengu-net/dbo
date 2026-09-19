package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration naming a door it does not carry the parts for is refused where
 * declarations are refused.
 *
 * <p>Scim needs three things: an authority, whose tokens it uses; the person
 * vault, which is what the enumeration answering its user list actually reads;
 * and the person types its mapping writes. Two of those are the spec's own and
 * one is not — whether a deployment configured an authority is a fact about
 * the deployment, and no file can be wrong about it.
 *
 * <p>So the two the spec can be wrong about are refused at parse, by name,
 * for the reason a malformed step id already is: a file somebody has to change
 * is better named before a bring-up gets further, and the answer is the same
 * whether or not anything is running.
 *
 * <p>The third stays at bring-up because that is where it can first be known.
 * That leaves one condition rather than three, which is what makes this door
 * convertible to an activity at all — the point of moving the check rather
 * than the mount.
 */
class ASpecAnswersForWhatItCanBeWrongAboutTest {

    private static final TenantSpec.Scim SCIM = new TenantSpec.Scim("https://ee.ee/eid");

    @Test
    @DisplayName("scim declared without the vault it enumerates through is refused by name")
    void scimWithoutTheVaultIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> spec(false, "Person", "Practitioner"));

        assertTrue(refused.getMessage().contains("pdi"),
                "the refusal does not name what is missing, so somebody has a file to change "
                        + "and no idea which line: " + refused.getMessage());
    }

    @Test
    @DisplayName("and without the person types its mapping writes, naming both")
    void scimWithoutThePersonTypesIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> spec(true, "Patient"));

        assertTrue(refused.getMessage().contains("Person")
                        && refused.getMessage().contains("Practitioner"),
                refused.getMessage());
    }

    @Test
    @DisplayName("a spec carrying what scim is made of parses, because the third thing it "
            + "needs is not the spec's to say")
    void whatTheSpecCanCarryIsEnough() {
        // No authority anywhere in sight, and this still parses. That is the
        // division: a file is refused for what a file can be wrong about, and
        // the deployment answers for the rest at the point it can.
        assertDoesNotThrow(() -> spec(true, "Person", "Practitioner"));
    }

    @Test
    @DisplayName("and a spec that never mentions scim is not asked about it")
    void nothingIsAskedOfASpecThatDeclaredNoDoor() {
        assertDoesNotThrow(() -> new TenantSpec("plain", "r4",
                java.util.List.of(type("Patient")), false, null, null, null,
                java.util.List.of(), java.util.List.of(), null, java.util.List.of(),
                null, false, java.util.List.of()));
    }

    private static TenantSpec spec(boolean pdi, String... typeNames) {
        java.util.List<cloud.jengu.dbo.fhir.common.FhirTypeConfig> types =
                new java.util.ArrayList<>();
        for (String typeName : typeNames) {
            types.add(type(typeName));
        }
        return new TenantSpec("directory", "r4", types, pdi, null, null, null,
                java.util.List.of(), java.util.List.of(), SCIM, java.util.List.of(),
                null, false, java.util.List.of());
    }

    private static cloud.jengu.dbo.fhir.common.FhirTypeConfig type(String typeName) {
        return new cloud.jengu.dbo.fhir.common.FhirTypeConfig(typeName,
                cloud.jengu.dbo.core.api.IdentityClass.INTERNAL, java.util.Set.of(),
                cloud.jengu.dbo.core.api.Handling.operational());
    }
}
