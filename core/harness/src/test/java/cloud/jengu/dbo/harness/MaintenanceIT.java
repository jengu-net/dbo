package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backup is export, restore is import — sealed with the owner's key. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenanceIT {

    static final String EID = "https://ee.ee/eid";
    static final byte[] OWNER_KEY = ownerKey();
    static final byte[] WRONG_KEY = ownerKey();

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static PGSimpleDataSource dsA;
    static PGSimpleDataSource dsB;
    static PGSimpleDataSource dsC;
    static R4Personality personality;
    static PgObjectStore engineA;
    static R4Store tenantA;
    static String patientId;
    static byte[] archive;
    static long fence;

    static byte[] ownerKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("MaintenanceIT");
        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE mnt_a");
            st.execute("CREATE DATABASE mnt_b");
            st.execute("CREATE DATABASE mnt_c");
        }
        String baseUrl = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        dsA = ds(baseUrl + "mnt_a");
        dsB = ds(baseUrl + "mnt_b");
        dsC = ds(baseUrl + "mnt_c");

        personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation")));
        engineA = new PgObjectStore(dsA, personality.registrations());
        tenantA = new R4Store(engineA, personality, "https://a.test");

        PutResult p = tenantA.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"36012120001"}],
                 "name":[{"family":"Varundatav"}]}""".formatted(EID));
        patientId = p.id();
        tenantA.update(patientId, 1L, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"36012120001"}],
                 "name":[{"family":"Varundatav Uuendatud"}]}""".formatted(EID));
        tenantA.create("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"MNT-1"}]},
                 "subject":{"reference":"Patient/%s"}}""".formatted(patientId));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.ExportResult result = TenantExport.export(dsA, R4Personality.DOMAIN, OWNER_KEY, out);
        archive = out.toByteArray();
        fence = result.outboxFence();
        assertEquals(2, result.objectCount());
    }

    @AfterAll
    void down() {
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /** Portable restore into a fresh tenant: same ids, searches hit, fresh history. */
    @Test
    void portableImportRestoresIntoAFreshTenant() throws Exception {
        PgObjectStore engineB = new PgObjectStore(dsB, personality.registrations());
        R4Store tenantB = new R4Store(engineB, personality, "https://b.test");

        var result = CoSignedArchive.over(archive, OWNER_KEY)
                .importInto(engineB, OWNER_KEY, TenantImport.HistoryMode.FRESH);
        assertEquals(2, result.imported());

        StoredObject restored = engineB.get("Patient", patientId).orElseThrow();
        assertEquals(1, restored.versionId(), "portable restore starts fresh history");
        assertTrue(new String(restored.payload(), StandardCharsets.UTF_8)
                .contains("Varundatav Uuendatud"));
        String found = tenantB.search("Patient",
                Map.of("identifier", EID + "|36012120001"), null);
        assertTrue(found.contains(patientId), "identity searchable after restore");
    }

    /** Re-import into the SAME tenant is a no-op (REQ-DBO-MNT-PORTABLE-STATE-EXPORT). */
    @Test
    void reimportIntoTheSameTenantIsANoOp() throws Exception {
        long versionBefore = engineA.get("Patient", patientId).orElseThrow().versionId();
        var result = CoSignedArchive.over(archive, OWNER_KEY)
                .importInto(engineA, OWNER_KEY, TenantImport.HistoryMode.FRESH);
        assertEquals(0, result.imported());
        assertEquals(2, result.skippedIdentical());
        assertEquals(versionBefore, engineA.get("Patient", patientId).orElseThrow().versionId(),
                "no new versions on idempotent re-import");
    }

    /** The platform cannot read what it operates: wrong key fails; no plaintext in the file. */
    @Test
    void wrongKeyFailsAndArchiveCarriesNoPlaintext() throws Exception {
        // signed properly, then opened with the wrong key: the refusal is the
        // seal's, before any signature is even looked at
        CoSignedArchive signed = CoSignedArchive.over(archive, OWNER_KEY);
        assertThrows(IllegalArgumentException.class, () ->
                signed.importInto(engineA, WRONG_KEY, TenantImport.HistoryMode.FRESH));
        String raw = new String(archive, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("Varundatav"), "payload text must not appear in the sealed archive");
        assertFalse(raw.contains("36012120001"), "identifiers must not appear in the sealed archive");
    }

    /** Fidelity restore: version ids and full history byte-equal; outbox sequence continues. */
    @Test
    void fidelityRestoreIsByteFaithful() throws Exception {
        PgObjectStore engineC = new PgObjectStore(dsC, personality.registrations());
        TenantImport.restoreFidelity(dsC, R4Personality.DOMAIN,
                new ByteArrayInputStream(archive), OWNER_KEY);

        StoredObject restored = engineC.get("Patient", patientId).orElseThrow();
        assertEquals(2, restored.versionId(), "fidelity preserves version ids");
        assertArrayEquals(engineA.get("Patient", patientId).orElseThrow().payload(),
                restored.payload());

        List<StoredObject> history = engineC.history("Patient", patientId);
        assertEquals(2, history.size(), "full history restored");
        assertTrue(new String(history.get(0).payload(), StandardCharsets.UTF_8)
                .contains("\"Varundatav\""));

        // the outbox sequence continues: a NEW write must not collide
        R4Store tenantC = new R4Store(engineC, personality, "https://c.test");
        tenantC.create("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"MNT-2"}]},
                 "subject":{"reference":"Patient/%s"}}""".formatted(patientId));
        List<FeedItem> items = new PgChangeFeed(dsC, R4Personality.DOMAIN)
                .read(null, 100).items();
        assertTrue(items.size() >= 4, "restored outbox + the new event");
        long maxSeq = items.get(items.size() - 1).seq();
        assertTrue(items.stream().filter(i -> i.seq() == maxSeq).count() == 1);
    }

    /** The manifest fence: post-export writes sit strictly after the cursor — incremental = the feed. */
    @Test
    void manifestFenceMakesIncrementalExportTheFeed() {
        PutResult after = tenantA.create("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"MNT-AFTER"}]},
                 "subject":{"reference":"Patient/%s"}}""".formatted(patientId));

        List<FeedItem> all = new PgChangeFeed(dsA, R4Personality.DOMAIN).read(null, 500).items();
        List<FeedItem> incremental = all.stream().filter(i -> i.seq() > fence).toList();
        assertTrue(incremental.stream().anyMatch(i -> i.objectId().equals(after.id())),
                "post-export write must appear after the fence");
        assertTrue(all.stream().filter(i -> i.seq() <= fence)
                        .noneMatch(i -> i.objectId().equals(after.id())),
                "and never before it");
    }
}
