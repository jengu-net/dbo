package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.MaintenanceHandler;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final String LEFT = "lahkuja";
    private static final String ARRIVED = "saabuja";
    private static final byte[] OWNER_KEY = new byte[32];

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static HttpClient http;

    @BeforeAll
    void up() throws Exception {
        new java.security.SecureRandom().nextBytes(OWNER_KEY);
        postgres = SharedPostgres.get();
        http = HttpClient.newHttpClient();
        dir = Files.createTempDirectory("dbo-given-back");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnArchiveCanBeGivenBackIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        for (String code : List.of(LEFT, ARRIVED)) {
            Files.writeString(dir.resolve(code + ".json"), """
                    {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                      {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                    .formatted(code));
        }
        UntilServed.scan(manager, LEFT, ARRIVED);

        assertEquals(201, fhir(LEFT, """
                {"resourceType":"Patient","name":[{"family":"Lahkuja"}]}""").statusCode());
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
                        URI.create(admin(ARRIVED) + "/import"))
                .header("Authorization", "Bearer " + systemToken(ARRIVED))
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
                        URI.create(manager.baseUrl(ARRIVED) + "/Patient?_summary=count"))
                .header("Authorization", "Bearer " + systemToken(ARRIVED)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(there.body().contains("\"total\":1"),
                "the record is not in the tenant that received the archive: " + there.body());
    }

    /** The archive, taken the way a customer takes one. */
    private static byte[] taken() throws Exception {
        HttpResponse<byte[]> archive = http.send(HttpRequest.newBuilder(
                        URI.create(admin(LEFT) + "/archive"))
                .header("Authorization", "Bearer " + systemToken(LEFT))
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

    private static String admin(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code + "/admin";
    }

    private static HttpResponse<String> fhir(String code, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(code) + "/Patient"))
                        .header("Authorization", "Bearer " + systemToken(code))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String systemToken(String code) throws Exception {
        manager.authority(code).ensureClient("keeper", "keeper-secret",
                List.of("system/*.read", "system/*.write"));
        String form = "grant_type=client_credentials&client_id=keeper&client_secret="
                + URLEncoder.encode("keeper-secret", StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + code + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString()).body()
                .replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
