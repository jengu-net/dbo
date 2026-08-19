package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking for a tenant's secret waits for provisioning, and refuses rather than
 * answering with a null (#40).
 *
 * <p>`scanOnce()` returning is not provisioning having finished. Five setups
 * assumed it was, and the null they got travelled as far as
 * {@code URLEncoder.encode} — which reports a null string, so the failure named
 * neither the tenant nor the reason and arrived two frames from the cause. It
 * failed once in a parallel run and passed twice in isolation, which is the
 * shape that costs an afternoon.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProvisionedSecretIT {

    static PostgreSQLContainer<?> postgres;
    static LocalDatabasePerTenantProvisioner provisioner;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ProvisionedSecretIT"),
                postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    void down() {
        provisioner.close();
    }

    @Test
    @Timeout(60)
    @DisplayName("a tenant that was never provisioned is refused by name, not answered with null")
    void anAbsentSecretIsRefusedByName() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> provisioner.bootstrapClientSecret("puudub", Duration.ofMillis(50)));

        assertTrue(refused.getMessage().contains("puudub"),
                "the refusal must name the tenant, or the caller is where we were before: "
                        + refused.getMessage());
        assertTrue(refused.getMessage().contains("provisioned"),
                "and say what is missing rather than that something was null: "
                        + refused.getMessage());
    }

    @Test
    @Timeout(120)
    @DisplayName("a dependent reached before its upstream says so, and comes up on the next scan")
    void aDependentAheadOfItsUpstreamWaitsRatherThanNullPointers() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dbo-upstream-order");
        try (cloud.jengu.dbo.tenant.TenantRuntimeManager manager =
                     new cloud.jengu.dbo.tenant.TenantRuntimeManager(dir, provisioner,
                             "127.0.0.1", 0, null)) {
            // the dependent alone: its upstream does not exist yet, which is the
            // ordering a filesystem listing can hand us on any boot
            java.nio.file.Files.writeString(dir.resolve("jargnev.json"), """
                    {"code":"jargnev","fhirVersion":"r4","types":[
                      {"name":"CodeSystem","identity":"canonical","handling":"replicated"}],
                     "dependencies":[{"name":"ulemine","types":["CodeSystem"]}]}""");

            assertTrue(manager.scanOnce().isEmpty(),
                    "a dependent whose upstream is absent must not come up half-wired");
            assertTrue(manager.runtime("jargnev").isEmpty(), "it was served anyway");

            // and when the upstream arrives, the next scan brings both up —
            // the wait was a wait, not a permanent failure
            java.nio.file.Files.writeString(dir.resolve("ulemine.json"), """
                    {"code":"ulemine","fhirVersion":"r4","types":[
                      {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}""");

            // Scanning until it holds, not once: a scan walks the directory in
            // whatever order the filesystem gives, so a scan that meets the
            // dependent first correctly leaves it waiting and brings the
            // upstream up alone. The promise is that the wait ends, not that it
            // ends within one pass — asserting the stronger thing made this
            // test pass on one filesystem's ordering and fail on another's.
            java.util.Set<String> up = java.util.Set.of();
            for (int scan = 0; scan < 5 && !up.contains("jargnev"); scan++) {
                up = manager.scanOnce();
            }
            assertTrue(up.contains("ulemine") && up.contains("jargnev"),
                    "the retry did not resolve it: " + up);
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a secret produced while the caller is waiting is returned, not refused")
    void aSecretThatArrivesLateIsWaitedFor() throws Exception {
        TenantSpec spec = TenantSpec.parse("""
                {"code":"hilinev","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");

        // provisioning finishing a moment after the ask IS the race, so the test
        // is the race: a real provision from another thread, against the real
        // database, while this thread is already asking
        Thread late = new Thread(() -> provisioner.provision(spec));
        late.start();

        String secret = provisioner.bootstrapClientSecret("hilinev", Duration.ofSeconds(30));
        late.join();

        assertTrue(secret != null && !secret.isBlank(), "waited and got nothing usable");
        assertEquals(secret, provisioner.bootstrapClientSecret("hilinev"),
                "the same tenant's secret must not change once provisioned");
    }
}
