package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.BlobStore;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p><b>One test, and it takes its tenant away.</b> What content does on the
 * way in and out is proven over the door a consumer actually has, on a shared
 * tenant, by {@link ContentHeldWholeIsReachableOverTheWireIT}. What is left
 * here is the claim that needs a world of its own: this one deprovisions the
 * tenant it wrote to, which no shared world survives.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

    /**
     * The one that matters. Not that the reference is gone — that is the
     * weaker thing a test proves by accident — but that the content itself
     * went with the tenant.
     */
    @Test
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
