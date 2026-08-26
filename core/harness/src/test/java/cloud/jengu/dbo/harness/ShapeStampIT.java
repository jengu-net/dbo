package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The written-under stamp (#131): every accepted object records the versions
 * of the pack profiles it was validated against, as a fact of the accept
 * event — stored beside the payload, served in {@code meta} as
 * {@code urn:dbo:shape}, per-version in history, replaced never accumulated.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShapeStampIT {

    private static final String CANONICAL =
            "https://sonavara.example/StructureDefinition/observed-on-somebody";

    private static String profile(String version) {
        return """
                {"resourceType":"StructureDefinition",
                 "url":"%s","version":"%s",
                 "name":"ObservedOnSomebody","status":"active","kind":"resource",
                 "abstract":false,"type":"Observation",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
                .formatted(CANONICAL, version);
    }

    private static final String CLAIMING = """
            {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
             "subject":{"display":"somebody"},
             "meta":{"profile":["%s"]}}""".formatted(CANONICAL);

    private static final String PLAIN = """
            {"resourceType":"Observation","status":"final","code":{"text":"pulse"}}""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static String observationId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-shape");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ShapeStampIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("kujud.json"), """
                {"code":"kujud","fhirVersion":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "kujud");
        base = manager.baseUrl("kujud");
        assertEquals(201, post("/StructureDefinition", profile("2.0.0")).statusCode());
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

    @Test
    @Order(1)
    @DisplayName("an accepted object is stamped with the pack version it was validated under, "
            + "and meta.profile stays the unversioned canonical")
    void acceptStamps() throws Exception {
        HttpResponse<String> created = post("/Observation", CLAIMING);
        assertEquals(201, created.statusCode(), created.body());
        observationId = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        String served = get("/Observation/" + observationId).body();
        assertTrue(served.contains("\"url\":\"urn:dbo:shape\""), served);
        assertTrue(served.contains("\"valueCanonical\":\"" + CANONICAL + "\""), served);
        assertTrue(served.contains("\"valueString\":\"2.0.0\""), served);
        assertTrue(served.contains("\"profile\":[\"" + CANONICAL + "\"]"),
                "the conformance claim stays an unversioned canonical: " + served);
    }

    @Test
    @Order(2)
    @DisplayName("an echoed round trip is stamp-stable: replaced, never accumulated")
    void echoIsStampStable() throws Exception {
        String served = get("/Observation/" + observationId).body();
        HttpResponse<String> updated = put("/Observation/" + observationId, served);
        assertEquals(200, updated.statusCode(), updated.body());

        String again = get("/Observation/" + observationId).body();
        assertEquals(1, count(again, "urn:dbo:shape"),
                "one stamp per profile, however many round trips: " + again);
        assertEquals(1, count(again, "\"valueString\":\"2.0.0\""), again);
    }

    @Test
    @Order(3)
    @DisplayName("a pack bump moves the stamp with the next accept; the old version keeps "
            + "its own stamp in history")
    void packBumpMovesTheStamp() throws Exception {
        // The pack's shape advances: same canonical, new version, as data.
        assertTrue(put("/StructureDefinition?url=" + CANONICAL, profile("3.0.0"))
                .statusCode() < 300, "the pack updates as ordinary content");

        String served = get("/Observation/" + observationId).body();
        HttpResponse<String> reaccepted = put("/Observation/" + observationId, served);
        assertEquals(200, reaccepted.statusCode(), reaccepted.body());
        assertTrue(get("/Observation/" + observationId).body()
                .contains("\"valueString\":\"3.0.0\""), "the stamp moved with re-validation");

        String history = get("/Observation/" + observationId + "/_history").body();
        assertTrue(history.contains("\"valueString\":\"2.0.0\""),
                "the version written under 2.0.0 still says so: " + history);
        assertTrue(history.contains("\"valueString\":\"3.0.0\""), history);
    }

    @Test
    @Order(4)
    @DisplayName("no declared pack profile, no stamp — accepted, unstamped")
    void undeclaredIsUnstamped() throws Exception {
        HttpResponse<String> created = post("/Observation", PLAIN);
        assertEquals(201, created.statusCode(), created.body());
        String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertFalse(get("/Observation/" + id).body().contains("urn:dbo:shape"),
                "the store stamps only shapes the pack publishes a version for");
    }

    @Test
    @Order(5)
    @DisplayName("a reindex rebuilds the shape dimension from the row, losing nothing")
    void reindexKeepsTheStamp() throws Exception {
        var runtime = manager.runtime("kujud").orElseThrow();
        // the engine rebuild every personality supports: envelope from row
        int rebuilt = runtime.engine().rebuildEnvelopes("Observation");
        assertTrue(rebuilt >= 2, "the rebuild touched the observations: " + rebuilt);
        assertTrue(get("/Observation/" + observationId).body()
                .contains("\"valueString\":\"3.0.0\""), "the stamp survived the reindex");
    }

    @Test
    @Order(6)
    @DisplayName("the stamp travels the feed beside the payload version, so a mirrored copy "
            + "keeps the stamp of the store that validated it")
    void stampRidesTheWire() throws Exception {
        var feed = manager.runtime("kujud").orElseThrow().feed();
        String cursor = null;
        java.util.List<String> stamped = null;
        for (var chunk = feed.read(null, 200); !chunk.items().isEmpty();
                chunk = feed.read(cursor, 200)) {
            for (var item : chunk.items()) {
                if (observationId.equals(item.objectId())
                        && item.shape() != null && !item.shape().isEmpty()) {
                    stamped = item.shape();
                }
            }
            cursor = chunk.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        assertTrue(stamped != null && stamped.stream().anyMatch(s -> s.endsWith("|3.0.0")),
                "the wire carries the written-under stamp: " + stamped);
    }

    // ---------------------------------------------------------- plumbing

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
