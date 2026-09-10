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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version's definitions, held as records by a tenant made for it.
 *
 * <p>A version used to be an object graph loaded into whichever node served
 * it — two hundred megabytes, copied per tenant, regenerated at every
 * bring-up. A face root is what makes it possible for a tenant to
 * <i>subscribe</i> to a version instead: the definitions are records in one
 * tenant's store, findable by canonical url like anything else, and flowing to
 * dependents through the same chain a zone's terminology takes.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AFaceRootHoldsItsVersionAsRecordsIT {

    private static final String ROOT = "juur-r4";
    private static final String PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String token;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-face-root");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AFaceRootHoldsItsVersionAsRecordsIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(ROOT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"StructureMap","identity":"canonical","handling":"operational"}]}"""
                .formatted(ROOT));
        UntilServed.scan(manager, ROOT);
        manager.authority(ROOT).ensureClient("reader", "reader-secret",
                List.of("system/*.read"));
        token = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=reader&client_secret="
                                        + "reader-secret")).build(),
                HttpResponse.BodyHandlers.ofString())
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
    @DisplayName("the version's definitions are records in the root, found by canonical url, "
            + "and a second boot holds each exactly once")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void theRootHoldsTheVersionOnceAndFindably() throws Exception {
        HttpResponse<String> patient = byUrl("StructureDefinition", PATIENT);
        assertEquals(200, patient.statusCode(), patient.body());
        assertTrue(patient.body().contains("\"kind\":\"resource\"")
                        && patient.body().contains("\"type\":\"Patient\""),
                "the root does not hold the version's own Patient definition as a record, so "
                        + "there is nothing for a dependent to replicate: " + patient.body());
        assertEquals(1, matches(patient.body()),
                "one canonical url, one record — anything else is a definition that would "
                        + "answer twice at validation");

        HttpResponse<String> parameters = search("SearchParameter", "_summary=count");
        assertTrue(count(parameters.body()) > 1000,
                "R4 defines over a thousand search parameters and the root holds "
                        + count(parameters.body()) + ": " + parameters.body());

        // Boot again over the same store. The packages are still there to be
        // read; the records must not be read into a second time.
        manager.scanOnce();
        assertEquals(1, matches(byUrl("StructureDefinition", PATIENT).body()),
                "a second boot loaded the packages over the records that were already held");
    }

    @Test
    @DisplayName("a serving tenant declared a root while it serves loads the version where it "
            + "stands, rather than remembering that it should")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void aTenantBecomingARootLoadsWhereItStands() throws Exception {
        String later = "juur-hiljem";
        String types = """
                "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"}]""";
        Files.writeString(dir.resolve(later + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},%s}""".formatted(later, types));
        UntilServed.scan(manager, later);

        // Declared a root now, without going down.
        Files.writeString(dir.resolve(later + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},%s}"""
                .formatted(later, types));
        manager.scanOnce();

        manager.authority(later).ensureClient("reader", "reader-secret", List.of("system/*.read"));
        String laterBase = "http://127.0.0.1:" + manager.port() + "/t/" + later;
        String laterToken = http.send(HttpRequest.newBuilder(URI.create(laterBase + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=reader&client_secret="
                                        + "reader-secret")).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> patient = http.send(HttpRequest.newBuilder(
                        URI.create(laterBase + "/fhir/StructureDefinition?url="
                                + URLEncoder.encode(PATIENT, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + laterToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(1, matches(patient.body()),
                "the tenant was classified as taking the change where it stands and then only "
                        + "remembered it — a root that holds nothing: " + patient.body());
    }

    private static HttpResponse<String> byUrl(String type, String url) throws Exception {
        return search(type, "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> search(String type, String query) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/" + type + "?" + query))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int matches(String bundle) {
        return bundle.split("\"fullUrl\"").length - 1;
    }

    private static int count(String bundle) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"total\":(\\d+)")
                .matcher(bundle);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/" + ROOT;
    }
}
