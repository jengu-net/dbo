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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version is readable by its number, and the statement that says so is
 * telling the truth.
 *
 * <p>The capability statement listed {@code vread} for every type that keeps
 * history, and the surface answered {@code not-supported} to every one of
 * them. That is the worse half of the two: a client trusts a capability
 * statement precisely so it does not have to discover the surface by trying
 * it, so an advertised interaction that 404s costs more than an absent one.
 *
 * <p>The data was never the problem — every version is kept and the history
 * bundle already serves them all. What was missing was a route and one
 * entry's rendering.
 *
 * <p><b>Three answers, not two.</b> No such version is not found; the version
 * that deleted the record is gone; anything else is the document as it stood.
 * A client that cannot tell the first two apart cannot tell a typo from a
 * history, which is most of what it came to ask.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AVersionIsReadableByNumberIT {

    private static final String CLINIC = "vread";
    private static final String NID = "https://vread.example/nid";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String id;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-vread");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AVersionIsReadableByNumberIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"}]}"""
                .formatted(CLINIC, NID));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("a-reader", "reader-secret",
                java.util.List.of("system/*.read", "system/*.write"));

        HttpResponse<String> created = post("/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"one"}],
                 "name":[{"family":"Ambrose"}]}""".formatted(NID));
        assertEquals(201, created.statusCode(), created.body());
        String location = created.headers().firstValue("Location").orElseThrow();
        id = location.substring(location.lastIndexOf('/') + 1);
        // A second version, so there is an earlier one worth asking for by
        // number rather than a history of one that a plain read would answer.
        assertEquals(200, put("/Patient/" + id, """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"one"}],
                 "name":[{"family":"Bagshot"}]}""".formatted(NID)).statusCode());
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

    @Test
    @Order(1)
    @DisplayName("the statement advertises vread and the surface answers it, which it did not: "
            + "the first version comes back as it was written, not as it is now")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void theFirstVersionComesBackAsItWas() throws Exception {
        assertTrue(get("/metadata").body().contains("\"vread\""),
                "the statement stopped advertising vread, which is the other way to close "
                        + "this and not the one that was taken");

        HttpResponse<String> first = get("/Patient/" + id + "/_history/1");
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("Ambrose"),
                "asked for version 1 and got something else: " + first.body());
        assertFalse(first.body().contains("Bagshot"),
                "the current version came back under the first version's number, which is "
                        + "worse than refusing: " + first.body());
        assertTrue(first.body().contains("\"versionId\":\"1\""),
                "the document does not say which version it is: " + first.body());

        // A read carries its validators, and a version read carries the
        // validators OF THAT VERSION — an ETag echoing the current version
        // would make a conditional update out of a stale read look safe.
        assertEquals("W/\"1\"", first.headers().firstValue("ETag").orElse(null),
                "the ETag is not this version's");
        assertNotNull(first.headers().firstValue("Last-Modified").orElse(null));
    }

    @Test
    @Order(2)
    @DisplayName("and the current version is readable by its number too, so a client need not "
            + "know which read to use")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void theCurrentVersionIsJustAVersion() throws Exception {
        HttpResponse<String> second = get("/Patient/" + id + "/_history/2");
        assertEquals(200, second.statusCode(), second.body());
        assertTrue(second.body().contains("Bagshot"), second.body());
        assertEquals(get("/Patient/" + id).body(), second.body(),
                "the newest version read by number differs from the plain read of the same "
                        + "record, so one of the two is rendering it differently");
    }

    @Test
    @Order(3)
    @DisplayName("a version that never existed is not found, and one that is not a number "
            + "is not found either rather than a bad request")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void aVersionThatNeverExisted() throws Exception {
        HttpResponse<String> absent = get("/Patient/" + id + "/_history/99");
        assertEquals(404, absent.statusCode(), absent.body());
        assertTrue(absent.body().contains("not-found"), absent.body());

        assertEquals(404, get("/Patient/" + id + "/_history/two").statusCode(),
                "'two' names no version of anything; the spelling is the client's rather "
                        + "than a parameter this store failed to understand");
    }

    @Test
    @Order(4)
    @DisplayName("the version that deleted the record is gone rather than missing, because a "
            + "client cannot otherwise tell a typo from a history")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void theVersionThatDeletedItIsGone() throws Exception {
        assertEquals(204, delete("/Patient/" + id).statusCode());

        HttpResponse<String> gone = get("/Patient/" + id + "/_history/3");
        assertEquals(410, gone.statusCode(),
                "the tombstone is a version like any other and must not read as absent: "
                        + gone.body());
        assertTrue(gone.body().contains("deleted"), gone.body());
        // The moment it stopped having a document is the fact somebody came
        // here for, so it arrives with its own validators rather than bare.
        assertEquals("W/\"3\"", gone.headers().firstValue("ETag").orElse(null));

        // And the versions before it still read, which is the whole reason a
        // deletion is a version rather than an erasure.
        assertEquals(200, get("/Patient/" + id + "/_history/1").statusCode(),
                "deleting the record took its history with it");
        assertEquals(404, get("/Patient/" + id).statusCode(),
                "the record itself should be gone from a plain read");
    }

    // ------------------------------------------------------------ plumbing

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return HTTP.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(request(path).header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return HTTP.send(request(path).header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String path) throws Exception {
        return HttpRequest.newBuilder(URI.create(manager.baseUrl(CLINIC) + path))
                .header("Authorization", "Bearer " + token());
    }

    private static String token() throws Exception {
        String form = "grant_type=client_credentials&client_id=a-reader&client_secret="
                + URLEncoder.encode("reader-secret", StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(CLINIC).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
