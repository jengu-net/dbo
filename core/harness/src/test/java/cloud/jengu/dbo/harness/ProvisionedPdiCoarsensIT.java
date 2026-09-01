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

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-pdi-coarse");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ProvisionedPdiCoarsensIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("varjatud.json"), """
                {"code":"varjatud","face":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("varjatud"));
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    void aGeneralisedElementIsCoarseAtRest() throws Exception {
        String id = manager.runtime("varjatud").orElseThrow().engine()
                .put(PutRequest.create("Patient", ("""
                        {"resourceType":"Patient","birthDate":"1970-01-01",
                         "name":[{"family":"Coarse"}]}""")
                        .getBytes(StandardCharsets.UTF_8)))
                .id();

        String stored = atRest("varjatud", id);
        assertTrue(stored.contains("\"birthDate\":\"1970\""),
                "the year must survive in the clear, or a reader with no right to the full "
                        + "date gets nothing where a clinician needs an age: " + stored);
        assertFalse(stored.contains("1970-01-01"),
                "and the full date rides encrypted like the rest: " + stored);
    }

    /** The payload as the inner engine holds it, under the encryption. */
    private static String atRest(String tenant, String id) throws Exception {
        try (java.sql.Connection c = provisioner.provision(
                        cloud.jengu.dbo.tenant.TenantSpec.parse(
                                Files.readString(dir.resolve(tenant + ".json"))))
                        .dataSource().getConnection();
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
