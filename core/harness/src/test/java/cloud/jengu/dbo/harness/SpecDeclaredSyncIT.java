package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DBO-SYNC-SPEC-DECLARED, REQ-DBO-SYNC-FULL-HISTORY-CATCH-UP:
 * content dependencies declared in the tenant SPEC are wired by the runtime
 * manager itself — no code instantiates an engine here. A zone tenant holds
 * a CodeSystem from before any dependent existed; dependents appear by spec
 * file, catch up from full history, receive live updates, and never receive
 * undeclared types. Two dependents of the same upstream each get the whole
 * stream (per-dependent consumers — a shared ack cursor would split it).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpecDeclaredSyncIT {

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String codeSystemId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("SpecDeclaredSyncIT");
        dir = Files.createTempDirectory("dbo-sync-tenants");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    private static final String CANONICAL_TYPES = """
            [{"name":"CodeSystem","identity":"canonical","handling":"operational"},
             {"name":"ValueSet","identity":"canonical","handling":"operational"}]""";

    /**
     * The dependent declares the streamed type <b>replicated</b>: the zone
     * publishes it and nobody here may write it. That makes this test cover
     * the lane as well as the stream — the replication engine writes as the
     * source tenant, so a shield that refuses every other caller does not
     * refuse the one delivery it exists to protect.
     */
    private static final String REPLICATED_TYPES = """
            [{"name":"CodeSystem","identity":"canonical","handling":"replicated"},
             {"name":"ValueSet","identity":"canonical","handling":"operational"}]""";

    private static String dependentSpec(String code) {
        return """
                {"code":"%s","fhirVersion":"r4","types":%s,
                 "dependencies":[{"name":"sync-ee","types":["CodeSystem"]}]}"""
                .formatted(code, REPLICATED_TYPES);
    }

    @Test
    @Order(1)
    void theZoneTenantHoldsContentFromBeforeAnyDependentExists() throws Exception {
        Files.writeString(dir.resolve("sync-ee.json"),
                """
                {"code":"sync-ee","fhirVersion":"r4","types":%s}""".formatted(CANONICAL_TYPES));
        manager.scanOnce();
        HttpResponse<String> created = post(manager.baseUrl("sync-ee") + "/CodeSystem",
                """
                {"resourceType":"CodeSystem","url":"https://ee.ee/cs/colors",
                 "status":"active","content":"complete",
                 "concept":[{"code":"green"}]}""");
        assertEquals(201, created.statusCode(), created.body());
        String location = created.headers().firstValue("Location").orElseThrow();
        codeSystemId = location.substring(location.lastIndexOf('/') + 1);
        // an undeclared type in the same store — must never stream
        assertEquals(201, post(manager.baseUrl("sync-ee") + "/ValueSet",
                """
                {"resourceType":"ValueSet","url":"https://ee.ee/vs/greens",
                 "status":"active"}""").statusCode());
    }

    @Test
    @Order(2)
    void aSpecDeclaredDependentCatchesUpFromFullHistory() throws Exception {
        Files.writeString(dir.resolve("sync-hogwarts.json"), dependentSpec("sync-hogwarts"));
        manager.scanOnce();
        String copy = awaitCopy("sync-hogwarts", "green");
        assertTrue(copy.contains("$lookup → 200"), copy);
        // REQ-DBO-SYNC-DECLARED-ONLY at the spec grain: ValueSet undeclared
        HttpResponse<String> undeclared = get(
                manager.baseUrl("sync-hogwarts") + "/ValueSet?_summary=count");
        assertTrue(undeclared.body().contains("\"total\":0"), undeclared.body());
    }

    @Test
    @Order(3)
    void aSecondDependentReceivesTheWholeStreamToo() throws Exception {
        Files.writeString(dir.resolve("sync-beauxbatons.json"), dependentSpec("sync-beauxbatons"));
        manager.scanOnce();
        // a shared ack cursor on the upstream feed would have starved this
        // tenant — hogwarts already acked past the event
        awaitCopy("sync-beauxbatons", "green");
        assertEquals(200,
                get(manager.baseUrl("sync-hogwarts") + "/CodeSystem/" + codeSystemId).statusCode());
    }

    @Test
    @Order(4)
    void liveUpdatesKeepPropagating() throws Exception {
        HttpResponse<String> updated = http.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl("sync-ee") + "/CodeSystem/" + codeSystemId))
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"CodeSystem","url":"https://ee.ee/cs/colors",
                                 "status":"active","content":"complete",
                                 "concept":[{"code":"green"},{"code":"crimson"}]}""")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(updated.statusCode() < 300, updated.body());
        assertTrue(awaitCopy("sync-hogwarts", "crimson").contains("$lookup → 200"),
                "an update's new concept never reached the dependent's native form");
    }

    /**
     * The feed only publishes events its snapshot considers settled, so a
     * single round after a write proves nothing — poll until the copy
     * carries what we are waiting for (the timing-immunity rule).
     */
    /**
     * Waits until the dependent can ANSWER for a code, not until its stored
     * bytes mention one (REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES).
     *
     * <p>A received CodeSystem is stored the way this store keeps one — a shell
     * whose concepts live in the native form — so the copy's payload never
     * contains a concept, at either end. Asking $lookup is what proves the
     * grain survived the hop: an answer can only come from concepts that
     * arrived and were rebuilt here.
     */
    private String awaitCopy(String tenant, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            manager.syncRound();
            HttpResponse<String> copy =
                    get(manager.baseUrl(tenant) + "/CodeSystem/" + codeSystemId);
            if (copy.statusCode() == 200) {
                HttpResponse<String> answer = get(manager.baseUrl(tenant)
                        + "/CodeSystem/$lookup?system=https://ee.ee/cs/colors&code=" + expected);
                body = copy.body() + "\n$lookup → " + answer.statusCode() + " " + answer.body();
                if (answer.statusCode() == 200) {
                    return body;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError(tenant + " cannot answer for '" + expected + "': " + body);
    }

    @Test
    @Order(5)
    void retractingTheSpecRemovesTheStream() throws Exception {
        Files.delete(dir.resolve("sync-hogwarts.json"));
        manager.scanOnce();
        assertTrue(!manager.codes().contains("sync-hogwarts"));
        // the retracted tenant's stream is gone; the remaining one still rounds
        manager.syncRound();
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
