package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A place whose account of what its configuration applied cannot be followed
 * to the records it names.
 *
 * <p>Releasing forgot an entry whenever no record it named was left standing,
 * and an entry whose lookup found nothing at all left none standing either:
 * it was forgotten while its record still shadowed. A place restarted a few
 * times forgot its whole account that way, and with it the only thing that
 * told the configuration's records from a person's. A place found like that
 * held 715 records in front of its origin's versions and no account at all.
 */
class APlaceDoesNotForgetWhatItsConfigurationAppliedTest {

    private static final String CODE = "clinic";
    private static final String ORG = "urn:example:org";
    private static final String PLAN = "urn:example:plan";

    private final ConfigurationInMemory tenant = new ConfigurationInMemory();
    private final ConfigFiles ledger = new ConfigFiles(tenant.store(),
            OwnersConfiguration.scopeOf(CODE));

    @Test
    @Proving(DboPromises.SYNC_A_PLACE_LETS_GO_OF_WHAT_ITS_OLD_CONFIGURATION_APPLIED)
    @DisplayName("an entry whose record no lookup finds is kept across starts while that "
            + "type still shadows")
    void anEntryNoLookupFindsIsKept() {
        tenant.handles("Organization", Handling.operational());
        Map<String, String> shadowing = new LinkedHashMap<>();
        for (int ward = 1; ward <= 150; ward++) {
            Identifier identity = new Identifier(ORG, "ward-" + ward);
            ledger.record("ward-" + ward + ".json", "blob", "Organization", List.of(identity));
            String id = tenant.holds("Organization", identity);
            shadowing.put(id, "Organization");
            if (ward % 15 == 0) {
                tenant.unindexes(id);
            }
        }

        OwnersConfiguration.Released first = null;
        for (int start = 0; start < 12; start++) {
            Map<String, String> still = new LinkedHashMap<>();
            shadowing.forEach((id, type) -> {
                if (tenant.has(id)) {
                    still.put(id, type);
                }
            });
            OwnersConfiguration.Released released =
                    OwnersConfiguration.releasedAtAPlace(CODE, tenant.store(), still);
            if (first == null) {
                first = released;
            }
            assertEquals(10, released.unfound(), "start " + start + ": " + released);
        }

        assertEquals(140, first.byAccount());
        assertEquals(10, ledger.applied().size(),
                "the account forgot records it could not find, while they still shadow");
        assertEquals(10, tenant.count("Organization"),
                "an operational type's record is not the configuration's on its type's word");
    }

    @Test
    @Proving(DboPromises.SYNC_A_PLACE_LETS_GO_OF_WHAT_ITS_OLD_CONFIGURATION_APPLIED)
    @DisplayName("with no account, a shadowing record of a type only the configuration writes "
            + "is released, and a person's record keeps shadowing")
    void aTypeOnlyTheConfigurationWritesIsReleasedWithoutTheAccount() {
        tenant.handles("PlanDefinition", Handling.projectedConfig());
        tenant.handles("Organization", Handling.operational());
        Map<String, String> shadowing = new LinkedHashMap<>();
        for (int plan = 1; plan <= 120; plan++) {
            shadowing.put(tenant.holds("PlanDefinition", new Identifier(PLAN, "plan-" + plan)),
                    "PlanDefinition");
        }
        String overridden = tenant.holds("Organization", new Identifier(ORG, "ward-7"));
        shadowing.put(overridden, "Organization");

        OwnersConfiguration.Released released =
                OwnersConfiguration.releasedAtAPlace(CODE, tenant.store(), shadowing);

        assertEquals(new OwnersConfiguration.Released(0, 120, 0), released);
        assertEquals(0, tenant.count("PlanDefinition"),
                "records only the configuration could have written still shadow");
        assertTrue(tenant.has(overridden), "a record somebody at the place wrote was released");
    }
}
