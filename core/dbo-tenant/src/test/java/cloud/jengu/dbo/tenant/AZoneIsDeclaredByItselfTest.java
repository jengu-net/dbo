package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typo that used to be load-bearing.
 *
 * <p>A member's file said {@code "zone": "hogwarts"} and the hospital became
 * an identity zone: a hub over its database, its ceremony the one the members
 * federate to, and the deployment green. Nothing checked, because there was
 * nothing recording that a tenant was a zone to check against.
 */
class AZoneIsDeclaredByItselfTest {

    private static TenantSpec tenant(String code, String zone, boolean zoneRoot) {
        return new TenantSpec(code, "r5",
                List.of(new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER,
                        Set.of("urn:example"), Handling.operational())),
                false, cloud.jengu.dbo.policy.TenantPolicies.defaults(),
                zone, null, List.of(), List.of(), null, List.of(), null, false,
                List.of(), zoneRoot);
    }

    @Test
    @Proving(DboPromises.ZONE_A_ZONE_IS_DECLARED_BY_THE_TENANT_THAT_IS_ONE)
    @DisplayName("a tenant that does not declare itself a zone is not made one by a member")
    void aHospitalIsNotAJurisdictionBecauseSomebodyTypedItsName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantRuntimeManager.aZoneIsDeclaredByItself(
                        tenant("gringotts", "hogwarts", false),
                        tenant("hogwarts", null, false)));

        assertTrue(refused.getMessage().contains("gringotts")
                        && refused.getMessage().contains("hogwarts"),
                "the refusal names neither the file to change nor what it names: "
                        + refused.getMessage());
        assertTrue(refused.getMessage().contains("zoneRoot"),
                "the refusal does not say where the declaration belongs, which leaves "
                        + "somebody to guess at it: " + refused.getMessage());
    }

    @Test
    @Proving(DboPromises.ZONE_A_ZONE_IS_DECLARED_BY_THE_TENANT_THAT_IS_ONE)
    @DisplayName("and a tenant that does declare itself one is joined without ceremony")
    void aZoneThatSaysSoIsOne() {
        TenantRuntimeManager.aZoneIsDeclaredByItself(
                tenant("gringotts", "rl", false), tenant("rl", null, true));
    }

    @Test
    @DisplayName("being a zone and being in one are not exclusive, because a jurisdiction "
            + "holds ordinary records too")
    void aZoneIsAPropertyRatherThanAKind() {
        TenantSpec both = tenant("ee", "eu", true);
        assertEquals("eu", both.zone());
        assertTrue(both.zoneRoot());
    }

    @Test
    @DisplayName("a spec says it in its file, and a spec that says nothing is not one")
    void theDeclarationIsReadFromTheFile() {
        String json = """
                {"code":"rl","face":"r5","zoneRoot":true,
                 "types":[{"name":"ValueSet","identity":"canonical",
                           "handling":"projected-config"}]}""";
        assertTrue(TenantSpec.parse(json).zoneRoot());
        assertTrue(!TenantSpec.parse(json.replace("\"zoneRoot\":true,", "")).zoneRoot(),
                "a tenant that says nothing about zones came out a jurisdiction");
    }
}
