package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantState;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this deployment does about its tenants is somebody's work, and it has a
 * record (#74, ADR 0061).
 *
 * <p>`/runtime/tenants` answers what is true now; nothing answered what
 * happened, and nobody was the actor — "who retracted that tenant?" had no
 * answer, because the actor was a process reading a directory. The management
 * tenant is where that history lives, and it is a tenant like the others: an
 * ordinary store, with ordinary records in it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantManagementIsRecordedIT {

    private static final String OPS_TOKEN = "ops-token-for-this-deployment";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static Path managementSpec;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String jdbcBase;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        String jdbcUrl = SharedPostgres.urlFor("TenantManagementIsRecordedIT");
        jdbcBase = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        dir = Files.createTempDirectory("dbo-tenants-managed");
        // Deployment configuration, not a file in the watched directory: the
        // scan loop that retracts tenants must not be able to retract the
        // thing recording retractions.
        managementSpec = Files.createTempDirectory("dbo-management").resolve("haldur.json");
        Files.writeString(managementSpec, spec("haldur", "r4"));

        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        manager.serveRuntimeState(OPS_TOKEN);
        manager.manages(managementSpec);

        Files.writeString(dir.resolve("teenib.json"), spec("teenib", "r4"));
        // declared, and this container serves no such version: a spec somebody
        // has to change, which is a person's and not a clock's
        Files.writeString(dir.resolve("kukkunud.json"), spec("kukkunud", "seitsmes"));
        Files.writeString(dir.resolve("katki.json"), "this is not a spec at all");
        UntilServed.scan(manager, up -> up.contains("teenib"));
        manager.scanOnce();
    }

    @AfterAll
    void down() {
        Caller.clear();
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    private static String spec(String code, String version) {
        return """
                {"code":"%s","fhirVersion":"%s","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, version);
    }

    /** The management tenant's own store, read the way anything reads a tenant's records. */
    private static Runs managementRuns() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcBase + "tenant_haldur");
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return new Runs(new PgObjectStore(ds, WorkModel.registrations()));
    }

    private static Run serving() {
        return managementRuns().byKey(TenantRuntimeManager.TENANT_PROCESS + "/"
                + TenantRuntimeManager.SERVE_STEP + "/deployment").orElseThrow(
                        () -> new AssertionError("the deployment kept no account of its tenants"));
    }

    @Test
    @Order(1)
    @DisplayName("the management tenant is a tenant, and the scan that retracts tenants "
            + "does not retract it")
    void theManagementTenantIsATenantAndSurvivesTheScan() {
        assertTrue(manager.codes().contains("haldur"));
        assertEquals(TenantState.State.SERVING, manager.tenantStates().stream()
                .filter(state -> state.code().equals("haldur")).findFirst().orElseThrow().state());

        manager.scanOnce();

        assertTrue(manager.codes().contains("haldur"),
                "it is not in the watched directory, so the undeclared-means-retract rule "
                        + "would have taken down the thing recording retractions");
    }

    @Test
    @Order(2)
    @DisplayName("a tenant that will not come up is a card in the management tenant, with "
            + "the reason")
    void aFailedTenantIsSomebodysCard() {
        Run sweep = serving();
        assertEquals(RunKind.SWEEP, sweep.kind());
        assertEquals(2L, sweep.tally().get("serving"),
                "haldur and teenib serve; the other two do not: " + sweep.tally());

        List<Run> cards = managementRuns().items(sweep).stream()
                .filter(Run::needsAPerson).toList();
        assertTrue(cards.stream().anyMatch(card -> card.item().reference().equals("kukkunud")
                        && card.item().message().contains("seitsmes")),
                "the reason is what somebody needs in order to act: " + cards);
        assertTrue(cards.stream().anyMatch(card ->
                        card.item().reference().equals("spec:katki.json")),
                "a file that never parsed has no tenant to be a state of, so it is named by "
                        + "the file somebody has to open: " + cards);
    }

    @Test
    @Order(3)
    @DisplayName("a tenant retracted because its declaration disappeared says that, and its "
            + "data is untouched")
    void retractionSaysWhyAndErasesNothing() throws Exception {
        Files.delete(dir.resolve("teenib.json"));
        manager.scanOnce();

        assertFalse(manager.codes().contains("teenib"));
        List<Run> retractions = retractionsOf("teenib");
        assertEquals(1, retractions.size(), "one retraction, once: " + retractions);
        assertTrue(retractions.get(0).assignment().note().contains("declaration was withdrawn"),
                "nobody retracted it, and saying so is the answer: "
                        + retractions.get(0).assignment());

        // Retraction is not erasure. The scan path cannot reach erasure, and
        // the proof is that the data is still there afterwards.
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcBase + "tenant_teenib");
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            assertTrue(st.executeQuery("SELECT 1 FROM state.r4_data LIMIT 1") != null,
                    "a retracted tenant's database is still there — only an operator erases");
        }
    }

    @Test
    @Order(4)
    @DisplayName("an erasure names the operator who asked for it, and a sweep cannot ask")
    void erasureIsAnOperatorActAndSaysWho() {
        Caller.set("kaja@haldur.ee");
        try {
            manager.erase("teenib");
        } finally {
            Caller.clear();
        }

        List<Run> erasures = managementRuns().holding(Holder.NOBODY).stream()
                .filter(run -> TenantRuntimeManager.ERASE_STEP.equals(run.step()))
                .filter(run -> run.key().contains("/teenib/"))
                .toList();
        assertEquals(1, erasures.size(), "the erasure is on the record: " + erasures);
        assertEquals("kaja@haldur.ee", erasures.get(0).assignment().executor().name(),
                "a process reading a directory cannot be the actor for this one");

        assertThrows(IllegalArgumentException.class, () -> manager.erase("haldur"),
                "erasing the management tenant would erase the account of what was erased");
    }

    @Test
    @Order(5)
    @DisplayName("deployment liveness does not go through the management tenant")
    void livenessIsDeploymentLevel() throws Exception {
        HttpResponse<String> answer = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + manager.port()
                        + "/runtime/tenants"))
                        .header("Authorization", "Bearer " + OPS_TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("haldur"), answer.body());
        // It answers from runtime state, which is why it still answers when the
        // tenant that records the history cannot be written to at all.
        assertFalse(answer.body().contains("\"runs\""), answer.body());
    }

    private static List<Run> retractionsOf(String code) {
        return managementRuns().holding(Holder.NOBODY).stream()
                .filter(run -> TenantRuntimeManager.RETRACT_STEP.equals(run.step()))
                .filter(run -> run.key().contains("/" + code + "/"))
                .toList();
    }
}
