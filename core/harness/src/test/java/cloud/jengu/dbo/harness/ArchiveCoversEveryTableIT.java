package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A backup carries every table, or names what it left.
 *
 * <p>The export used to hold a hand-written list of five tables against a
 * schema that had grown to ten. Nothing failed: a table missing from a backup
 * looks exactly like a table that never existed, and the loss only appears
 * during a restore, which is the worst moment to discover it.
 *
 * <p>So the archive is checked against the database rather than against a
 * list. Add a table and forget the export, and this fails naming it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveCoversEveryTableIT {

    private static final byte[] OWNER_KEY = new byte[32];
    static PGSimpleDataSource ds;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        String jdbcUrl = SharedPostgres.urlFor("ArchiveCoversEveryTableIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE archive_coverage");
        }
        ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "archive_coverage");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        R4Personality p = new R4Personality(List.of(FhirTypeConfig.internal("Observation")));
        PgObjectStore store = new PgObjectStore(ds, p.registrations());
        store.put(PutRequest.create("Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"final\"}"
                        .getBytes(StandardCharsets.UTF_8)));

        // A table the export was never told about — standing in for the next one
        // somebody adds without remembering the archive.
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS state.%s_late_arrival (id int)"
                    .formatted(R4Personality.DOMAIN));
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("every table in the tenant's state schema is in the archive")
    void theArchiveCoversEveryStateTable() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, R4Personality.DOMAIN, OWNER_KEY, out);

        TreeSet<String> inDatabase = new TreeSet<>();
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'state'""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                inDatabase.add(rs.getString(1));
            }
        }

        TreeSet<String> inArchive = new TreeSet<>();
        try (InputStream plain = SealedArchive.opening(
                new ByteArrayInputStream(out.toByteArray()), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("fidelity/state.") && name.endsWith(".csv")) {
                    inArchive.add(name.substring("fidelity/state.".length(),
                            name.length() - ".csv".length()));
                }
            }
        }

        List<String> missing = new ArrayList<>(inDatabase);
        missing.removeAll(inArchive);
        // Delivery state is left out on purpose and is named here, so
        // the guard keeps its value: an exclusion has to be a line somebody
        // wrote, and any OTHER table going missing still fails this test.
        missing.removeAll(List.of(R4Personality.DOMAIN + "_consumer",
                R4Personality.DOMAIN + "_subscription_dlq",
                R4Personality.DOMAIN + "_topic_counter"));
        assertEquals(List.of(), missing,
                "these tables exist in the tenant and are not in its backup — a table missing "
                        + "from an archive is indistinguishable from one that never existed, and "
                        + "the loss surfaces during a restore");

        assertFalse(inArchive.contains(R4Personality.DOMAIN + "_consumer"),
                "the delivery cursor must not travel: restoring it re-sends every event "
                        + "delivered between the cursor and the outbox head");
    }
}
