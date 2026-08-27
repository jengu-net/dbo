package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference that is a question is answered when the document is written
 * (#89).
 *
 * <p>The writer knows an identifier and not an id — a device, an integration,
 * a bundle author allocating nothing. Stored unresolved, such a reference
 * points at nothing: accepted and broken.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConditionalReferencesIT {

    private static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static String patientId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-condref");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ConditionalReferencesIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("viide.json"), """
                {"code":"viide","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(EID));
        UntilServed.scan(manager, up -> up.contains("viide"));
        base = manager.baseUrl("viide");

        HttpResponse<String> created = post("/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"38001010000"}],
                 "name":[{"family":"Viidatud"}]}""".formatted(EID));
        assertEquals(201, created.statusCode(), created.body());
        patientId = created.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/Patient/([^/]+).*", "$1");
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

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String observation(String subject) {
        return """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "subject":{"reference":"%s"}}""".formatted(subject);
    }

    @Test
    @Order(1)
    @Proving(DboPromises.CORE_CONDITIONAL_REFERENCES)
    void aWriterThatKnowsAnIdentifierNeedNotKnowAnId() throws Exception {
        HttpResponse<String> written = post("/Observation",
                observation("Patient?identifier=" + EID + "|38001010000"));
        assertEquals(201, written.statusCode(), written.body());

        String stored = http.send(HttpRequest.newBuilder(URI.create(base + "/Observation/"
                                + written.headers().firstValue("Location").orElseThrow()
                                .replaceAll(".*/Observation/([^/]+).*", "$1")))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(stored.contains("Patient/" + patientId),
                "the stored reference is the id the store assigned: " + stored);
        assertFalse(stored.contains("?identifier="),
                "and no question survives into the store: " + stored);
    }

    @Test
    @Order(2)
    @Proving(DboPromises.CORE_CONDITIONAL_REFERENCES)
    void aReferenceMatchingNothingIsRefusedRatherThanStoredBroken() throws Exception {
        HttpResponse<String> refused = post("/Observation",
                observation("Patient?identifier=" + EID + "|nobody-has-this"));
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("nobody-has-this"),
                "the refusal names the question that went unanswered: " + refused.body());
    }

    @Test
    @Order(3)
    @Proving(DboPromises.CORE_CONDITIONAL_REFERENCES)
    void aReferenceMayAskByIdentityAndNotByGeneralSearch() throws Exception {
        HttpResponse<String> refused = post("/Observation",
                observation("Patient?name=Viidatud"));
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("identity"),
                "and says why, rather than answering a search: " + refused.body());
    }

    @Test
    @Order(4)
    void anOrdinaryReferenceIsUntouched() throws Exception {
        HttpResponse<String> written = post("/Observation",
                observation("Patient/" + patientId));
        assertEquals(201, written.statusCode(), written.body());
    }

    @Test
    @Order(5)
    @Proving(DboPromises.CORE_CONDITIONAL_REFERENCES)
    void itWorksInsideABundleToo() throws Exception {
        HttpResponse<String> answered = post("", """
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Observation","status":"final",
                    "code":{"text":"in a bundle"},
                    "subject":{"reference":"Patient?identifier=%s|38001010000"}},
                   "request":{"method":"POST","url":"Observation"}}]}"""
                .formatted(EID));
        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("201 Created"), answered.body());

        String obsId = answered.body()
                .replaceAll(".*\"location\":\"Observation/([^/]+)/_history.*", "$1");
        String stored = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/Observation/" + obsId)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(stored.contains("Patient/" + patientId),
                "an entry's conditional reference is resolved like any other write: " + stored);
    }
}
