package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-VENDOR-CHANGE, walked in order.
 *
 * <p>The clinic is leaving. Whatever the reason, the question is the same one
 * every provider should be able to ask before they sign anything: <b>can I
 * get everything out, and can somebody else read it without asking you?</b>
 *
 * <p>The answer here is one mechanism rather than two. Backup and export are
 * the same operation, so the thing that runs nightly is the thing that leaves
 * — which means the escape route is exercised every night rather than for the
 * first time on the worst day of the relationship.
 *
 * <p>And the archive is sealed to a key the clinic holds, so the party that
 * operates the store cannot read what it is holding for them. The platform is
 * a custodian, not a reader.
 *
 * <p><b>One clinic's estate, out and back in somewhere else, in dependency
 * order.</b>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheClinicChangesVendorIT {

    private static final String EID = "https://ee.ee/eid";
    private static final byte[] THEIR_KEY = key();
    private static final byte[] SOMEBODY_ELSES_KEY = key();

    static PostgreSQLContainer<?> postgres;
    static PGSimpleDataSource here;
    static PGSimpleDataSource elsewhere;
    static R4Personality personality;
    static PgObjectStore engineHere;
    static R4Store clinic;
    static byte[] archive;
    static String patientId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        String jdbcUrl = SharedPostgres.urlFor("TheClinicChangesVendorIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                        postgres.getUsername(), postgres.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE vendor_here");
            st.execute("CREATE DATABASE vendor_elsewhere");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        here = ds(base + "vendor_here");
        elsewhere = ds(base + "vendor_elsewhere");

        personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation")));
        engineHere = new PgObjectStore(here, personality.registrations());
        clinic = new R4Store(engineHere, personality, "https://kevad.example");

        patientId = clinic.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Tamm","given":["Liis"]}]}""".formatted(EID)).id();
        clinic.create("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"VEND-1"}]},
                 "subject":{"reference":"Patient/%s"}}""".formatted(patientId));
    }

    // ── everything leaves as one sealed file ──

    @Test
    @Order(1)
    @DisplayName("the whole estate leaves as one archive, sealed to a key the clinic holds, "
            + "with nothing readable in the file by whoever is storing it")
    @Proving({DboPromises.MNT_BACKUP_IS_EXPORT, DboPromises.MNT_OWNER_KEY_ENCRYPTION,
            DboPromises.MNT_SNAPSHOT_CONSISTENT})
    void everythingLeavesSealed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.ExportResult result =
                TenantExport.export(here, R4Personality.DOMAIN, THEIR_KEY, out);
        archive = out.toByteArray();

        assertEquals(2, result.objectCount(),
                "the export is a snapshot of the estate rather than of whatever the writer "
                        + "happened to see mid-stream");

        // The same operation runs nightly. An escape route exercised only on
        // the day somebody leaves is an escape route nobody has tested.
        String raw = new String(archive, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("Tamm"),
                "a name is readable in the file the platform is storing, so the custodian "
                        + "can read what it holds");
        assertFalse(raw.contains("49001010000"),
                "and so is a national identifier");
    }

    @Test
    @Order(2)
    @DisplayName("somebody else's key opens nothing, and the refusal comes from the seal "
            + "before any signature is looked at")
    @Proving(DboPromises.MNT_OWNER_KEY_ENCRYPTION)
    void somebodyElsesKeyOpensNothing() throws Exception {
        CoSignedArchive sealed = CoSignedArchive.over(archive, THEIR_KEY);
        PgObjectStore fresh = new PgObjectStore(elsewhere, personality.registrations());

        assertThrows(IllegalArgumentException.class,
                () -> sealed.importInto(fresh, SOMEBODY_ELSES_KEY,
                        TenantImport.HistoryMode.FRESH),
                "an archive opened with the wrong key produced something, so the key is a "
                        + "formality rather than the thing that protects the file");
    }

    // ── and goes back in somewhere else ──

    @Test
    @Order(3)
    @DisplayName("it restores into a store the clinic's old vendor does not run, keeping the "
            + "identities the clinic's other systems already refer to")
    @Proving({DboPromises.MNT_PORTABLE_STATE_EXPORT, DboPromises.CORE_REINDEX_IS_AN_OPERATION})
    void itRestoresElsewhereWithTheSameIdentities() throws Exception {
        PgObjectStore engineElsewhere = new PgObjectStore(elsewhere, personality.registrations());
        R4Store newVendor = new R4Store(engineElsewhere, personality, "https://sugis.example");

        var result = CoSignedArchive.over(archive, THEIR_KEY)
                .importInto(engineElsewhere, THEIR_KEY, TenantImport.HistoryMode.FRESH);
        assertEquals(2, result.imported());

        StoredObject restored = engineElsewhere.get("Patient", patientId).orElseThrow();
        assertTrue(new String(restored.payload(), StandardCharsets.UTF_8).contains("Tamm"),
                "the record came back readable to the party holding the key");

        // Searchable, which is the part that would be invisible if it were
        // wrong: projections are derived from the payload and rebuilt on the
        // way in, so a restore is not a pile of documents nobody can find.
        assertTrue(newVendor.search("Patient", Map.of("identifier", EID + "|49001010000"), null)
                        .contains(patientId),
                "she is findable in the new store by the identifier her other systems know "
                        + "her by, which is what makes this a move rather than a dump");
    }

    @Test
    @Order(4)
    @DisplayName("history is restored by a choice the operator makes rather than by whatever "
            + "the archive happened to contain")
    @Proving(DboPromises.MNT_HISTORY_BY_SCHEMA)
    void historyIsRestoredByChoice() throws Exception {
        StoredObject restored = new PgObjectStore(elsewhere, personality.registrations())
                .get("Patient", patientId).orElseThrow();

        assertEquals(1, restored.versionId(),
                "a portable restore starts fresh history: the new store is not pretending to "
                        + "have witnessed edits it never saw, which is a different claim from "
                        + "a backup restored into the store that made it");
    }

    @Test
    @Order(5)
    @DisplayName("restoring the same archive twice changes nothing, because a restore that "
            + "is not idempotent cannot be retried after a failure halfway")
    @Proving(DboPromises.MNT_PORTABLE_STATE_EXPORT)
    void restoringTwiceChangesNothing() throws Exception {
        PgObjectStore engineElsewhere = new PgObjectStore(elsewhere, personality.registrations());
        long before = engineElsewhere.get("Patient", patientId).orElseThrow().versionId();

        var again = CoSignedArchive.over(archive, THEIR_KEY)
                .importInto(engineElsewhere, THEIR_KEY, TenantImport.HistoryMode.FRESH,
                        cloud.jengu.dbo.fhir.r4.R4FhirVersion.INSTANCE.face()
                                .require(cloud.jengu.dbo.core.face.DocumentEquivalence.class));

        assertEquals(0, again.imported(), "nothing was written the second time");
        assertEquals(2, again.skippedIdentical(),
                "and what was already there was recognised as identical rather than rewritten");
        assertEquals(before, engineElsewhere.get("Patient", patientId).orElseThrow().versionId(),
                "so a retried restore does not add a version per attempt");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private static byte[] key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
