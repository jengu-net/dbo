package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant that asks for PDI gets the coarsening its face publishes.
 *
 * <p>The rule is that the engine declares an element is GENERALISED and the
 * face knows how — only a face knows a birth date reduces to its year. In
 * production the wrapper was built without the face's rule at all, so every
 * generalised element silently became a removed one: a tenant asking for a
 * coarse birth date got none.
 *
 * <p>It went unnoticed because the test that covers coarsening builds the
 * store itself and passes a real rule in. This one takes the tenant the
 * runtime provisions, which is the shape that actually runs.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvisionedPdiCoarsensIT {


    static SharedTenants.Tenant tenant;

    @BeforeAll
    void up() throws Exception {
        // Shared. What it asks is what a provisioned tenant does to a birth
        // date behind the membrane — a question about the membrane, not about
        // a tenant of its own.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_PDI_PERSON);
    }

    @Test
    void aGeneralisedElementIsCoarseAtRest() throws Exception {
        String id = tenant.engine()
                .put(PutRequest.create("Patient", ("""
                        {"resourceType":"Patient","birthDate":"1970-01-01",
                         "name":[{"family":"Coarse"}]}""")
                        .getBytes(StandardCharsets.UTF_8)))
                .id();

        String stored = atRest(id);
        assertTrue(stored.contains("\"birthDate\":\"1970\""),
                "the year must survive in the clear, or a reader with no right to the full "
                        + "date gets nothing where a clinician needs an age: " + stored);
        assertFalse(stored.contains("1970-01-01"),
                "and the full date rides encrypted like the rest: " + stored);
    }

    /**
     * What the database holds, read straight from the tenant's own.
     *
     * <p>It used to provision the tenant again from its spec file to get a
     * connection. On a shared tenant there is no spec file of this class's
     * own, and provisioning a second time to read one row was never what the
     * test meant — it meant "look at what is actually stored".
     */
    private static String atRest(String id) throws Exception {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        try (java.sql.Connection c = source.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT payload FROM state.r4_data WHERE id = ?::uuid")) {
            ps.setString(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new String(rs.getBytes(1), StandardCharsets.UTF_8);
            }
        }
    }

}
