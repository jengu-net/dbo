package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conditional update: this resource, identified by its canonical, should exist
 * with these contents (R4 §3.1.0.7.1).
 *
 * <p>A catalogue's job is to make the store match configuration. Conditional
 * CREATE cannot express that — it is a no-op when the resource is present, so
 * an item whose definition changed upstream keeps its old contents while the
 * caller is told it worked. That is worse than a refusal, because nothing
 * looks wrong.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionalUpdateIT {

    private static final String CANONICAL =
            "https://jengu.cloud/ActivityDefinition/dermatologist-consult";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-condupd");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ConditionalUpdateIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("kataloog.json"), """
                {"code":"kataloog","face":"r4","types":[
                  {"name":"ActivityDefinition","identity":"canonical","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("kataloog"));
        base = manager.baseUrl("kataloog");
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

    private static String definition(String title) {
        return """
                {"resourceType":"ActivityDefinition","status":"active","url":"%s","title":"%s"}"""
                .formatted(CANONICAL, title);
    }

    private static String condition() {
        return "url=" + URLEncoder.encode(CANONICAL, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String catalogue() throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create(base + "/ActivityDefinition?" + condition())).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @Proving(DboPromises.CORE_CONDITIONAL_UPSERT)
    void absentItCreatesAndPresentItReplaces() throws Exception {
        HttpResponse<String> created = put("/ActivityDefinition?" + condition(),
                definition("Dermatologist consult"));
        assertEquals(201, created.statusCode(), created.body());

        HttpResponse<String> replaced = put("/ActivityDefinition?" + condition(),
                definition("Dermatology consultation"));
        assertEquals(200, replaced.statusCode(),
                "present, the canonical is REPLACED rather than left alone: " + replaced.body());

        String all = catalogue();
        assertTrue(all.contains("Dermatology consultation"), all);
        assertTrue(!all.contains("Dermatologist consult"),
                "the old contents must be gone, not shadowed: " + all);
        assertEquals(1, all.split("\"resourceType\":\"ActivityDefinition\"", -1).length - 1,
                "one canonical, one record: " + all);
    }

    @Test
    @Proving({DboPromises.CORE_CONDITIONAL_UPSERT, DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY})
    void aCatalogueSyncsAsABatchWithNothingRejected() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            String url = "https://jengu.cloud/ActivityDefinition/service-" + i;
            entries.append(i > 0 ? "," : "").append("""
                    {"resource":{"resourceType":"ActivityDefinition","status":"active",
                      "url":"%s","title":"Service %d"},
                     "request":{"method":"PUT","url":"ActivityDefinition?url=%s"}}"""
                    .formatted(url, i, URLEncoder.encode(url, StandardCharsets.UTF_8)));
        }
        String bundle = "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"entry\":["
                + entries + "]}";

        // twice: the first run creates, the second must replace rather than
        // refuse or duplicate — which is what a sync round does every time
        for (int round = 1; round <= 2; round++) {
            HttpResponse<String> answered = http.send(HttpRequest.newBuilder(URI.create(base))
                            .header("Content-Type", "application/fhir+json")
                            .POST(HttpRequest.BodyPublishers.ofString(bundle)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, answered.statusCode(), answered.body());
            assertEquals(0, answered.body().split("\"outcome\"", -1).length - 1,
                    "round " + round + " must reject nothing: " + answered.body());
            assertEquals(25, answered.body().split("\"status\":\"20", -1).length - 1,
                    "every entry answers 200 or 201 in round " + round + ": " + answered.body());
        }

        String count = http.send(HttpRequest.newBuilder(URI.create(
                        base + "/ActivityDefinition?_summary=count")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(count.contains("\"total\":26"),
                "25 catalogue items plus the one from the other test, not 51: " + count);
    }
}
