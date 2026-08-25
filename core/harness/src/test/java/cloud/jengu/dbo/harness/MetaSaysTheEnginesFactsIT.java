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
 * Two engine facts are said where FHIR says them (#109): {@code Meta.security}
 * carries the handling class this store enforces on the record, and
 * {@code Meta.source} says which upstream streamed a copy here. Before this, a
 * client received the data and not the classification governing it — the
 * element has existed for exactly this since R4.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetaSaysTheEnginesFactsIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-metafacts");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("MetaSaysTheEnginesFactsIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("allikas.json"), """
                {"code":"allikas","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"mirrored"}]}""");
        UntilServed.scan(manager, "allikas");
        Files.writeString(dir.resolve("saaja.json"), """
                {"code":"saaja","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"mirrored"}],
                 "dependencies":[{"name":"allikas","types":["CodeSystem"]}]}""");
        UntilServed.scan(manager, "saaja");
        manager.syncRound();
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

    private String base(String code) {
        return manager.baseUrl(code);
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Every served record says the classification governing it. */
    @Test
    void aServedRecordCarriesItsHandlingClassInMetaSecurity() throws Exception {
        HttpResponse<String> created = post(base("allikas") + "/Patient",
                "{\"resourceType\":\"Patient\",\"gender\":\"female\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = created.body().replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        String served = get(base("allikas") + "/Patient/" + id).body();
        assertTrue(served.contains("\"system\":\"urn:dbo:handling\"")
                        && served.contains("\"code\":\"operational\""),
                "the read says the declared class: " + served);
        // and the search path frames the same fact
        String bundle = get(base("allikas") + "/Patient").body();
        assertTrue(bundle.contains("urn:dbo:handling"),
                "a search hit is stamped like a read: " + bundle);
    }

    /** A streamed copy says where it came from; the original says nothing. */
    @Test
    void aStreamedCopySaysItsUpstreamInMetaSource() throws Exception {
        String atSource = post(base("allikas") + "/CodeSystem", """
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"https://allikas.test/cs/varvid","concept":[{"code":"roheline"}]}""")
                .body();
        assertFalse(atSource.contains("urn:dbo:upstream:"),
                "the tenant's own record has no upstream: " + atSource);
        manager.syncRound();

        String copied = get(base("saaja")
                + "/CodeSystem?url=https://allikas.test/cs/varvid").body();
        assertTrue(copied.contains("\"source\":\"urn:dbo:upstream:allikas\""),
                "the copy says which upstream streamed it: " + copied);
        assertTrue(copied.contains("\"code\":\"mirrored\""),
                "and the handling stamp says what governs it here: " + copied);
    }

    /** The author's own security codings survive, and ours never accumulates. */
    @Test
    void anAuthorsSecurityCodingSurvivesAndOursDoesNotAccumulate() throws Exception {
        HttpResponse<String> created = post(base("allikas") + "/Patient", """
                {"resourceType":"Patient",
                 "meta":{"security":[{"system":"http://terminology.hl7.org/CodeSystem/v3-Confidentiality","code":"R"}]}}""");
        assertEquals(201, created.statusCode(), created.body());
        String id = created.body().replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        String served = get(base("allikas") + "/Patient/" + id).body();
        assertTrue(served.contains("v3-Confidentiality") && served.contains("\"code\":\"R\""),
                "the author's coding survives: " + served);
        assertEquals(1, count(served, "urn:dbo:handling"),
                "exactly one engine stamp: " + served);

        // Round trip: the served document, with our stamp in its bytes, is
        // stored as a new record and served again — still exactly one stamp.
        // the id comes out with its leading comma, or the leftover comma makes
        // the round-tripped document invalid JSON
        HttpResponse<String> roundTripped = post(base("allikas") + "/Patient",
                served.replaceAll(",\"id\":\"[^\"]+\"", ""));
        assertEquals(201, roundTripped.statusCode(), roundTripped.body());
        String secondId = roundTripped.body().replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        String again = get(base("allikas") + "/Patient/" + secondId).body();
        assertEquals(1, count(again, "urn:dbo:handling"),
                "a served document written back does not accumulate stamps: " + again);
        assertTrue(again.contains("v3-Confidentiality"),
                "while the author's coding still survives: " + again);
    }

    /** The system on the wire resolves, per the #91 ratchet. */
    @Test
    void theHandlingSystemResolvesWhereItIsServedFrom() throws Exception {
        // 200 is the discriminator: the answer for a known code carries no
        // display because the handling codes have none (#50's shape), so the
        // body is not the thing to match -- the unknown code refusing is.
        HttpResponse<String> lookup = get(base("allikas")
                + "/CodeSystem/$lookup?system=urn:dbo:handling&code=mirrored");
        assertEquals(200, lookup.statusCode(), lookup.body());
        HttpResponse<String> unknown = get(base("allikas")
                + "/CodeSystem/$lookup?system=urn:dbo:handling&code=sellist-pole");
        assertTrue(unknown.statusCode() >= 400,
                "an unknown code is refused, which is what makes the 200 above an answer: "
                        + unknown.statusCode());
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
