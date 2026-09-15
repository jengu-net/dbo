package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.BlobStore;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content that is identifying as a whole — a recording, a scanned referral —
 * where taking it apart gains nothing and loses the thing somebody signed.
 *
 * <p>What makes erasure reach it is where it is kept, and nothing else. It
 * lives in the tenant's own database, so dropping the tenant drops it for the
 * same reason the records go: not because a sweep remembered a second system,
 * which is the kind of step that fails quietly and leaves somebody's recording
 * behind after they asked for it to be gone.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// The erasure case takes the tenant away, so it goes last rather than
// leaving the others with no tenant to write to.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ABlobIsTenantDataIT {

    private static final String CLINIC = "blob-klinik";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-blob");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ABlobIsTenantDataIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC));
        UntilServed.scan(manager, CLINIC);
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

    private BlobStore blobs() {
        return manager.runtime(CLINIC).orElseThrow().blobs();
    }

    @Test
    @Order(1)
    @DisplayName("a blob comes back as the bytes that were written, and a scan of one is not "
            + "re-encoded on the way")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void whatGoesInComesBack() {
        // Bytes that are not text and are not valid UTF-8: anything that
        // decoded and re-encoded them would corrupt this, silently.
        byte[] content = new byte[4096];
        new SecureRandom().nextBytes(content);
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xFE;
        content[2] = 0x00;

        String key = blobs().put(content, "application/pdf");
        Optional<BlobStore.Blob> read = blobs().get(key);

        assertTrue(read.isPresent(), "a blob written to this tenant cannot be read back");
        assertArrayEquals(content, read.get().content(),
                "the bytes came back changed, so what is stored is not the document that "
                        + "was signed");
        assertEquals("application/pdf", read.get().media(),
                "the media type is the writer's statement about their own content, and was "
                        + "not kept");
        assertEquals(4096, read.get().size());
    }

    @Test
    @Order(2)
    @DisplayName("a key this store did not issue names nothing, and a dropped blob is gone")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void nothingIsFoundUnderSomebodyElsesKey() {
        assertTrue(blobs().get("not-a-key").isEmpty());
        assertTrue(blobs().get("01920000-0000-7000-8000-000000000000").isEmpty(),
                "a well-formed key this store never issued found something");

        String key = blobs().put(new byte[] {1, 2, 3}, "application/octet-stream");
        assertTrue(blobs().drop(key), "dropping a blob that was here said it was not");
        assertTrue(blobs().get(key).isEmpty(), "a dropped blob is still readable");
        assertFalse(blobs().drop(key), "dropping the same blob twice said it was there twice");
    }

    /**
     * The one that matters. Not that the reference is gone — that is the
     * weaker thing a test proves by accident — but that the content itself
     * went with the tenant.
     */
    @Test
    @Order(3)
    @DisplayName("erasure-by-drop reaches the blobs, because they were in what was dropped")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void erasureByDropTakesTheContent() throws Exception {
        byte[] recording = new byte[2048];
        new SecureRandom().nextBytes(recording);
        String key = blobs().put(recording, "audio/ogg");
        assertTrue(blobs().get(key).isPresent(), "nothing was stored to erase");
        assertTrue(blobs().count() > 0);

        // Read from OUTSIDE the store's own interface: the row is really in
        // this tenant's database, so the claim is about where it is rather
        // than about what an implementation says.
        String url = SharedPostgres.urlFor("ABlobIsTenantDataIT")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + CLINIC.replace('-', '_'));
        assertEquals(1, rowsOfBlob(url, key),
                "the blob is not in the tenant's own database, so dropping the tenant would "
                        + "leave it wherever it actually is");

        manager.close();
        provisioner.deprovision(CLINIC);

        // And the database itself is gone, which is what took the content with
        // it. Asked of the server rather than of the store.
        try (Connection c = DriverManager.getConnection(
                     SharedPostgres.urlFor("ABlobIsTenantDataIT"),
                     postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname = ?")) {
            ps.setString(1, "tenant_" + CLINIC.replace('-', '_'));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "the tenant's database survived the drop, so nothing it held was "
                                + "erased — blobs included");
            }
        }
    }

    private long rowsOfBlob(String url, String key) throws Exception {
        try (Connection c = DriverManager.getConnection(url,
                     postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state.blob WHERE key = ?::uuid")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
