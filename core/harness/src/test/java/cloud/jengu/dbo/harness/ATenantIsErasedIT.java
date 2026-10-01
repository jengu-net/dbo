package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-A-TENANT-IS-ERASED: a tenant is erased by dropping its database, and
 * everything it held goes with it.
 *
 * <p><b>Why not in Rowling Land.</b> Erasure is an operator act with no door:
 * nothing a deployment serves reaches it, so it is asked of the provisioner
 * directly, and a world whose tenants other stories are walking cannot have
 * one of them dropped under it. A tenant's life short of erasure — coming up,
 * being retracted and declared again — is walked in the tenant-opening story.
 *
 * <p>One runtime, two tenants: a clinic holding a recording, and one holding
 * records. Each is retracted and then dropped, and the drop is asked of the
 * database server rather than of the store, because what is claimed is that
 * the content is gone from where it was, not that an interface stopped
 * answering for it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantIsErasedIT {

    private static final String RECORDING = "blob-klinik";
    private static final String RECORDS = "aiakas";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("ATenantIsErasedIT");
        dir = Files.createTempDirectory("dbo-erased");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        declare(RECORDING);
        declare(RECORDS);
        UntilServed.scan(manager, RECORDING, RECORDS);
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

    // ── content that is identifying as a whole goes with its tenant ──

    /**
     * Content that is identifying as a whole — a recording, a scanned
     * referral — where taking it apart gains nothing and loses the thing
     * somebody signed. What makes erasure reach it is where it is kept: in
     * the tenant's own database, so dropping the tenant drops it for the same
     * reason the records go, not because a sweep remembered a second system.
     *
     * <p>Not that the reference is gone — that is the weaker thing a test
     * proves by accident — but that the content itself went with the tenant.
     */
    @Test
    @Order(1)
    @DisplayName("erasure-by-drop reaches the blobs, because they were in what was dropped")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void erasureByDropTakesTheContent() throws Exception {
        var blobs = manager.runtime(RECORDING).orElseThrow().blobs();
        byte[] recording = new byte[2048];
        new SecureRandom().nextBytes(recording);
        String key = blobs.put(recording, "audio/ogg");
        assertTrue(blobs.get(key).isPresent(), "nothing was stored to erase");
        assertTrue(blobs.count() > 0);
        assertEquals(1, rowsOfBlob(key),
                "the blob is not in the tenant's own database, so dropping the tenant would "
                        + "leave it wherever it actually is");

        retractAndDrop(RECORDING);
        assertEquals(0, databasesNamed(RECORDING),
                "the tenant's database survived the drop, so nothing it held was erased — "
                        + "blobs included");
    }

    // ── and erasure is the drop, for any code a tenant can have ──

    @Test
    @Order(2)
    @DisplayName("erasure is only the explicit deprovision, and it drops the database")
    @Proving(DboPromises.TEN_ERASURE_BY_DROP)
    void deprovisionDropsTheDatabase() throws Exception {
        manager.runtime(RECORDS).orElseThrow().engine().put(PutRequest.create("Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Aiakas\"}]}"
                        .getBytes(StandardCharsets.UTF_8)));
        retractAndDrop(RECORDS);
        assertEquals(0, databasesNamed(RECORDS), "erasure-by-drop must remove the database");
        assertFalse(manager.codes().contains(RECORDS));
    }

    /**
     * The codes tenants actually have, not the short ones tests pick: a spec
     * accepts a hyphen and up to a hundred and twenty-eight characters, and
     * the drop has to consider every name a spec does.
     */
    @Test
    @Order(3)
    @DisplayName("erasure takes every code a declaration accepts, and refuses one it would not")
    @Proving(DboPromises.TEN_ERASURE_BY_DROP)
    void erasureTakesTheCodesASpecAccepts() {
        // Nothing of these names was provisioned, so what is asked is whether
        // the drop will CONSIDER the name at all.
        provisioner.deprovision("mingi-pikem-nimi");
        provisioner.deprovision("a-tenant-whose-code-is-considerably-longer-than-sixteen");

        IllegalArgumentException notACode = assertThrows(IllegalArgumentException.class,
                () -> provisioner.deprovision("Not A Code"));
        assertTrue(notACode.getMessage().contains("Not A Code"), notACode.getMessage());
    }

    // ── plumbing ──

    private static void declare(String code) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code));
    }

    /** Retraction first, which releases the tenant's pool; then the drop. */
    private static void retractAndDrop(String code) throws Exception {
        Files.delete(dir.resolve(code + ".json"));
        manager.scanOnce();
        provisioner.deprovision(code);
    }

    private static long databasesNamed(String code) throws Exception {
        try (Connection c = DriverManager.getConnection(
                     jdbcUrl, postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname = ?")) {
            ps.setString(1, "tenant_" + code.replace('-', '_'));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long rowsOfBlob(String key) throws Exception {
        String url = jdbcUrl.replaceAll("/[^/?]+(\\?.*)?$",
                "/tenant_" + RECORDING.replace('-', '_'));
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
