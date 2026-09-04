package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.sync.DirectoryConfigSource;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantDeclarationModel;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this deployment was told to serve, asked of the store rather than of
 * somebody with a shell on the node.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ADeploymentRecordsWhatItWasToldToServeIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static Path managementSpec;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String management;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-declared");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADeploymentRecordsWhatItWasToldToServeIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        // Declared by configuration and not by a file in the watched directory
        // — so it is not one of the declarations it records.
        managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        Files.writeString(managementSpec, spec("registry", "Observation"));
        management = manager.manages(managementSpec);
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

    private static String spec(String code, String... types) {
        StringBuilder declared = new StringBuilder();
        for (String type : types) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"internal\",\"handling\":\"operational\"}");
        }
        return "{\"code\":\"" + code + "\",\"face\":\"r4\",\"types\":[" + declared + "]}";
    }

    private List<StoredObject> declarations() {
        return manager.runtime(management).orElseThrow().engine()
                .select(Criteria.of(TenantDeclarationModel.TYPE));
    }

    private Run pass() {
        return new Runs(manager.runtime(management).orElseThrow().engine())
                .byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP + "/deployment")
                .orElseThrow(() -> new AssertionError("the deployment recorded no pass at all"));
    }

    @Test
    @Order(1)
    @Proving(DboPromises.TEN_A_DECLARATION_IS_A_RECORD)
    void whatWasDeclaredIsInTheManagingTenantsStore() throws Exception {
        Files.writeString(dir.resolve("declared-one.json"), spec("declared-one", "Observation"));
        Files.writeString(dir.resolve("declared-two.json"), spec("declared-two", "Observation"));
        UntilServed.scan(manager, "declared-one", "declared-two");

        assertEquals(2, declarations().size(),
                "two tenants were declared and the store says: " + declarations().size());
        assertEquals(2L, pass().tally().get("applied"));
        assertFalse(pass().needsAPerson(), "nothing here is anybody's to fix");

        String held = new String(declarations().stream()
                .filter(record -> new String(record.payload(), StandardCharsets.UTF_8)
                        .contains("declared-one"))
                .findFirst().orElseThrow().payload(), StandardCharsets.UTF_8);
        assertEquals(spec("declared-one", "Observation"), held,
                "the declaration is what somebody wrote, not a projection of it");
    }

    /** A changed declaration replaces the one on record, keyed by the tenant it names. */
    @Test
    @Order(2)
    @Proving(DboPromises.TEN_A_DECLARATION_IS_A_RECORD)
    void aChangedDeclarationReplacesTheOneOnRecord() throws Exception {
        Files.writeString(dir.resolve("declared-two.json"),
                spec("declared-two", "Observation", "Condition"));
        manager.scanOnce();

        assertEquals(2, declarations().size(), "a change is not a second declaration");
        assertTrue(declarations().stream()
                        .map(record -> new String(record.payload(), StandardCharsets.UTF_8))
                        .anyMatch(payload -> payload.contains("Condition")),
                "the store still holds what the file said before it changed; tally="
                        + pass().tally());
    }

    /**
     * A file that will not parse is one card naming it, and the tenants
     * declared beside it are recorded regardless.
     */
    @Test
    @Order(3)
    @Proving({DboPromises.TEN_A_DECLARATION_IS_A_RECORD,
            DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP})
    void oneUnreadableDeclarationDoesNotTakeTheOthersWithIt() throws Exception {
        Files.writeString(dir.resolve("declared-broken.json"), "not a tenant spec at all");
        manager.scanOnce();

        assertEquals(2, declarations().size(), "the readable two are still recorded");
        assertTrue(pass().needsAPerson(), "somebody has to fix that file");
        assertTrue(new Runs(manager.runtime(management).orElseThrow().engine())
                        .items(pass()).stream()
                        .anyMatch(card -> card.item().reference().equals("declared-broken.json")),
                "and the card names the file they have to open");
    }

    /** A declaration nobody makes any more stops being on record. */
    @Test
    @Order(4)
    @Proving(DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED)
    void aWithdrawnDeclarationLeavesTheRecord() throws Exception {
        Files.delete(dir.resolve("declared-broken.json"));
        Files.delete(dir.resolve("declared-two.json"));
        manager.scanOnce();

        assertEquals(1, declarations().size(),
                "the declaration nobody makes any more is still on record");
        assertEquals(1L, pass().tally().get("withdrawn"));
        assertFalse(manager.codes().contains("declared-two"),
                "the tenant is served from what was applied, and nothing declares it now");
        assertTrue(new String(declarations().get(0).payload(), StandardCharsets.UTF_8)
                        .contains("declared-one"),
                "and it took the right one away");
    }

    /**
     * The rule the whole delta rests on, and the one that used to be the other
     * way round: a source that cannot be read is not a deployment declaring
     * nothing. Absence in a listing was retraction, so a vanished mount took
     * every tenant on this node down with it. Now the records stand, the
     * tenants keep serving, and what is stale is said out loud.
     */
    @Test
    @Order(5)
    @Proving({DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED,
            DboPromises.TEN_SERVED_FROM_WHAT_WAS_APPLIED})
    void anUnreadableSourceRetractsNothing() throws Exception {
        Path moved = dir.resolveSibling(dir.getFileName() + "-moved");
        Files.move(dir, moved);
        try {
            Set<String> serving = manager.scanOnce();

            assertTrue(serving.contains("declared-one"),
                    "a mount that vanished took a live tenant down with it");
            assertEquals(1, declarations().size(),
                    "an unreadable source took a live declaration off the record");
            assertTrue(manager.troubles().containsKey("source:declarations"),
                    "and nothing said the declarations had stopped moving: "
                            + manager.troubles());
        } finally {
            Files.move(moved, dir);
        }
        manager.scanOnce();
        assertFalse(manager.troubles().containsKey("source:declarations"),
                "the mount came back and the ledger still says it is gone");
    }

    /**
     * And the source itself refuses rather than reading empty, which is what
     * the rule above rests on wherever the reader is called from.
     */
    @Test
    @Order(6)
    @Proving(DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE)
    void aDirectoryThatCannotBeReadIsNotADirectoryDeclaringNothing() {
        assertThrows(RuntimeException.class,
                () -> new DirectoryConfigSource(dir.resolve("nowhere"),
                        TenantDeclarationModel.TYPE, ".json").fetch());
    }
}
