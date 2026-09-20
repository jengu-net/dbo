package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A staff directory over identity held in the clear is refused rather than
 * served.
 *
 * <p>A world of its own, and the reason is the claim itself: what is asserted
 * is that a tenant does NOT come up. It is made by putting a spec where the
 * deployment reads specs and running the scan that would have brought it up,
 * which visits every tenant the runtime holds — so a shared one would run the
 * other classes' tenants through a pass they did not ask for, and the trouble
 * this reads would be a ledger everybody writes to.
 *
 * <p>Everything the directory does once it is up is
 * {@link ScimProvisioningIT}, on a shared tenant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimNeedsTheMembraneIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-scim-membrane");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ScimNeedsTheMembraneIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
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
    @DisplayName("scim without pdi never comes up, and the ledger says why")
    @Proving(DboPromises.SCIM_DECLARED_PER_TENANT)
    void aDirectoryOverPlainIdentityIsRefused() throws Exception {
        Files.writeString(dir.resolve("clear-headed.json"), """
                {"code":"clear-headed","face":"r4","pdi":false,
                 "audit":{"level":"writes"},
                 "scim":{"system":"urn:test:idp:external-id"},
                 "types":[
                  {"name":"Person","identity":"identifier",
                   "systems":["urn:test:idp:external-id"],"handling":"operational"},
                  {"name":"Practitioner","identity":"internal","handling":"operational"}]}""");
        manager.scanOnce();

        assertFalse(manager.runtime("clear-headed").isPresent(),
                "a staff directory over identity in the clear is refused, not served");
        assertTrue(String.valueOf(manager.troubles()).contains("scim requires pdi"),
                "and the refusal is answerable from the trouble ledger, not only "
                        + "as an absent endpoint: " + manager.troubles());
    }
}
