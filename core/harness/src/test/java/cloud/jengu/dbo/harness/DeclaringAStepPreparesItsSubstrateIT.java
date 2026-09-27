package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step declared has somewhere for its work to sit, before there is any work.
 *
 * <p>Declaring is provisioning, the same way a tenant's database is prepared
 * when the tenant is declared. What is prepared is NOT a tenant: a database
 * the runtime owns, carrying a durable bootstrap and nothing else, made
 * through the admin connection that provisions tenants and never through the
 * path that provisions one — because a thing that is not a tenant must not
 * look like one to everything downstream, starting with erasure.
 *
 * <p>Its own world because the claim is about what declaring a step does to a
 * deployment, and the declaration lives in the management descriptor that a
 * deployment is configured with rather than in the watched directory.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeclaringAStepPreparesItsSubstrateIT {

    /** Two of these share a substrate by naming it; the third names none. */
    private static final String SHARED = "fleet.retention.sweep";
    private static final String ALSO_SHARED = "fleet.retention.expire";
    private static final String ALONE = "fleet.coding.normalise";

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Path managementSpec;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("DeclaringAStepPreparesItsSubstrateIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(Files.createTempDirectory("dbo-substrates"),
                provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        Files.writeString(managementSpec, descriptorWith(SHARED, ALSO_SHARED, ALONE));
        manager.manages(managementSpec);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @Order(1)
    @DisplayName("two steps naming one substrate share it, and a step naming none gets its own")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void placementIsHonoured() {
        var substrates = manager.stepSubstrates();

        assertEquals(3, substrates.size(),
                "a declared step has nowhere for its work to sit: " + substrates.keySet());
        assertSame(substrates.get(SHARED), substrates.get(ALSO_SHARED),
                "two steps named one substrate and got two, so naming it twice costs twice "
                        + "the connections — which is the dial this placement decision is");
        assertNotSame(substrates.get(ALONE), substrates.get(SHARED),
                "a step that named no substrate was put on somebody else's, so 'no placement "
                        + "stated' silently means shared rather than its own");
    }

    @Test
    @Order(2)
    @DisplayName("the databases exist, named apart from tenants, and carry no store schema")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void theyAreDatabasesAndNotTenants() throws Exception {
        assertTrue(databaseExists(TenantSpec.substrateDatabaseName("retention")),
                "the shared substrate was not created, so two steps point at nothing");
        assertTrue(databaseExists(TenantSpec.substrateDatabaseName(ALONE)),
                "a step naming no substrate got no database of its own either");

        // Not a tenant: nothing provisioned it as one, so it holds none of
        // what a tenant's database holds. Asked of the database rather than of
        // the code, because the claim is about what was created.
        //
        // A DURABLE BOOTSTRAP AND NOTHING ELSE, which is two assertions and
        // not one. The joiner migrates each substrate as it is built, so
        // `dbos` is here and is supposed to be; what must not be here is any
        // schema of the store's — that would mean this went through the path
        // that provisions a tenant and is now a thing which is not a tenant
        // looking exactly like one.
        try (Connection c = manager.stepSubstrates().get(ALONE).getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT table_schema, count(*) FROM information_schema.tables "
                                + "WHERE table_schema NOT IN ('pg_catalog','information_schema') "
                                + "GROUP BY table_schema");
                ResultSet rs = ps.executeQuery()) {
            java.util.Map<String, Integer> bySchema = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                bySchema.put(rs.getString(1), rs.getInt(2));
            }
            assertTrue(bySchema.containsKey("dbos"),
                    "the substrate carries no durable layer, so a step declared has a "
                            + "database and still nowhere for its work to go: " + bySchema);
            assertEquals(java.util.Set.of("dbos"), bySchema.keySet(),
                    "a step's substrate carries a schema that is not the durable layer's, so "
                            + "it went through the path that provisions a tenant: " + bySchema);
        }

        assertTrue(TenantSpec.substrateDatabaseName(ALONE).startsWith("step_"),
                "a step's database is not told apart from a tenant's by name, so `\\l` reads "
                        + "as a list of tenants and two namespaces can collide");
    }

    @Test
    @Order(3)
    @DisplayName("a withdrawn step stops being performed and its substrate stays, because the "
            + "work in it belongs to tenants who believe it is being done")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void withdrawalRemovesNothing() throws Exception {
        Files.writeString(managementSpec, descriptorWith(SHARED, ALSO_SHARED));
        manager.manages(managementSpec);

        assertTrue(databaseExists(TenantSpec.substrateDatabaseName(ALONE)),
                "the substrate went when the declaration did, so a configuration change "
                        + "destroyed work that tenants are still waiting on");
    }

    private boolean databaseExists(String dbName) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(
                SharedPostgres.urlFor("DeclaringAStepPreparesItsSubstrateIT"),
                postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String descriptorWith(String... steps) {
        StringBuilder declared = new StringBuilder();
        for (String step : steps) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"code\":\"").append(step)
                    .append("\",\"slots\":{\"record\":\"Reference(Observation)\"}")
                    .append(step.startsWith("fleet.retention.") ? ",\"substrate\":\"retention\"" : "")
                    .append("}");
        }
        return """
                {"code":"registry","face":"r4","types":[
                   {"name":"Observation","identity":"internal","handling":"operational"}],
                 "fleetSteps":[%s]}""".formatted(declared);
    }
}
