package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property, not the case: a lookup by a value the store holds either finds
 * the record or refuses — it never answers an empty bundle.
 *
 * <p>Empty is the one answer a caller cannot act on and cannot detect. It
 * reads as <i>nobody here</i>, so a consumer that meant to recognise somebody
 * returning mints a second identity for them instead — which is how this
 * arrived: not as an error anybody saw, but as duplicated people.
 *
 * <p>Written as a matrix because the shape that broke was one cell of it. A
 * record whose own type claims the value, a record carrying a value another
 * type is identified by, and a value nothing is identified by at all are three
 * different paths through the membrane, and the rule is the same for all
 * three. A case would have gone green again the moment somebody re-tightened
 * the wrong filter; this does not.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnIdentifyingLookupNeverAnswersEmptyIT {

    private static final String CLINIC = "leidmisreegel";
    private static final String EID = "https://ee.ee/eid";
    private static final String UNDECLARED = "https://kliinik.example/kaart";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String token;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-lookup-rule");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnIdentifyingLookupNeverAnswersEmptyIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Practitioner","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC, EID));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("emr", "emr-secret",
                List.of("system/*.read", "system/*.write"));
        token = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=emr&client_secret="
                                        + URLEncoder.encode("emr-secret", StandardCharsets.UTF_8)))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
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
    @DisplayName("every way a person-type record can carry a value, a lookup by that value "
            + "finds it or refuses — and never comes back empty")
    @Proving({DboPromises.PDI_EXACT_RESOLUTION, DboPromises.SRCH_HONEST_CAPABILITY})
    void noneOfTheseAnswersEmpty() throws Exception {
        List<String> silent = new ArrayList<>();

        // the type identified by that system, claiming the value
        check("Person", EID, "30101010001", silent);
        // a type declared internal, carrying a value another type is identified by
        check("Patient", EID, "30101010002", silent);
        check("Practitioner", EID, "30101010003", silent);
        // a value NOTHING here is identified by: held, indexed, and nobody's identity
        check("Patient", UNDECLARED, "card-4471", silent);

        assertTrue(silent.isEmpty(),
                "these are held by this store and a lookup for them answered an empty "
                        + "bundle. Empty reads as nobody here, and a caller acting on it "
                        + "creates the person again:\n  " + String.join("\n  ", silent));
    }

    /** Writes one record carrying the value, then asks for it back. */
    private void check(String type, String system, String value, List<String> silent)
            throws Exception {
        HttpResponse<String> written = post("/" + type, """
                {"resourceType":"%s","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Otsitav"}]}""".formatted(type, system, value));
        assertEquals(201, written.statusCode(), written.body());
        String id = written.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");

        HttpResponse<String> found = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/" + type + "?identifier="
                                + URLEncoder.encode(system + "|" + value, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token)
                        .header("Purpose-Of-Use", "TREAT").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // A refusal is a fine answer: it says the store holds this and cannot
        // match on it, which a caller can act on. Silence is the failure.
        if (found.statusCode() == 200 && !found.body().contains(id)) {
            silent.add(type + " carrying " + system + "|" + value
                    + " (record " + id + ") -> " + found.body().replaceAll("\\s+", " "));
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/" + CLINIC;
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
