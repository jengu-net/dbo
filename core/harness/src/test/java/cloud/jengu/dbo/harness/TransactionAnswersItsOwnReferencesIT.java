package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
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
 * A conditional reference resolves against the transaction's own entries as
 * well as the store (#129, REQ-DBO-CORE-CONDITIONAL-REFERENCES as amended):
 * a hierarchy authored as one document — each entry a conditional PUT on its
 * identity, each child naming its parent by that identity — lands whole, in
 * any entry order, and re-applying the same document converges onto the same
 * records rather than duplicating them
 * (REQ-DBO-CORE-CONDITIONAL-UPSERT, whose "and inside a bundle" the
 * transaction path never honoured before this).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionAnswersItsOwnReferencesIT {

    private static final String SYS = "https://osakond.test/layout";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    static String wingId;
    static String wardId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-txrefs");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TransactionAnswersItsOwnReferencesIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("paigutus.json"), """
                {"code":"paigutus","fhirVersion":"r4","types":[
                  {"name":"Location","identity":"identifier","systems":["%s"],
                   "handling":"projected-config"}]}""".formatted(SYS));
        UntilServed.scan(manager, "paigutus");
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
        return http.send(HttpRequest.newBuilder(URI.create(manager.baseUrl("paigutus")))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl("paigutus") + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The child BEFORE the parent, deliberately: order must not matter. */
    private static String wardTree() {
        return """
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Location","name":"Main Ward",
                    "identifier":[{"system":"%s","value":"hospital-wing-main-ward"}],
                    "partOf":{"reference":"Location?identifier=%s|hospital-wing"}},
                   "request":{"method":"PUT",
                    "url":"Location?identifier=%s|hospital-wing-main-ward"}},
                  {"resource":{"resourceType":"Location","name":"Hospital Wing",
                    "identifier":[{"system":"%s","value":"hospital-wing"}]},
                   "request":{"method":"PUT",
                    "url":"Location?identifier=%s|hospital-wing"}}
                ]}""".formatted(SYS, SYS, SYS, SYS, SYS);
    }

    @Test
    @Order(1)
    void aHierarchyAuthoredAsOneDocumentLandsWhole() throws Exception {
        HttpResponse<String> applied = post(wardTree());
        assertEquals(200, applied.statusCode(), applied.body());
        assertEquals(2, applied.body().split("\"201 Created\"", -1).length - 1,
                "both entries created: " + applied.body());

        wingId = idOf("hospital-wing");
        wardId = idOf("hospital-wing-main-ward");
        String ward = get("/Location/" + wardId).body();
        assertTrue(ward.contains("\"reference\":\"Location/" + wingId + "\""),
                "the child's partOf resolved to the parent CREATED IN THE SAME "
                        + "DOCUMENT, declared after it: " + ward);
    }

    /** Re-applying the same document converges; nothing duplicates. */
    @Test
    @Order(2)
    void reApplyingTheSameDocumentConverges() throws Exception {
        HttpResponse<String> again = post(wardTree());
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(2, again.body().split("\"200 OK\"", -1).length - 1,
                "the second application updates, never duplicates: " + again.body());
        assertEquals(wingId, idOf("hospital-wing"), "same record, same id");
        assertEquals(wardId, idOf("hospital-wing-main-ward"), "same record, same id");
        String count = get("/Location?_summary=count").body();
        assertTrue(count.contains("\"total\":2"), "still exactly two: " + count);
    }

    /** Two entries claiming one identity is a malformed document, named. */
    @Test
    @Order(3)
    void twoEntriesClaimingOneIdentityAreRefused() throws Exception {
        HttpResponse<String> refused = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Location","name":"One",
                    "identifier":[{"system":"%s","value":"topelt"}]},
                   "request":{"method":"PUT","url":"Location?identifier=%s|topelt"}},
                  {"resource":{"resourceType":"Location","name":"Two",
                    "identifier":[{"system":"%s","value":"topelt"}]},
                   "request":{"method":"PUT","url":"Location?identifier=%s|topelt"}}
                ]}""".formatted(SYS, SYS, SYS, SYS));
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("both claim"), refused.body());
        assertTrue(get("/Location?identifier=" + SYS + "%7Ctopelt&_summary=count")
                        .body().contains("\"total\":0"),
                "nothing applied — the transaction refused whole");
    }

    /** A question neither the document nor the store answers refuses as before. */
    @Test
    @Order(4)
    void aReferenceNeitherAnswersKeepsTheRefusal() throws Exception {
        HttpResponse<String> refused = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Location","name":"Orb",
                    "identifier":[{"system":"%s","value":"orb"}],
                    "partOf":{"reference":"Location?identifier=%s|olematu"}},
                   "request":{"method":"PUT","url":"Location?identifier=%s|orb"}}
                ]}""".formatted(SYS, SYS, SYS));
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("matches nothing in this store"),
                "the reasoning that was right in general is kept, reached later: "
                        + refused.body());
    }

    /** An identity the store already holds: the entry updates that record. */
    @Test
    @Order(5)
    void aClaimTheStoreAlreadyHoldsBecomesAnUpdateOntoIt() throws Exception {
        HttpResponse<String> applied = post("""
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Location","name":"Hospital Wing, renamed",
                    "identifier":[{"system":"%s","value":"hospital-wing"}]},
                   "request":{"method":"PUT","url":"Location?identifier=%s|hospital-wing"}},
                  {"resource":{"resourceType":"Location","name":"Second Ward",
                    "identifier":[{"system":"%s","value":"second-ward"}],
                    "partOf":{"reference":"Location?identifier=%s|hospital-wing"}},
                   "request":{"method":"PUT","url":"Location?identifier=%s|second-ward"}}
                ]}""".formatted(SYS, SYS, SYS, SYS, SYS));
        assertEquals(200, applied.statusCode(), applied.body());
        assertEquals(wingId, idOf("hospital-wing"),
                "the claim resolved onto the EXISTING record, not a twin");
        String second = get("/Location/" + idOf("second-ward")).body();
        assertTrue(second.contains("\"reference\":\"Location/" + wingId + "\""),
                "and the sibling's reference points at that same record: " + second);
    }

    private String idOf(String value) throws Exception {
        String body = get("/Location?identifier="
                + SYS.replace(":", "%3A").replace("/", "%2F") + "%7C" + value).body();
        return body.replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }
}
