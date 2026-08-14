package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4Terminology;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dbo#12 proof matrix: the FHIR REST surface over real HTTP. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestSurfaceIT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static FhirHttpServer r4Server;
    static FhirHttpServer r5Server;
    static R4Terminology terminologyIngest;
    static String base;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(postgres.getJdbcUrl());
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        // production shape: the base URL is deployment config (the public URL);
        // the test reserves ports up front so stores frame links correctly
        int r4Port = freePort();
        int r5Port = freePort();
        base = "http://127.0.0.1:" + r4Port + "/fhir";
        String r5Base = "http://127.0.0.1:" + r5Port + "/fhir";

        R4Personality p4 = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.canonical("CodeSystem"),
                FhirTypeConfig.canonical("ValueSet")));
        PgObjectStore engine4 = new PgObjectStore(pg, p4.registrations());
        R4Store store4 = new R4Store(engine4, p4, base);
        R4Terminology terminology = new R4Terminology(engine4, p4, new TerminologyStore(pg));
        terminologyIngest = terminology;
        r4Server = new FhirHttpServer(store4, terminology, "127.0.0.1", r4Port, "/fhir");

        R5Personality p5 = new R5Personality(List.of(
                FhirTypeConfig.internal("SubscriptionTopic")));
        R5Store store5 = new R5Store(new PgObjectStore(pg, p5.registrations()), p5, r5Base);
        r5Server = new FhirHttpServer(store5, null, "127.0.0.1", r5Port, "/fhir");
    }

    @AfterAll
    void down() {
        r4Server.close();
        r5Server.close();
        postgres.stop();
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------- helpers

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/fhir+json");
    }

    private static String patient(String eid, String family) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""".formatted(EID, eid, family);
    }

    // ------------------------------------------------------------ scenarios

    /** POST 201 → GET 200 → PUT If-Match 200 → stale 412 → DELETE 204 → GET 404. */
    @Test
    void createReadUpdateDeleteRoundTripWithEtags() throws Exception {
        HttpResponse<String> created = send(req(base + "/Patient")
                .POST(HttpRequest.BodyPublishers.ofString(patient("36606060601", "RestOne"))).build());
        assertEquals(201, created.statusCode());
        assertEquals("W/\"1\"", created.headers().firstValue("ETag").orElseThrow());
        String location = created.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith(base + "/Patient/"));

        HttpResponse<String> read = send(req(location).GET().build());
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("RestOne"));

        HttpResponse<String> updated = send(req(location)
                .header("If-Match", "W/\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(patient("36606060601", "RestOne Renamed"))).build());
        assertEquals(200, updated.statusCode());
        assertEquals("W/\"2\"", updated.headers().firstValue("ETag").orElseThrow());

        HttpResponse<String> stale = send(req(location)
                .header("If-Match", "W/\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(patient("36606060601", "Never"))).build());
        assertEquals(412, stale.statusCode());
        assertTrue(stale.body().contains("OperationOutcome"));

        HttpResponse<String> deleted = send(req(location).DELETE().build());
        assertEquals(204, deleted.statusCode());
        assertEquals(404, send(req(location).GET().build()).statusCode());
    }

    /** If-None-Exist: 201 then 200, same id (bootstrap workhorse over HTTP). */
    @Test
    void conditionalCreateViaIfNoneExist() throws Exception {
        String condition = "identifier=" + EID + "|47101010101";
        HttpResponse<String> first = send(req(base + "/Patient")
                .header("If-None-Exist", condition)
                .POST(HttpRequest.BodyPublishers.ofString(patient("47101010101", "CondRest"))).build());
        HttpResponse<String> second = send(req(base + "/Patient")
                .header("If-None-Exist", condition)
                .POST(HttpRequest.BodyPublishers.ofString(patient("47101010101", "CondRest AGAIN"))).build());
        assertEquals(201, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(first.headers().firstValue("Location"), second.headers().firstValue("Location"));
    }

    /** Search pages through ABSOLUTE link[next] urls; no duplicates. */
    @Test
    void searchPagesThroughAbsoluteNextLinks() throws Exception {
        for (int i = 0; i < 5; i++) {
            send(req(base + "/Patient").POST(HttpRequest.BodyPublishers.ofString(
                    patient("5090909090" + i, "Paged"))).build());
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        String url = base + "/Patient?family=paged&_count=2";
        int pages = 0;
        while (url != null) {
            HttpResponse<String> page = send(req(url).GET().build());
            assertEquals(200, page.statusCode());
            pages++;
            Matcher m = Pattern.compile("\"fullUrl\":\"[^\"]+/Patient/([^\"]+)\"").matcher(page.body());
            while (m.find()) {
                assertTrue(ids.add(m.group(1)), "duplicate across pages: " + m.group(1));
            }
            Matcher next = Pattern.compile("\"url\":\"(http[^\"]+_cursor=[^\"]+)\"").matcher(page.body());
            url = next.find() ? next.group(1) : null;
        }
        assertEquals(5, ids.size());
        assertEquals(3, pages);
    }

    /** Strictness over HTTP: unknown param, repeated param, validation, conflict. */
    @Test
    void errorsAreOperationOutcomesWithProperStatusCodes() throws Exception {
        assertEquals(400, send(req(base + "/Patient?favourite-color=blue").GET().build()).statusCode());
        HttpResponse<String> repeated = send(req(base + "/Patient?family=a&family=b").GET().build());
        assertEquals(400, repeated.statusCode());
        assertTrue(repeated.body().contains("repeated query parameter"));

        HttpResponse<String> invalid = send(req(base + "/Observation")
                .POST(HttpRequest.BodyPublishers.ofString("{\"resourceType\":\"Observation\"}")).build());
        assertEquals(422, invalid.statusCode());
        assertTrue(invalid.body().contains("OperationOutcome"));

        send(req(base + "/Patient").POST(HttpRequest.BodyPublishers.ofString(
                patient("58203030303", "ConflictOwner"))).build());
        HttpResponse<String> conflict = send(req(base + "/Patient")
                .POST(HttpRequest.BodyPublishers.ofString(patient("58203030303", "Impostor"))).build());
        assertEquals(409, conflict.statusCode());
    }

    /** _history lists every version over HTTP. */
    @Test
    void historyReturnsAllVersions() throws Exception {
        HttpResponse<String> created = send(req(base + "/Patient")
                .POST(HttpRequest.BodyPublishers.ofString(patient("69404040404", "HistOne"))).build());
        String location = created.headers().firstValue("Location").orElseThrow();
        send(req(location).header("If-Match", "W/\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(patient("69404040404", "HistTwo"))).build());

        HttpResponse<String> history = send(req(location + "/_history").GET().build());
        assertEquals(200, history.statusCode());
        assertTrue(history.body().contains("\"history\""));
        assertTrue(history.body().contains("HistOne"));
        assertTrue(history.body().contains("HistTwo"));
    }

    /** REQ-DBO-SRCH-HONEST-CAPABILITY: metadata lists exactly the configured types + real params. */
    @Test
    void metadataListsConfiguredTypesAndTheirParameters() throws Exception {
        HttpResponse<String> metadata = send(req(base + "/metadata").GET().build());
        assertEquals(200, metadata.statusCode());
        String body = metadata.body();
        assertTrue(body.contains("CapabilityStatement"));
        assertTrue(body.contains("\"Patient\""));
        assertTrue(body.contains("\"Observation\""));
        assertFalse(body.contains("\"Encounter\""), "unconfigured types must not be advertised");
        assertTrue(body.contains("\"identifier\""));
        assertTrue(body.contains("\"_lastUpdated\""));
        assertTrue(body.contains("\"4.0.1\""));
    }

    /** Terminology operations answer over HTTP from the native concept store. */
    @Test
    void terminologyOperationsOverHttp() throws Exception {
        terminologyIngest.ingestCodeSystem("""
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"https://terms.rest.test/sys","concept":[
                   {"code":"a","display":"Alpha"},{"code":"b","display":"Beta"}]}""");
        terminologyIngest.ingestValueSet("""
                {"resourceType":"ValueSet","status":"active","url":"https://terms.rest.test/all",
                 "compose":{"include":[{"system":"https://terms.rest.test/sys"}]}}""");

        HttpResponse<String> validate = send(req(base
                + "/CodeSystem/$validate-code?system=https://terms.rest.test/sys&code=a").GET().build());
        assertEquals(200, validate.statusCode());
        assertTrue(validate.body().contains("\"valueBoolean\":true"));

        HttpResponse<String> lookup = send(req(base
                + "/CodeSystem/$lookup?system=https://terms.rest.test/sys&code=b").GET().build());
        assertEquals(200, lookup.statusCode());
        assertTrue(lookup.body().contains("Beta"));

        HttpResponse<String> expand = send(req(base
                + "/ValueSet/$expand?url=https://terms.rest.test/all&count=10").GET().build());
        assertEquals(200, expand.statusCode());
        assertTrue(expand.body().contains("Alpha"));
        assertTrue(expand.body().contains("\"total\":2"));

        assertEquals(404, send(req(base
                + "/ValueSet/$expand?url=https://terms.rest.test/nope").GET().build()).statusCode());
    }

    /** The same server class serves the R5 store — version-generic by facade. */
    @Test
    void r5StoreServesOverTheSameServerClass() throws Exception {
        String r5base = r5Server.baseUrl();
        HttpResponse<String> created = send(req(r5base + "/SubscriptionTopic")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"resourceType":"SubscriptionTopic","status":"active",
                         "url":"https://dbo.test/topics/rest"}""")).build());
        assertEquals(201, created.statusCode());
        String location = created.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> read = send(req(location).GET().build());
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("SubscriptionTopic"));

        HttpResponse<String> metadata = send(req(r5base + "/metadata").GET().build());
        assertTrue(metadata.body().contains("\"5.0.0\""));
    }
}
