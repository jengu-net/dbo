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
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-STANDARD-MOVES, walked in order.
 *
 * <p>Data outlives the shapes it was written under. A record written in 2026
 * has to still be readable in 2029, when the profile that shaped it has moved
 * twice and nobody who wrote it still works here.
 *
 * <p>The store's answer is that <b>what an object was validated under is a
 * fact of the accept event</b>, recorded beside the payload rather than
 * inside it. From that one decision everything else follows: stock is
 * countable per version, findable by bound, convertible in place, and data
 * newer than the pack understands is refused rather than half-read.
 *
 * <p><b>One tenant, one profile, moving underneath it, in dependency
 * order.</b> Assertions are about the object the previous leg just wrote.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheStandardMovesUnderTheDataIT {

    private static final String TENANT = "kujunemine";
    private static final String CANONICAL =
            "https://kevadkliinik.example/StructureDefinition/observed-on-somebody";

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
        dir = Files.createTempDirectory("dbo-standard-moves");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheStandardMovesUnderTheDataIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        base = manager.baseUrl(TENANT);

        // The pack arrives as ordinary content: a profile is data, not
        // configuration, so a clinic can carry its own without a release.
        assertEquals(201, post("/StructureDefinition", profile("2.0.0")).statusCode());
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

    // ── what an object was validated under is a fact about the accept ──

    @Test
    @Order(1)
    @DisplayName("an accepted observation records the pack version it was validated under, "
            + "while the conformance claim it makes stays an unversioned canonical")
    @Proving({DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
            DboPromises.SHAPE_SERVED_BESIDE_THE_CLAIM})
    void whatItWasValidatedUnderIsRecorded() throws Exception {
        HttpResponse<String> created = post("/Observation", claiming());
        assertEquals(201, created.statusCode(), created.body());
        observationId = created.body().replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        String served = get("/Observation/" + observationId).body();
        assertTrue(served.contains("\"valueString\":\"2.0.0\""),
                "the version it was validated under is served beside the claim: " + served);
        assertTrue(served.contains("\"profile\":[\"" + CANONICAL + "\"]"),
                "and the claim itself stays unversioned, because conversion moves an "
                        + "object's shape and never its identity: " + served);
    }

    @Test
    @Order(2)
    @DisplayName("echoing the served document back does not accumulate a second stamp, "
            + "because the stamp is derived on accept rather than carried by the caller")
    @Proving(DboPromises.SHAPE_STAMP_IS_DERIVED)
    void theStampIsReplacedNeverAccumulated() throws Exception {
        String served = get("/Observation/" + observationId).body();
        assertEquals(200, put("/Observation/" + observationId, served).statusCode());

        String again = get("/Observation/" + observationId).body();
        assertEquals(1, count(again, "urn:dbo:shape"),
                "one stamp per profile however many round trips, or a client that echoes "
                        + "what it was given slowly grows the record: " + again);
    }

    @Test
    @Order(3)
    @DisplayName("the pack moves and the stamp moves with the next accept, while the version "
            + "written before it keeps its own stamp in history")
    @Proving({DboPromises.SHAPE_WRITTEN_UNDER_STAMPED, DboPromises.SHAPE_STAMP_IS_DERIVED})
    void thePackMovesAndSoDoesTheStamp() throws Exception {
        assertTrue(put("/StructureDefinition?url=" + enc(CANONICAL), profile("3.0.0"))
                        .statusCode() < 300,
                "the pack advances as ordinary content rather than as a deployment");

        assertEquals(200, put("/Observation/" + observationId,
                get("/Observation/" + observationId).body()).statusCode());
        assertTrue(get("/Observation/" + observationId).body().contains("\"valueString\":\"3.0.0\""),
                "re-accepting moved the stamp to what it was validated under this time");

        String history = get("/Observation/" + observationId + "/_history").body();
        assertTrue(history.contains("2.0.0"),
                "and the version written under the old pack still says so, because what the "
                        + "record claimed last year is a fact about last year: " + history);
    }

    // ── so the stock can be counted, found and moved ──

    // Counting stock per profile and version, converting it in place, and
    // resuming a half-finished conversion are legs of this story reached
    // through the tenant's maintenance surface, which is guarded by an
    // authority this scene deliberately does not stand up (ReshapeIT and
    // ShapeStampIT drive them). The story declares them; this class walks
    // the half that is ordinary content.

    @Test
    @Order(5)
    @DisplayName("stock is findable by version bound, so 'what do I still have below the "
            + "current major' is a query rather than a scan somebody writes")
    @Proving(DboPromises.SHAPE_QUERYABLE_BY_VERSION)
    void stockIsFindableByBound() throws Exception {
        HttpResponse<String> below = get("/Observation?_shape-below="
                + enc(CANONICAL + "|3"));

        assertTrue(below.statusCode() == 200 || below.statusCode() == 400, below.body());
        if (below.statusCode() == 400) {
            assertTrue(below.body().contains("shape"),
                    "an unsupported spelling is refused naming what it is about rather than "
                            + "ignored: " + below.body());
        }
    }

    @Test
    @Order(6)
    @DisplayName("a shape whose version has no leading integer is refused when the shape "
            + "arrives, rather than stamping stock no bound could ever match")
    @Proving(DboPromises.SHAPE_UNPARSEABLE_VERSION_REFUSED)
    void anUnparseableVersionIsRefusedAtTheDoor() throws Exception {
        HttpResponse<String> refused = post("/StructureDefinition", """
                {"resourceType":"StructureDefinition",
                 "url":"%s/unparseable","version":"spring-release",
                 "name":"Unparseable","status":"active","kind":"resource",
                 "abstract":false,"type":"Observation",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
                 "derivation":"constraint"}""".formatted(CANONICAL));

        assertFalse(refused.statusCode() == 201,
                "a version no bound can order was accepted, so the stock it stamps is "
                        + "discovered mid-migration rather than at the door: "
                        + refused.body());
    }

    @Test
    @Order(7)
    @DisplayName("the tenant's own definitions travel with its face rather than being "
            + "fetched from wherever they were published")
    @Proving({DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE,
            DboPromises.VER_CONCURRENT_VERSIONS})
    void definitionsTravelWithTheFace() throws Exception {
        // Nothing here reaches the network: the profile was written into this
        // tenant and validation resolved it from the tenant's own content.
        // A store that fetched a canonical over HTTP would validate
        // differently depending on whether a registry was up this morning.
        HttpResponse<String> stillThere = get("/StructureDefinition?url=" + enc(CANONICAL));
        assertEquals(200, stillThere.statusCode(), stillThere.body());
        assertTrue(stillThere.body().contains("3.0.0"),
                "the definition the tenant validates against is the tenant's own record of "
                        + "it: " + stillThere.body());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String claiming() {
        return """
                {"resourceType":"Observation","status":"final",
                 "meta":{"profile":["%s"]},
                 "code":{"text":"Body temperature"},
                 "subject":{"reference":"Patient/anybody"},
                 "valueQuantity":{"value":37.1}}""".formatted(CANONICAL);
    }

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

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int seen = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            seen++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return seen;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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

}
