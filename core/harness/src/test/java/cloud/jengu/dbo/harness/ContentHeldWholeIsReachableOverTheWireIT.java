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
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A consumer speaks HTTP, and an interface mounted on a runtime is not a seam
 * it has. The store kept content whole and an archive carried it, and there
 * was no way in — built, mounted, and unreachable, which is a different
 * failure from missing and leaves whoever needs it in the same place.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentHeldWholeIsReachableOverTheWireIT {

    static SharedTenants.Tenant tenant;
    static String CLINIC;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static String writer;
    static String reader;

    @BeforeAll
    void up() throws Exception {
        // Shared. Blobs are keyed by what the store assigns, so every
        // assertion below is about the bytes this class just wrote and none
        // of them counts what the tenant holds.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        CLINIC = tenant.code();
        writer = tenant.token("blob-wire-writer", "system/*.write", "system/*.read");
        reader = tenant.token("blob-wire-reader", "system/*.read");
    }

    @Test
    @DisplayName("content put over the wire comes back over the wire as the bytes that were "
            + "sent, with the type its writer gave it")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void whatIsPutIsWhatComesBack() throws Exception {
        // Not text, and not valid UTF-8: a door that decoded anything on the
        // way through corrupts a scan silently rather than failing.
        byte[] scan = new byte[5000];
        new SecureRandom().nextBytes(scan);
        scan[0] = (byte) 0xFF;
        scan[1] = (byte) 0xFE;
        scan[2] = 0x00;

        HttpResponse<String> put = put(scan, "image/tiff", writer);
        assertEquals(201, put.statusCode(), put.body());
        String location = put.headers().firstValue("Location").orElseThrow();
        assertTrue(put.body().contains("\"key\""), put.body());

        HttpResponse<byte[]> got = HTTP.send(HttpRequest.newBuilder(URI.create(base() + location))
                        .header("Authorization", "Bearer " + reader).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, got.statusCode());
        assertArrayEquals(scan, got.body(),
                "the bytes changed between the door and the store, so what comes back is not "
                        + "the document that was sent");
        assertEquals("image/tiff", got.headers().firstValue("Content-Type").orElse(""),
                "the media type is the writer's statement about their own content and did "
                        + "not survive the trip");
    }

    @Test
    @DisplayName("two puts of the same bytes are two blobs, because the key is the store's "
            + "to choose")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void theStoreChoosesTheKey() throws Exception {
        byte[] same = "the same bytes twice".getBytes(StandardCharsets.UTF_8);
        String first = put(same, "text/plain", writer).headers()
                .firstValue("Location").orElseThrow();
        String second = put(same, "text/plain", writer).headers()
                .firstValue("Location").orElseThrow();

        assertNotEquals(first, second,
                "two callers writing the same content were given one key, so either can "
                        + "delete the other's");
    }

    @Test
    @DisplayName("forgetting content says whether there was any, and a key that names nothing "
            + "is not found")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void droppingTellsYouWhetherThereWasAnything() throws Exception {
        String location = put("gone shortly".getBytes(StandardCharsets.UTF_8),
                "text/plain", writer).headers().firstValue("Location").orElseThrow();

        assertEquals(204, delete(location, writer).statusCode(),
                "dropping content that was here did not say so");
        assertEquals(404, delete(location, writer).statusCode(),
                "dropping the same content twice said it was there twice, so a caller "
                        + "retrying cannot tell a success from a mistake");
        assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(base() + location))
                        .header("Authorization", "Bearer " + reader).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(),
                "content that was forgotten is still readable");
    }

    @Test
    @DisplayName("a credential that may read may not write, and none at all reaches nothing")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void theScopeIsTheOneForBinaryContent() throws Exception {
        byte[] content = "not yours to write".getBytes(StandardCharsets.UTF_8);

        assertEquals(403, put(content, "text/plain", reader).statusCode(),
                "a credential granted only reading wrote content");
        assertEquals(401, put(content, "text/plain", null).statusCode(),
                "content was written with no credential at all");
    }

    /**
     * This tenant is not behind the membrane, so it holds nobody's key. A
     * writer naming a person believes their content is protected, and keeping
     * it in the clear while they believe that is the failure the sealing
     * exists to prevent — one layer along.
     */
    @Test
    @DisplayName("a tenant that holds no keys refuses content named for a person, rather than "
            + "keeping it in the clear")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void whatCannotBeSealedIsNotQuietlyKept() throws Exception {
        HttpRequest.Builder named = HttpRequest.newBuilder(
                        URI.create(base() + "/t/" + CLINIC + "/blob?person="
                                + "01920000-0000-7000-8000-000000000000"))
                .header("Authorization", "Bearer " + writer)
                .header("Content-Type", "audio/ogg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1, 2, 3}));

        HttpResponse<String> refused = HTTP.send(named.build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, refused.statusCode(),
                "content named for a person was accepted by a tenant with no keys, so it is "
                        + "stored in the clear while whoever wrote it believes otherwise: "
                        + refused.body());
        assertTrue(refused.body().contains("seal"), refused.body());
    }

    private static String base() {
        return "http://127.0.0.1:" + SharedTenants.manager().port();
    }

    private static HttpResponse<String> put(byte[] content, String media, String bearer)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(base() + "/t/" + CLINIC + "/blob"))
                .header("Content-Type", media)
                .POST(HttpRequest.BodyPublishers.ofByteArray(content));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String location, String bearer) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + location))
                        .header("Authorization", "Bearer " + bearer).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String client, String secret, String scope) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + secret + "&scope="
                + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        String body = HTTP.send(HttpRequest.newBuilder(
                        URI.create(tenant.base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        int at = body.indexOf("\"access_token\"");
        int start = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(start, body.indexOf('"', start));
    }
}
