package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a node calls the databases it makes: the names every node has always
 * used, unless it is told a namespace of its own.
 */
class ADatabaseIsNamedAsItsNodesTest {

    private static final String LONG = "a-tenant-whose-code-is-long-enough-to-be-folded-into-sixty"
            + "-three";

    @Test
    @DisplayName("a node told no namespace names its databases exactly as every node always has")
    @Proving(DboPromises.TEN_NODES_SHARE_A_SERVER_UNDER_NAMESPACES)
    void noNamespaceKeepsTodaysNames() {
        assertEquals("tenant_fhir_r5", TenantSpec.databaseName("fhir-r5"));
        assertEquals("tenant_fhir_r5", TenantSpec.databaseName(null, "fhir-r5"));
        assertEquals("step_dbo_fleet_report", TenantSpec.substrateDatabaseName("dbo.fleet-report"));
        assertEquals(TenantSpec.databaseName(LONG), TenantSpec.databaseName(null, LONG));
        assertEquals(63, TenantSpec.databaseName(LONG).length());
    }

    @Test
    @DisplayName("a namespace leads every name, stays readable when a name is folded, and two "
            + "namespaces never name one database")
    @Proving(DboPromises.TEN_NODES_SHARE_A_SERVER_UNDER_NAMESPACES)
    void aNamespaceLeadsEveryName() {
        assertEquals("site_a_tenant_fhir_r5", TenantSpec.databaseName("site-a", "fhir-r5"));
        assertEquals("site_a_step_dbo_fleet_report",
                TenantSpec.substrateDatabaseName("site-a", "dbo.fleet-report"));

        String folded = TenantSpec.databaseName("site-a", LONG);
        assertEquals(63, folded.length());
        assertTrue(folded.startsWith("site_a_tenant_"), "the namespace was folded away: " + folded);
        assertNotEquals(folded, TenantSpec.databaseName("site-b", LONG),
                "two nodes folded one long code into one name");
        assertNotEquals(TenantSpec.databaseName(LONG), folded);
    }

    @Test
    @DisplayName("a namespace a database cannot be named under is refused when the node is set up")
    @Proving(DboPromises.TEN_NODES_SHARE_A_SERVER_UNDER_NAMESPACES)
    void aBadNamespaceIsRefused() {
        for (String bad : new String[] {"Site A", "site;drop", "a-namespace-far-longer-than-a-name"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LocalDatabasePerTenantProvisioner("jdbc:postgresql://nowhere/x",
                            "u", "p", bad), bad);
        }
    }
}
