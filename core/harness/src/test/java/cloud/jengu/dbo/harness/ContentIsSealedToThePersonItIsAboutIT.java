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

    static SharedTenants.Tenant tenant;
    static String CLINIC;
    /** The shape declares which system a Person is keyed by. */
    private static final String EID = SharedTenants.EID;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static String writer;
    static String personId;
    static String location;
    static byte[] recording;

    @BeforeAll
    void up() throws Exception {
        // Shared. Its at-rest check is now asked about the blob it wrote, by
        // the key the store handed back, so nothing here depends on being the
        // only class using the tenant.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_PDI_PERSON);
        CLINIC = tenant.code();
        tenant.authority().ensureClient("a-writer", "writer-secret",
                List.of("system/*.write", "system/*.read"));
        tenant.authority().ensureClient("desk", "desk-secret", List.of("erasure"));
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
        ds.setUrl(tenant.databaseUrl());
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        // Asked about the blob this class wrote, by the key the store handed
        // back in Location. It used to take whichever row came first, which is
        // only the right row on a tenant nobody else is using.
        String key = location.substring(location.lastIndexOf('/') + 1);
        try (var c = ds.getConnection();
             var ps = c.prepareStatement(
                     "SELECT content, person::text FROM state.blob WHERE key = ?::uuid")) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            assertTrue(rs.next(), "nothing was stored under " + key);
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
        return tenant.base();
    }

    /**
     * The server root. A Location is an absolute path from here, and joining
     * it to the tenant's own base asks for the tenant twice.
     */
    private static String root() {
        return "http://127.0.0.1:" + SharedTenants.manager().port();
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
