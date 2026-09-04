package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantDatabaseProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.TenantState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant that is not serving says which of the three it is, and says it
 * again the same way on the next pass.
 *
 * <p>Both halves were wrong in ways that read as the same thing to whoever
 * declared the tenant. Storage that had not arrived yet was waited for on the
 * thread that brings every tenant up, so a tenant behind a slow one was not
 * slow, it was absent. And a bring-up that failed after mounting a surface
 * left the surface mounted, so the retry died on its own OIDC context and the
 * ledger reported {@code cannot add context to list} instead of the spec that
 * was wrong.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantThatIsNotUpSaysWhyIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static WhenTheOperatorIsBehind storage;
    static TenantRuntimeManager manager;

    /**
     * A provisioner whose storage is somebody else's job and has not arrived —
     * the in-cluster shape, where an operator creates the role, the database
     * and the Secret, and the serving node finds them or does not.
     */
    static final class WhenTheOperatorIsBehind implements TenantDatabaseProvisioner {

        private final LocalDatabasePerTenantProvisioner real;
        private final Set<String> withheld = ConcurrentHashMap.newKeySet();

        WhenTheOperatorIsBehind(LocalDatabasePerTenantProvisioner real) {
            this.real = real;
        }

        void withhold(String code) {
            withheld.add(code);
        }

        void arrives(String code) {
            withheld.remove(code);
        }

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            if (withheld.contains(spec.code())) {
                throw new NotProvisionedYet("tenant " + spec.code()
                        + " is waiting for its secret");
            }
            return real.provision(spec);
        }

        @Override
        public void deprovision(String tenantCode) {
            real.deprovision(tenantCode);
        }

        @Override
        public void release(String tenantCode) {
            real.release(tenantCode);
        }
    }

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-not-up");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantThatIsNotUpSaysWhyIT"),
                postgres.getUsername(), postgres.getPassword());
        storage = new WhenTheOperatorIsBehind(provisioner);
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, storage, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
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

    private static String spec(String code) {
        return """
                {"code":"%s","face":"r4","types":[
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(code);
    }

    private TenantState.State stateOf(String code) {
        return manager.tenantStates().stream()
                .filter(state -> state.code().equals(code))
                .findFirst().orElseThrow().state();
    }

    /**
     * The tenant behind the waiting one is the assertion. Waiting for storage
     * used to happen inside the scan, so the queue moved at the speed of
     * whoever was at the front of it.
     */
    @Test
    @Order(1)
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void aTenantWaitingForItsStorageDoesNotHoldUpTheOneBehindIt() throws Exception {
        storage.withhold("waiting-clinic");
        Files.writeString(dir.resolve("waiting-clinic.json"), spec("waiting-clinic"));
        Files.writeString(dir.resolve("ready-clinic.json"), spec("ready-clinic"));

        Set<String> serving = manager.scanOnce();

        assertTrue(serving.contains("ready-clinic"),
                "the tenant behind the waiting one did not come up: " + manager.troubles());
        assertFalse(serving.contains("waiting-clinic"));
        assertEquals(TenantState.State.COMING_UP, stateOf("waiting-clinic"),
                "storage that has not arrived is a wait, not a fault");
        assertTrue(manager.troubles().get("waiting-clinic").contains("waiting for its secret"),
                "the ledger has to say what it is waiting for: " + manager.troubles());
    }

    /** And when the storage arrives, the next pass is all it takes. */
    @Test
    @Order(2)
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void andItComesUpOnTheNextPassOnceTheStorageArrives() {
        storage.arrives("waiting-clinic");
        UntilServed.scan(manager, "waiting-clinic");
        assertEquals(TenantState.State.SERVING, stateOf("waiting-clinic"));
        assertFalse(manager.troubles().containsKey("waiting-clinic"),
                "a tenant that came up is nobody's trouble any more");
    }

    /**
     * The spec declares a SCIM surface it cannot serve, which is refused —
     * but only after the tenant's OIDC surface is already mounted. The second
     * scan is the whole test: it has to meet the same refusal, not the
     * wreckage of the first one.
     */
    @Test
    @Order(3)
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void aBringUpThatFailedHalfWayStillSaysWhyOnEveryLaterPass() throws Exception {
        // Parseable, and unservable only once the tenant is being built: the
        // SCIM door needs the person types the mapping writes, and this spec
        // declares none of them.
        Files.writeString(dir.resolve("halted-clinic.json"), """
                {"code":"halted-clinic","face":"r4","pdi":true,"types":[
                  {"name":"Observation","identity":"internal","handling":"operational"}],
                 "scim":{"system":"https://staff.test/ids"}}""");

        manager.scanOnce();
        String first = manager.troubles().get("halted-clinic");
        assertTrue(first != null && first.contains("scim declared but unservable"),
                "troubles=" + manager.troubles() + " states=" + manager.tenantStates());
        assertEquals(TenantState.State.FAILED, stateOf("halted-clinic"));

        manager.scanOnce();
        String second = manager.troubles().get("halted-clinic");
        assertTrue(second.contains("scim declared but unservable"),
                "the retry met its own leftovers instead of the reason: " + second);
        assertEquals(first, second, "the same wrong spec has to read the same way twice");
    }
}
