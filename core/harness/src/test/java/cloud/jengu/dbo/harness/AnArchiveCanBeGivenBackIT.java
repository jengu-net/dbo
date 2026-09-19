package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.tenant.MaintenanceHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant can be handed the archive another tenant left with.
 *
 * <p>The export door has always answered and the import machinery has always
 * worked; what was missing was a door onto it. So this drives the surface
 * rather than the classes: a portable archive taken over HTTP from one tenant,
 * co-signed, and POSTed into another, which is the shape of the promise that
 * importing into a fresh tenant is a restore.
 *
 * <p>It is a separate route from {@code restore} on purpose, and the refusal
 * there still stands: a backup restores an INSTALLATION and a portable archive
 * carries no credentials, so offering one where the other is required would
 * produce a store nobody can authenticate against. This route takes content
 * into a tenant that already has an authority of its own.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnArchiveCanBeGivenBackIT {

    private static final byte[] OWNER_KEY = new byte[32];

    static SharedTenants.Tenant left;
    static SharedTenants.Tenant arrived;
    static HttpClient http;

    @BeforeAll
    void up() throws Exception {
        new java.security.SecureRandom().nextBytes(OWNER_KEY);
        http = HttpClient.newHttpClient();
        // A pair of numbered tenants. The destination is counted whole — one
        // patient, and the archive's own vocabulary beside it — so it has to
        // be a tenant nobody else writes a Patient into.
        left = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL, 4);
        arrived = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL, 5);

        assertEquals(201, fhir(left, """
                {"resourceType":"Patient","name":[{"family":"Lahkuja"}]}""").statusCode());
    }

    @Test
    @DisplayName("a portable archive taken from one tenant is accepted by another, through a "
            + "door rather than through a test holding the store")
    void anArchiveIsGivenBack() throws Exception {
        byte[] archive = taken();

        KeyPair vendor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair tenant = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String root;
        try (var plain = SealedArchive.opening(new ByteArrayInputStream(archive), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            root = rootFrom(zip);
        }
        ArchiveAttestation attestation = ArchiveAttestation.over(root)
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());

        HttpResponse<String> given = http.send(HttpRequest.newBuilder(
                        URI.create(admin(arrived) + "/import"))
                .header("Authorization", "Bearer " + systemToken(arrived))
                .header(MaintenanceHandler.OWNER_KEY_HEADER, Base64.getEncoder().encodeToString(OWNER_KEY))
                .header(MaintenanceHandler.ATTESTATION_HEADER, Base64.getEncoder()
                        .encodeToString(attestation.toJson().getBytes(StandardCharsets.UTF_8)))
                .header(MaintenanceHandler.VENDOR_KEY_HEADER, Base64.getEncoder()
                        .encodeToString(vendor.getPublic().getEncoded()))
                .header(MaintenanceHandler.TENANT_KEY_HEADER, Base64.getEncoder()
                        .encodeToString(tenant.getPublic().getEncoded()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(archive)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, given.statusCode(),
                "the import door refused an archive this store made: " + given.body());
        // Not a count. How many records an archive carries beyond the one
        // written here is the store's business — it publishes vocabularies of
        // its own into every tenant — and pinning the number would make this
        // a test about that rather than about the door.
        assertTrue(given.body().contains("\"imported\":") && !given.body().contains("\"imported\":0"),
                "the door answered without taking anything: " + given.body());

        // The half that would have conflicted before: the destination's own
        // copies of the store's vocabulary are recognised by the identity they
        // claim rather than met as a second row claiming it.
        assertTrue(!given.body().contains("\"skippedIdentical\":0"),
                "nothing was recognised as already held, so either the archive carried none of "
                        + "the store's own vocabulary or identity was not consulted: "
                        + given.body());

        HttpResponse<String> there = http.send(HttpRequest.newBuilder(
                        URI.create(arrived.fhir() + "/Patient?_summary=count"))
                .header("Authorization", "Bearer " + systemToken(arrived)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(there.body().contains("\"total\":1"),
                "the record is not in the tenant that received the archive: " + there.body());
    }

    /** The archive, taken the way a customer takes one. */
    private static byte[] taken() throws Exception {
        HttpResponse<byte[]> archive = http.send(HttpRequest.newBuilder(
                        URI.create(admin(left) + "/archive"))
                .header("Authorization", "Bearer " + systemToken(left))
                .header(MaintenanceHandler.OWNER_KEY_HEADER, Base64.getEncoder().encodeToString(OWNER_KEY))
                .header(MaintenanceHandler.KIND_HEADER, "portable-export")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, archive.statusCode(), "the export door did not answer");
        return archive.body();
    }

    private static String rootFrom(ZipInputStream zip) throws Exception {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if ("digests.json".equals(entry.getName())) {
                String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                int at = json.indexOf("\"root\":\"") + "\"root\":\"".length();
                return json.substring(at, json.indexOf('"', at));
            }
        }
        throw new IllegalStateException("no digest list in the archive");
    }

    private static String admin(SharedTenants.Tenant tenant) {
        return tenant.base() + "/admin";
    }

    private static HttpResponse<String> fhir(SharedTenants.Tenant tenant, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(tenant.fhir() + "/Patient"))
                        .header("Authorization", "Bearer " + systemToken(tenant))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String systemToken(SharedTenants.Tenant tenant) {
        return tenant.token("archive-keeper", "system/*.read", "system/*.write");
    }
}
