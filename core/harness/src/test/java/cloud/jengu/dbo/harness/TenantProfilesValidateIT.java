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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation runs against the carried pack PLUS what the tenant defined.
 *
 * <p>A profile a tenant authored is a shape its writes are held to — not
 * "a shape this face does not carry", which is what the store answered before
 * this. And it takes effect when it is written, for that tenant alone.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantProfilesValidateIT {

    /** A tenant's own rule: an Observation here must carry a subject. */
    private static final String PROFILE = """
            {"resourceType":"StructureDefinition",
             "url":"https://sonavara.example/StructureDefinition/observed-on-somebody",
             "name":"ObservedOnSomebody","status":"active","kind":"resource",
             "abstract":false,"type":"Observation",
             "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
             "derivation":"constraint",
             "differential":{"element":[
               {"id":"Observation.subject","path":"Observation.subject","min":1}]}}""";

    private static final String WITHOUT_SUBJECT = """
            {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
             "meta":{"profile":["https://sonavara.example/StructureDefinition/observed-on-somebody"]}}""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-profiles");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TenantProfilesValidateIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("profiilid.json"), """
                {"code":"profiilid","fhirVersion":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("profiilid"));
        base = manager.baseUrl("profiilid");
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

    @Test
    @Order(1)
    @Proving(DboPromises.VER_SPECIFIED_VALIDATION)
    void beforeTheProfileExistsTheShapeIsUnknownRatherThanSatisfied() throws Exception {
        HttpResponse<String> answer = post("/Observation", WITHOUT_SUBJECT);
        assertTrue(answer.statusCode() >= 400,
                "a profile nothing carries is not a shape anything conforms to: "
                        + answer.statusCode() + " " + answer.body());
    }

    @Test
    @Order(2)
    void aWrittenProfileTakesEffectWithoutARestart() throws Exception {
        assertEquals(201, post("/StructureDefinition", PROFILE).statusCode());

        HttpResponse<String> refused = post("/Observation", WITHOUT_SUBJECT);
        assertEquals(422, refused.statusCode(),
                "the tenant's own rule is now a rule: " + refused.body());
        assertTrue(refused.body().contains("subject"),
                "and the refusal says which element: " + refused.body());
    }

    @Test
    @Order(3)
    void whatTheProfileAllowsIsAccepted() throws Exception {
        HttpResponse<String> accepted = post("/Observation", """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "subject":{"reference":"Patient/anyone"},
                 "meta":{"profile":["https://sonavara.example/StructureDefinition/observed-on-somebody"]}}""");
        assertEquals(201, accepted.statusCode(), accepted.body());
    }

    @Test
    @Order(4)
    @Proving(DboPromises.VER_SPECIFIED_VALIDATION)
    void theDifferentialIsSnapshottedSoTheWholeBaseStillApplies() throws Exception {
        // status is required by the BASE Observation, and the tenant's
        // differential never mentions it: without snapshot generation the
        // validator would check the one element the author wrote and pass
        // everything else
        HttpResponse<String> refused = post("/Observation", """
                {"resourceType":"Observation","code":{"text":"pulse"},
                 "subject":{"reference":"Patient/anyone"},
                 "meta":{"profile":["https://sonavara.example/StructureDefinition/observed-on-somebody"]}}""");
        assertEquals(422, refused.statusCode(),
                "the base's own rules survive the profile: " + refused.body());
        assertTrue(refused.body().contains("status"), refused.body());
    }
}
