package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
 * Data newer than the pack understands: refused, loudly, rather than
 * read best-effort — a silent misreading is indistinguishable from a correct
 * read to whoever is holding the result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NewerDataRefusedIT {

    private static final String SHAPE = "https://sonavara.example/StructureDefinition/reading";
    private static final String WITHDRAWN = "https://sonavara.example/StructureDefinition/gone";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static String tooNew;
    static String unstamped;
    static String underWithdrawn;
    static String withdrawnShapeId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-toonew");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("NewerDataRefusedIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("uus.json"), """
                {"code":"uus","fhirVersion":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Basic","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "uus");
        base = manager.baseUrl("uus");

        // The pack stands at 3.0.0, and stock is written under it.
        assertEquals(201, post("/StructureDefinition", shape(SHAPE, "3.0.0")).statusCode());
        withdrawnShapeId = idOf(post("/StructureDefinition", shape(WITHDRAWN, "1.0.0")));
        tooNew = idOf(post("/Basic", note(SHAPE)));
        underWithdrawn = idOf(post("/Basic", note(WITHDRAWN)));
        unstamped = idOf(post("/Basic",
                """
                {"resourceType":"Basic","code":{"text":"plain"}}"""));
        assertTrue(get("/Basic/" + tooNew).body().contains("\"valueString\":\"3.0.0\""),
                "the stock starts stamped at what the pack then declared");
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
    @Proving({DboPromises.SHAPE_NEWER_DATA_REFUSED, DboPromises.SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER})
    @DisplayName("the pack rolls back below the stamp: the object is refused by id, with its "
            + "own answer naming all three facts")
    void refusedById() throws Exception {
        // The pack now carries an OLDER major than the stock was written
        // under — a store that has fallen behind data it already holds.
        assertTrue(put("/StructureDefinition?url="
                        + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8),
                shape(SHAPE, "2.0.0")).statusCode() < 300);

        HttpResponse<String> refused = get("/Basic/" + tooNew);
        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains(tooNew), refused.body());
        assertTrue(refused.body().contains("3.0.0") && refused.body().contains("2.0.0"),
                "the answer names the stamp and what the pack declares: " + refused.body());
        assertTrue(refused.body().contains("conflict"),
                "and it is its own answer, not a fault or a permission: " + refused.body());
    }

    @Test
    @Order(2)
    @Proving(DboPromises.SHAPE_NEWER_DATA_REFUSED)
    @DisplayName("a search whose answer would contain it is refused whole, never quietly short")
    void searchRefusedRatherThanShortened() throws Exception {
        HttpResponse<String> search = get("/Basic");
        assertEquals(409, search.statusCode(),
                "a short answer looks like an answer: " + search.body());
        assertTrue(search.body().contains(tooNew), search.body());
    }

    @Test
    @Order(3)
    @Proving(DboPromises.SHAPE_NEWER_DATA_REFUSED)
    @DisplayName("what is not demonstrably ahead still reads: unstamped stock, and stock "
            + "under a shape the pack no longer carries at all")
    void onlyDemonstrablyAheadIsRefused() throws Exception {
        assertEquals(200, get("/Basic/" + unstamped).statusCode(),
                "unstamped is not the same as too new");

        // The rule, pinned here so the two cannot drift: a stamp outlives
        // the pack version that made it, so a withdrawn shape is not a
        // conflict — only a pack declaring an OLDER version is.
        assertTrue(delete("/StructureDefinition/" + withdrawnShapeId).statusCode() < 400,
                "the pack drops the shape entirely");
        assertEquals(200, get("/Basic/" + underWithdrawn).statusCode(),
                "a shape the pack no longer carries leaves its stock readable");
    }

    // ---------------------------------------------------------- plumbing

    private static String shape(String url, String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"Shape%s","status":"active","kind":"resource","abstract":false,
                 "type":"Basic",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(url, version, Math.abs(url.hashCode()));
    }

    private static String note(String profile) {
        return """
                {"resourceType":"Basic","code":{"text":"note"},
                 "meta":{"profile":["%s"]}}""".formatted(profile);
    }

    private static String idOf(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        return created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
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

    private static HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
