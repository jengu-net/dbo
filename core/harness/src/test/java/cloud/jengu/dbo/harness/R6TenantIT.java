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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant on FHIR R6 comes up and is served — by the same facade every other
 * version will be served by.
 *
 * <p>R6 goes first because it has no incumbent: there is no R6 personality to
 * regress, and no generated R6 model to fall back on, so a store that answers
 * here is a store answering entirely from definitions. Everything a tenant
 * exercises is in one path — the engine's registrations, the extractor built
 * from the version's search parameters, the search compiler, the framing and
 * the ancestors put back on the way out.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R6TenantIT {

    private static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        String jdbcUrl = SharedPostgres.urlFor("R6TenantIT");
        dir = Files.createTempDirectory("dbo-tenants-r6");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("kuues.json"), """
                {"code":"kuues","face":"r6","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(EID));
        UntilServed.scan(manager, up -> up.contains("kuues"));
        base = manager.baseUrl("kuues");
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

    @Test
    @Order(1)
    @DisplayName("a spec declaring a version with no generated model becomes a live tenant")
    void aTenantOnR6ComesUp() throws Exception {
        assertTrue(manager.runtime("kuues").isPresent(),
                "a tenant on a face the container carries must come up");

        HttpResponse<String> metadata = get(base + "/metadata");
        assertEquals(200, metadata.statusCode());
        assertTrue(metadata.body().contains("\"fhirVersion\":\"6.0.0-ballot5\""),
                "the statement must say the ballot it serves: " + metadata.body());
        assertTrue(metadata.body().contains("\"type\":\"Patient\""));
    }

    @Test
    @Order(2)
    @DisplayName("a write is validated against the ballot's own definitions")
    @Proving(DboPromises.VER_PERSONALITY_OWNS_MEANING)
    void aWriteIsValidatedAgainstTheDefinitions() throws Exception {
        HttpResponse<String> refused = post(base + "/Patient", """
                {"resourceType":"Patient","gender":"unicorn"}""");
        // 422 rather than 400: the request was well formed and the resource
        // was not, which is the distinction FHIR asks a server to make
        assertEquals(422, refused.statusCode(),
                "a code outside the definition's value set must not be stored: " + refused.body());

        HttpResponse<String> created = post(base + "/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38001010001"}],
                 "name":[{"family":"Aiakas","given":["Kass"]}],
                 "gender":"female","birthDate":"1980-01-01"}""".formatted(EID));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.headers().firstValue("Location").isPresent());
    }

    @Test
    @Order(3)
    @DisplayName("and it is found by what the version's search parameters extracted")
    @Proving({DboPromises.SRCH_STRICT_BY_DEFAULT, DboPromises.VER_PERSONALITY_OWNS_MEANING})
    void itIsFoundByItsEnvelope() throws Exception {
        HttpResponse<String> byIdentifier = get(base + "/Patient?identifier="
                + java.net.URLEncoder.encode(EID + "|38001010001",
                        java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, byIdentifier.statusCode(), byIdentifier.body());
        assertTrue(byIdentifier.body().contains("Aiakas"),
                "an identifier search must find what was written: " + byIdentifier.body());
        assertTrue(byIdentifier.body().contains("\"resourceType\":\"Bundle\""));
        assertTrue(byIdentifier.body().contains("\"mode\":\"match\""));

        HttpResponse<String> byName = get(base + "/Patient?family=aiak");
        assertTrue(byName.body().contains("Aiakas"),
                "string search is starts-with and case-insensitive: " + byName.body());

        HttpResponse<String> unknown = get(base + "/Patient?nosuchparam=1");
        assertEquals(400, unknown.statusCode(),
                "an unknown parameter is refused, never silently ignored");
    }

    @Test
    @Order(4)
    @DisplayName("a read carries the id and version the stored bytes never had")
    void aReadCarriesTheAncestors() throws Exception {
        String id = idOfTheOnePatient();

        HttpResponse<String> read = get(base + "/Patient/" + id);
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"id\":\"" + id + "\""), read.body());
        assertTrue(read.body().contains("\"versionId\":\"1\""), read.body());
        assertTrue(read.body().contains("38001010001"),
                "and what the author wrote is still there");
    }

    @Test
    @Order(5)
    @DisplayName("an update is a version, and the history says so")
    void anUpdateIsAVersion() throws Exception {
        String id = idOfTheOnePatient();

        HttpResponse<String> updated = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/Patient/" + id))
                .header("Content-Type", "application/fhir+json")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"resourceType":"Patient","id":"%s",
                         "identifier":[{"system":"%s","value":"38001010001"}],
                         "name":[{"family":"Aiakas","given":["Kass","Teine"]}],
                         "gender":"female","birthDate":"1980-01-01"}""".formatted(id, EID)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updated.statusCode(), updated.body());

        HttpResponse<String> history = get(base + "/Patient/" + id + "/_history");
        assertEquals(200, history.statusCode());
        assertTrue(history.body().contains("\"type\":\"history\""), history.body());
        assertTrue(history.body().contains("\"versionId\":\"1\"")
                && history.body().contains("\"versionId\":\"2\""),
                "both versions must be in the history: " + history.body());
    }

    private String idOfTheOnePatient() throws Exception {
        String bundle = get(base + "/Patient?family=aiak").body();
        int at = bundle.indexOf("\"id\":\"");
        return bundle.substring(at + 6, bundle.indexOf('"', at + 6));
    }
}
