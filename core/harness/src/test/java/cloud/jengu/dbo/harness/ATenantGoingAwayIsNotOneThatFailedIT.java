package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant being taken down is not a tenant that failed to come up.
 *
 * <p>A sweep racing its own teardown reported as {@code tenant bring-up
 * failed: declaration=<x>.json}, at ERROR, once per deleted tenant. Everything
 * about that sentence sends a reader somewhere the answer is not — to the
 * declaration, to the spec file, to whether the tenant is broken — when what
 * happened is that somebody deleted the tenant and the pool closed under the
 * work in flight. Ten a run, in a suite with a tenant per class.
 *
 * <p>Nothing is wrong when this happens, so it is no state at all: not
 * COMING_UP, which says the next round fixes it, and not FAILED, which says
 * somebody must.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATenantGoingAwayIsNotOneThatFailedIT {

    static final String CODE = "going-away";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-going-away");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantGoingAwayIsNotOneThatFailedIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null, null);
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
    @Timeout(600)
    @DisplayName("a tenant whose storage went away mid-flight is neither coming up nor failed, "
            + "and its declaration is not blamed")
    void takenDownIsNotFailed() throws Exception {
        Files.writeString(dir.resolve(CODE + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CODE));
        UntilServed.scan(manager, CODE);

        // The race itself, made to happen rather than waited for: a mount runs
        // against a pool that has already closed, which is what a deletion in
        // flight leaves behind. Waiting for the real interleaving would be a
        // test that passes for the wrong reason on a slow machine.
        Path second = Files.createTempDirectory("dbo-going-away-2");
        Files.writeString(second.resolve(CODE + ".json"),
                Files.readString(dir.resolve(CODE + ".json")));
        try (TenantRuntimeManager racing = new TenantRuntimeManager(second,
                new ClosedBeforeItIsUsed(provisioner), "127.0.0.1", 0, null, null)) {
            racing.scanOnce();

            TenantState raced = racing.tenantStates().stream()
                    .filter(one -> CODE.equals(one.code()))
                    .findFirst().orElse(null);
            assertFalse(raced != null && raced.state() == TenantState.State.FAILED,
                    "a tenant whose storage was already gone was recorded as one that failed "
                            + "to come up, which sends a reader to its declaration: " + raced);
            assertFalse(racing.troubles().containsKey(CODE),
                    "a tenant being taken down left trouble against its name for somebody to "
                            + "investigate: " + racing.troubles());
        }
        manager.scanOnce();

        TenantState state = manager.tenantStates().stream()
                .filter(one -> CODE.equals(one.code()))
                .findFirst().orElse(null);
        if (state != null) {
            assertFalse(state.state() == TenantState.State.FAILED,
                    "a tenant being taken down was recorded as one that failed to come up, "
                            + "which sends a reader to its declaration: " + state);
        }
        assertFalse(manager.troubles().containsKey(CODE),
                "a tenant being taken down left trouble against its name for somebody to "
                        + "investigate: " + manager.troubles());
    }

    /**
     * A provisioner whose pool is closed before anything uses it — the state a
     * deletion in flight leaves for work that is already under way.
     */
    private record ClosedBeforeItIsUsed(
            cloud.jengu.dbo.tenant.TenantDatabaseProvisioner inner)
            implements cloud.jengu.dbo.tenant.TenantDatabaseProvisioner {

        @Override
        public TenantDatabase provision(cloud.jengu.dbo.tenant.TenantSpec spec) {
            TenantDatabase database = inner.provision(spec);
            // Through AutoCloseable rather than the pool's own type: this test
            // is about a data source that has gone, not about which pool it
            // was.
            if (database.dataSource() instanceof AutoCloseable pool) {
                try {
                    pool.close();
                } catch (Exception alreadyGone) {
                    // being closed is the point
                }
            }
            return database;
        }

        @Override
        public void deprovision(String tenantCode) {
            inner.deprovision(tenantCode);
        }
    }

    /** And a real failure is still reported as one. */
    @Test
    @Timeout(600)
    @DisplayName("a declaration that genuinely cannot come up is still recorded as failed")
    void arealFailureStillFails() throws Exception {
        Files.writeString(dir.resolve("broken.json"), """
                {"code":"broken","face":"no-such-face","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        manager.scanOnce();

        assertTrue(manager.troubles().containsKey("broken")
                        || manager.tenantStates().stream().anyMatch(one ->
                                "broken".equals(one.code())
                                        && one.state() == TenantState.State.FAILED),
                "a declaration naming a face nothing provides stopped being reported, so the "
                        + "quieting went too far: " + manager.troubles());
    }
}
