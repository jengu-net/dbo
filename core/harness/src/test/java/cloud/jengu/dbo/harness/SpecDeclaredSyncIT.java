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
 * dbo#30 (REQ-DBO-SYNC-SPEC-DECLARED, REQ-DBO-SYNC-FULL-HISTORY-CATCH-UP):
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
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String codeSystemId;

    @BeforeAll
    void up() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        dir = Files.createTempDirectory("dbo-sync-tenants");
        provisioner = new LocalDatabasePerTenantProvisioner(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
        postgres.stop();
    }

    private static final String CANONICAL_TYPES = """
            [{"name":"CodeSystem","identity":"canonical"},
             {"name":"ValueSet","identity":"canonical"}]""";

    private static String dependentSpec(String code) {
        return """
                {"code":"%s","fhirVersion":"r4","types":%s,
                 "dependencies":[{"name":"ee","types":["CodeSystem"]}]}"""
                .formatted(code, CANONICAL_TYPES);
    }

    @Test
    @Order(1)
    void theZoneTenantHoldsContentFromBeforeAnyDependentExists() throws Exception {
        Files.writeString(dir.resolve("ee.json"),
                """
                {"code":"ee","fhirVersion":"r4","types":%s}""".formatted(CANONICAL_TYPES));
        manager.scanOnce();
        HttpResponse<String> created = post(manager.baseUrl("ee") + "/CodeSystem",
                """
                {"resourceType":"CodeSystem","url":"https://ee.ee/cs/colors",
                 "status":"active","content":"complete",
                 "concept":[{"code":"green"}]}""");
        assertEquals(201, created.statusCode(), created.body());
        String location = created.headers().firstValue("Location").orElseThrow();
        codeSystemId = location.substring(location.lastIndexOf('/') + 1);
        // an undeclared type in the same store — must never stream
        assertEquals(201, post(manager.baseUrl("ee") + "/ValueSet",
                """
                {"resourceType":"ValueSet","url":"https://ee.ee/vs/greens",
                 "status":"active"}""").statusCode());
    }

    @Test
    @Order(2)
    void aSpecDeclaredDependentCatchesUpFromFullHistory() throws Exception {
        Files.writeString(dir.resolve("hogwarts.json"), dependentSpec("hogwarts"));
        manager.scanOnce();
        manager.syncRound();
        HttpResponse<String> copy = get(manager.baseUrl("hogwarts") + "/CodeSystem/" + codeSystemId);
        assertEquals(200, copy.statusCode(), copy.body());
        assertTrue(copy.body().contains("green"), copy.body());
        // REQ-DBO-SYNC-DECLARED-ONLY at the spec grain: ValueSet undeclared
        HttpResponse<String> undeclared = get(
                manager.baseUrl("hogwarts") + "/ValueSet?_summary=count");
        assertTrue(undeclared.body().contains("\"total\":0"), undeclared.body());
    }

    @Test
    @Order(3)
    void aSecondDependentReceivesTheWholeStreamToo() throws Exception {
        Files.writeString(dir.resolve("beauxbatons.json"), dependentSpec("beauxbatons"));
        manager.scanOnce();
        manager.syncRound();
        // a shared ack cursor on the upstream feed would have starved this
        // tenant — hogwarts already acked past the event
        assertEquals(200,
                get(manager.baseUrl("beauxbatons") + "/CodeSystem/" + codeSystemId).statusCode());
        assertEquals(200,
                get(manager.baseUrl("hogwarts") + "/CodeSystem/" + codeSystemId).statusCode());
    }

    @Test
    @Order(4)
    void liveUpdatesKeepPropagating() throws Exception {
        HttpResponse<String> updated = http.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl("ee") + "/CodeSystem/" + codeSystemId))
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"CodeSystem","url":"https://ee.ee/cs/colors",
                                 "status":"active","content":"complete",
                                 "concept":[{"code":"green"},{"code":"crimson"}]}""")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(updated.statusCode() < 300, updated.body());
        manager.syncRound();
        HttpResponse<String> copy = get(manager.baseUrl("hogwarts") + "/CodeSystem/" + codeSystemId);
        assertTrue(copy.body().contains("crimson"), copy.body());
    }

    @Test
    @Order(5)
    void retractingTheSpecRemovesTheStream() throws Exception {
        Files.delete(dir.resolve("hogwarts.json"));
        manager.scanOnce();
        assertTrue(!manager.codes().contains("hogwarts"));
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
