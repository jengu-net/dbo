package cloud.jengu.dbo.maintenance;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restore IS import (§11): the everyday import path loads what backup wrote —
 * every backup is implicitly restore-tested.
 *
 * <p><b>Portable</b> import writes through the ObjectStore: into a fresh
 * tenant it is a restore (fresh history); into the SAME tenant it is a no-op
 * (skip-if-byte-identical — REQ-DBO-MNT-PORTABLE-STATE-EXPORT).
 * <b>Fidelity</b> restore loads the COPY dumps byte-exact into an
 * initialized empty tenant: version ids, history and consumer cursors
 * preserved; serial sequences realigned.
 */
public final class TenantImport {

    private TenantImport() {}

    public record PortableResult(long imported, long skippedIdentical) {}

    public static PortableResult importPortable(ObjectStore target, InputStream sealed,
            byte[] ownerMasterKey) throws IOException {
        byte[] plain = SealedArchive.open(sealed, ownerMasterKey);
        long imported = 0;
        long skipped = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plain))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().startsWith("state/") || !entry.getName().endsWith(".ndjson")) {
                    continue;
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new NonClosing(zip), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String type = jsonString(line, "t");
                    String id = jsonString(line, "id");
                    byte[] resource = resourceOf(line);
                    var existing = target.get(type, id);
                    // compare through the same flattening the export applied
                    if (existing.isPresent() && Arrays.equals(
                            Names.flatten(new String(existing.get().payload(), StandardCharsets.UTF_8))
                                    .getBytes(StandardCharsets.UTF_8),
                            resource)) {
                        skipped++;
                        continue; // same tenant re-import: a no-op
                    }
                    target.put(new PutRequest(type, id, null, resource));
                    imported++;
                }
            }
        }
        return new PortableResult(imported, skipped);
    }

    /** Byte-faithful restore into an INITIALIZED, EMPTY tenant (schema present, no data). */
    public static void restoreFidelity(DataSource target, String domain, InputStream sealed,
            byte[] ownerMasterKey) throws IOException {
        Names.requireDomain(domain);
        byte[] plain = SealedArchive.open(sealed, ownerMasterKey);
        Map<String, String> dumps = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plain))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("fidelity/") && entry.getName().endsWith(".csv")) {
                    String table = entry.getName()
                            .substring("fidelity/".length(), entry.getName().length() - ".csv".length());
                    dumps.put(table, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        try (Connection c = target.getConnection()) {
            c.setAutoCommit(false);
            try {
                var copy = c.unwrap(PGConnection.class).getCopyAPI();
                for (Map.Entry<String, String> dump : dumps.entrySet()) {
                    String qualified = qualifiedTable(domain, dump.getKey());
                    try (PreparedStatement ps = c.prepareStatement("TRUNCATE " + qualified)) {
                        ps.executeUpdate();
                    }
                    copy.copyIn("COPY " + qualified + " FROM STDIN WITH (FORMAT csv)",
                            new StringReader(dump.getValue()));
                }
                // realign the outbox serial after the byte-exact load
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT setval(pg_get_serial_sequence('state.%s_outbox', 'seq'),
                                      COALESCE((SELECT max(seq) FROM state.%s_outbox), 0) + 1, false)"""
                        .formatted(domain, domain))) {
                    ps.execute();
                }
                c.commit();
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fidelity restore failed", e);
        }
    }

    /** Only table names the export itself wrote are accepted (validated identifier space). */
    private static String qualifiedTable(String domain, String archiveName) {
        String statePrefix = "state." + domain + "_";
        String historyPrefix = "history." + domain + "_";
        if (!archiveName.startsWith(statePrefix) && !archiveName.startsWith(historyPrefix)) {
            throw new IllegalArgumentException("unexpected fidelity table: " + archiveName);
        }
        String suffix = archiveName.substring(archiveName.indexOf('_') + 1);
        if (!suffix.matches("[a-z_]{1,32}")) {
            throw new IllegalArgumentException("unexpected fidelity table: " + archiveName);
        }
        return archiveName;
    }

    // ------------------------------------------------------------- plumbing

    private static String jsonString(String line, String field) {
        String needle = "\"" + field + "\":\"";
        int start = line.indexOf(needle) + needle.length();
        return line.substring(start, line.indexOf('"', start));
    }

    /** The raw payload: everything after "resource": up to the line's closing brace. */
    private static byte[] resourceOf(String line) {
        int idx = line.indexOf("\"resource\":") + "\"resource\":".length();
        return line.substring(idx, line.length() - 1).getBytes(StandardCharsets.UTF_8);
    }

    /** ZipInputStream must survive the per-entry readers. */
    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
        }
    }
}
