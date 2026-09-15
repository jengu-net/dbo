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
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recording is destroyed with the person it is about.
 *
 * <p>Erasure-by-drop takes a whole tenant, which is the wrong instrument for
 * one person asking to be forgotten. What reaches a single human is the key:
 * destroy it and everything sealed under it stops opening, wherever a copy
 * went. That only works if the sealing happens where the key lives — sealed
 * anywhere else, a person's erasure leaves the content readable beside a
 * receipt that says it was destroyed.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentIsSealedToThePersonItIsAboutIT {

    private static final String CLINIC = "pitseri-klinik";
    private static final String EID = "https://eid.test/ni";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String writer;
    static String personId;
    static String location;
    static byte[] recording;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-sealed-blob");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ContentIsSealedToThePersonItIsAboutIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC, EID));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("a-writer", "writer-secret",
                List.of("system/*.write", "system/*.read"));
        manager.authority(CLINIC).ensureClient("desk", "desk-secret", List.of("erasure"));
        writer = token("a-writer", "writer-secret");

        HttpResponse<String> person = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/Person"))
                        .header("Authorization", "Bearer " + writer)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Person",
                                 "identifier":[{"system":"%s","value":"39002020255"}],
                                 "name":[{"family":"Sepp"}]}""".formatted(EID))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, person.statusCode(), person.body());
        personId = person.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
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
    @DisplayName("content named for a person is kept, and comes back as the bytes that were "
            + "sent — the sealing is not a second thing a reader has to know about")
    @Proving(DboPromises.PDI_CRYPTO_SHREDDING)
    void sealedContentReadsBackAsItself() throws Exception {
        recording = new byte[4096];
        new SecureRandom().nextBytes(recording);
        recording[0] = (byte) 0xFF;
        recording[1] = (byte) 0xFE;

        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/blob?person=Person%2F" + personId))
                        .header("Authorization", "Bearer " + writer)
                        .header("Content-Type", "audio/ogg")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(recording)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, put.statusCode(), put.body());
        location = put.headers().firstValue("Location").orElseThrow();

        HttpResponse<byte[]> got = HTTP.send(HttpRequest.newBuilder(URI.create(root() + location))
                        .header("Authorization", "Bearer " + writer).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, got.statusCode());
        assertArrayEquals(recording, got.body(),
                "content sealed to a person did not come back as what was written, so the "
                        + "seal is changing the thing it is protecting");
        assertEquals("audio/ogg", got.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    @Order(2)
    @DisplayName("what is stored is not the bytes that were sent, because the point is that "
            + "nobody without the key can read it")
    @Proving(DboPromises.PDI_CRYPTO_SHREDDING)
    void whatIsAtRestIsSealed() throws Exception {
        // Read beneath the door, the way somebody with the database and no key
        // would: if the recording is lying there in the clear, destroying a key
        // destroys nothing.
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ContentIsSealedToThePersonItIsAboutIT")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + CLINIC.replace('-', '_')));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT content, person::text FROM state.blob");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next(), "nothing was stored");
            byte[] atRest = rs.getBytes(1);
            String whose = rs.getString(2);
            assertTrue(whose != null && !whose.isBlank(),
                    "the row does not say whose the content is, so nothing can know which "
                            + "key it needs");
            // The PERSON, not the record that names them. A record id taken
            // for a person id mints a key belonging to nobody: the content
            // seals, reads back perfectly, and survives the erasure of the
            // human it is about — every symptom of working and none of the
            // protection.
            assertNotEquals(personId, whose,
                    "the subject was stored as the record it named rather than resolved to "
                            + "the person, so the key it is sealed under is nobody's");
            assertTrue(atRest.length != recording.length
                            || !java.util.Arrays.equals(atRest, recording),
                    "the recording is at rest exactly as it was sent, so it is not sealed "
                            + "and destroying a key would destroy nothing");
        }
    }

    @Test
    @Order(3)
    @DisplayName("erasing the person makes the recording unreadable, and the answer says it "
            + "was erased rather than that it was never here")
    @Proving(DboPromises.PDI_CRYPTO_SHREDDING)
    void erasingThePersonReachesTheContent() throws Exception {
        HttpResponse<String> erased = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/erasure"))
                        .header("Authorization", "Bearer " + token("desk", "desk-secret"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"subject\":\"Person/" + personId + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, erased.statusCode(), erased.body());

        HttpResponse<String> after = HTTP.send(HttpRequest.newBuilder(
                        URI.create(root() + location))
                        .header("Authorization", "Bearer " + writer).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(410, after.statusCode(),
                "the recording is still readable after its person was erased, so the receipt "
                        + "says destroyed and the content opens: " + after.statusCode()
                        + " " + after.body());
        assertTrue(after.body().contains("erased"),
                "410 is right and the body has to say why: " + after.body());
    }

    private static String base() {
        return manager.baseUrl(CLINIC).replace("/fhir", "");
    }

    /**
     * The server root. A Location is an absolute path from here, and joining
     * it to the tenant's own base asks for the tenant twice.
     */
    private static String root() {
        return "http://127.0.0.1:" + manager.port();
    }

    private static String token(String client, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
