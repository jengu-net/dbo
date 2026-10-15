package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whoever listens for a point hears of every tenant past it, once, however
 * late they started listening — the management tenant above all, which comes
 * up inside the runtime's own start, before any application has registered
 * anything.
 *
 * <p>A world of its own because it has a management tenant, takes a tenant
 * down and brings it up again, and counts what each listener is told of every
 * tenant in it; a listener on the shared world would be told of tenants other
 * classes bring up and down.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EveryListenerHearsOfEveryTenantIT {

    private static final String RUN = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private static final String MANAGING = "managing-" + RUN;
    private static final String ONE = "one-" + RUN;
    private static final String TWO = "two-" + RUN;

    private LocalDatabasePerTenantProvisioner databases;
    private TenantRuntimeManager manager;
    private Path dir;
    /** Registered once the management tenant serves, and before any other tenant does. */
    private final Counting early = new Counting();

    /** How many times a listener was told of each tenant. */
    private static final class Counting implements TenantLifecycleListener {
        final Map<String, Integer> told = new ConcurrentHashMap<>();

        @Override
        public void reached(TenantPoint point, TenantFacts tenant) {
            told.merge(tenant.code(), 1, Integer::sum);
        }
    }

    @BeforeAll
    void aManagedDeployment() throws Exception {
        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("EveryListenerHearsOfEveryTenantIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        dir = Files.createTempDirectory("dbo-listeners");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Path management = Files.createTempDirectory("dbo-listeners-management")
                .resolve(MANAGING + ".json");
        Files.writeString(management, spec(MANAGING));
        // Up inside the runtime's start, before anybody listens: what the
        // Activator does before the application's extensions exist.
        manager.manages(management);
        manager.addLifecycleListener(TenantPoint.SERVING, null, "early", early);
        Files.writeString(dir.resolve(ONE + ".json"), spec(ONE));
        Files.writeString(dir.resolve(TWO + ".json"), spec(TWO));
        UntilServed.scan(manager, ONE, TWO);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        SuiteDatabases.retire(databases);
    }

    @Test
    @Order(1)
    @DisplayName("a listener registered after the management tenant came up is told of it, and "
            + "of every tenant that came up after, once each")
    @Proving(DboPromises.TEN_A_LISTENER_HEARS_OF_EVERY_TENANT_PAST_ITS_POINT)
    void theManagementTenantIsHeardOf() {
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 1), early.told);
    }

    @Test
    @Order(2)
    @DisplayName("a listener registered while tenants serve is told, once, of each tenant its "
            + "target selects, and one whose target leaves the management tenant out is not "
            + "told of it")
    @Proving(DboPromises.TEN_A_LISTENER_HEARS_OF_EVERY_TENANT_PAST_ITS_POINT)
    void aLateListenerIsToldOfWhatServes() {
        Counting late = new Counting();
        manager.addLifecycleListener(TenantPoint.SERVING, null, "late", late);
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 1), late.told);

        Counting members = new Counting();
        manager.addLifecycleListener(TenantPoint.SERVING,
                "(!(" + TenantFacts.CODE + "=" + MANAGING + "))", "members", members);
        assertEquals(Map.of(ONE, 1, TWO, 1), members.told);

        // Nobody is told again by somebody else arriving.
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 1), early.told);
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 1), late.told);
    }

    @Test
    @Order(3)
    @DisplayName("a tenant brought up again is a new arrival, told once more; a tenant that is "
            + "down is told to nobody arriving; and a listener removed is told nothing more")
    @Proving(DboPromises.TEN_A_LISTENER_HEARS_OF_EVERY_TENANT_PAST_ITS_POINT)
    void aTenantThatComesBackIsToldAgain() throws Exception {
        Counting removed = new Counting();
        manager.addLifecycleListener(TenantPoint.SERVING, null, "removed", removed);
        manager.removeLifecycleListener(removed);

        Files.delete(dir.resolve(TWO + ".json"));
        long giveUp = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (manager.runtime(TWO).isPresent() && System.nanoTime() < giveUp) {
            manager.scanOnce();
            Thread.sleep(250);
        }
        assertTrue(manager.runtime(TWO).isEmpty(), TWO + " did not go down");
        Counting whileDown = new Counting();
        manager.addLifecycleListener(TenantPoint.SERVING, null, "while-down", whileDown);
        assertEquals(Map.of(MANAGING, 1, ONE, 1), whileDown.told,
                "a listener was told of a tenant that is down");

        Files.writeString(dir.resolve(TWO + ".json"), spec(TWO));
        UntilServed.scan(manager, TWO);
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 2), early.told);
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 1), whileDown.told);
        assertEquals(Map.of(MANAGING, 1, ONE, 1, TWO, 1), removed.told,
                "a removed listener was told of a tenant that came up after");
    }

    private static String spec(String code) {
        return """
                {"code":"%s","face":"r5",
                 "types":[{"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(code);
    }
}
