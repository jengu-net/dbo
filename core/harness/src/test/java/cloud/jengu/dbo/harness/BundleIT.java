package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * A Bundle POSTed to the base is a first-class request (#86).
 *
 * <p>REQ-DBO-CORE-BATCH-ANSWERS-PER-ENTRY: a batch answers one entry per
 * request entry, in order, each with its own status — a failing entry says
 * nothing about its neighbours.
 *
 * <p>REQ-DBO-CORE-ATOMIC-TRANSACTION-BUNDLE: a transaction lands whole or not
 * at all, and entries may point at each other by {@code urn:uuid} — resolved
 * to the ids the store allocated, never stored dangling.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundleIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-bundle");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("BundleIT"), postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("kimp.json"), """
                {"code":"kimp","fhirVersion":"r4","types":[
                  {"name":"Organization","identity":"internal","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("kimp"));
        base = manager.baseUrl("kimp");
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

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long count(String type) throws Exception {
        String body = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/" + type + "?_summary=count")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return Long.parseLong(body.replaceAll(".*\"total\":(\\d+).*", "$1"));
    }

    @Test
    void theConsumersOwnReproIsAnsweredNotErrored() throws Exception {
        // the exact bundle from the issue
        HttpResponse<String> answer = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Organization","name":"Txn 0"},
                   "request":{"method":"POST","url":"Organization"}}]}""");
        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("transaction-response"), answer.body());
        assertTrue(answer.body().contains("201 Created"), answer.body());
    }

    @Test
    void aBatchAnswersPerEntryAndAFailureIsLocal() throws Exception {
        long before = count("Organization");
        HttpResponse<String> answer = post("""
                {"resourceType":"Bundle","type":"batch","entry":[
                  {"resource":{"resourceType":"Organization","name":"Batch A"},
                   "request":{"method":"POST","url":"Organization"}},
                  {"resource":{"resourceType":"Patient","name":[{"family":"Wrong"}]},
                   "request":{"method":"POST","url":"Organization"}},
                  {"resource":{"resourceType":"Organization","name":"Batch B"},
                   "request":{"method":"POST","url":"Organization"}}]}""");
        assertEquals(200, answer.statusCode(), answer.body());
        String body = answer.body();
        assertTrue(body.contains("batch-response"), body);
        assertEquals(2, body.split("201 Created", -1).length - 1,
                "the two good entries each answer 201: " + body);
        assertTrue(body.contains("\"status\":\"400\""),
                "the bad entry answers its own 400, naming the mismatch: " + body);
        assertEquals(before + 2, count("Organization"),
                "the good entries landed, the bad one did not");
    }

    @Test
    void aTransactionLandsWholeOrNotAtAll() throws Exception {
        long before = count("Patient");
        HttpResponse<String> refused = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Patient","name":[{"family":"Atom"}]},
                   "request":{"method":"POST","url":"Patient"}},
                  {"resource":{"resourceType":"Patient","gender":"not-a-gender"},
                   "request":{"method":"POST","url":"Patient"}}]}""");
        assertEquals(422, refused.statusCode(), refused.body());
        assertEquals(before, count("Patient"),
                "a refused transaction leaves the store exactly as it was");
    }

    @Test
    void entriesReferenceEachOtherByUrnAndTheStoredReferenceIsReal() throws Exception {
        HttpResponse<String> answer = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"fullUrl":"urn:uuid:11111111-1111-1111-1111-111111111111",
                   "resource":{"resourceType":"Patient","name":[{"family":"Sidus"}]},
                   "request":{"method":"POST","url":"Patient"}},
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"pulse"},
                     "subject":{"reference":"urn:uuid:11111111-1111-1111-1111-111111111111"}},
                   "request":{"method":"POST","url":"Observation"}}]}""");
        assertEquals(200, answer.statusCode(), answer.body());
        String patientId = answer.body()
                .replaceAll(".*\"location\":\"Patient/([^/]+)/_history.*", "$1");
        String obsId = answer.body()
                .replaceAll(".*\"location\":\"Observation/([^/]+)/_history.*", "$1");
        String stored = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/Observation/" + obsId)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(stored.contains("Patient/" + patientId),
                "the stored reference is the allocated id: " + stored);
        assertFalse(stored.contains("urn:uuid"),
                "no urn survives into the store: " + stored);
    }

    @Test
    void whatIsNotServedIsRefusedByNameNeverA500() throws Exception {
        // no request on the entry
        HttpResponse<String> noRequest = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Organization","name":"X"}}]}""");
        assertEquals(400, noRequest.statusCode(), noRequest.body());
        assertTrue(noRequest.body().contains("no request"), noRequest.body());

        // a bundle kind this endpoint does not serve
        HttpResponse<String> searchset = post(
                "{\"resourceType\":\"Bundle\",\"type\":\"searchset\"}");
        assertEquals(400, searchset.statusCode(), searchset.body());
        assertTrue(searchset.body().contains("searchset"), searchset.body());

        // not a bundle at all
        HttpResponse<String> notABundle = post(
                "{\"resourceType\":\"Patient\"}");
        assertEquals(400, notABundle.statusCode(), notABundle.body());

        // and GET on the base is a named refusal, not an index error
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(base))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, get.statusCode(), get.body());
        assertFalse(get.body().contains("out of bounds"), get.body());
    }
}
