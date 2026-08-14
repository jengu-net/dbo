package cloud.jengu.dbo.maintenance;

import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backup IS export (§11, REQ-DBO-MNT-BACKUP-IS-EXPORT): one sealed archive,
 * two elements —
 *
 * <p><b>state/</b> — portable NDJSON, one line per live object with the raw
 * payload embedded (importable anywhere);
 * <b>fidelity/</b> — byte-faithful COPY dumps of the domain's state+history
 * tables (REQ-DBO-MNT-HISTORY-BY-SCHEMA).
 *
 * <p>Everything reads inside ONE repeatable-read transaction
 * (REQ-DBO-MNT-SNAPSHOT-CONSISTENT); the manifest carries the outbox fence,
 * so an incremental export is exactly the §10 feed from that cursor.
 */
public final class TenantExport {

    private static final List<String> STATE_TABLES =
            List.of("data", "identifier", "reference", "outbox", "consumer");

    private TenantExport() {}

    public record ExportResult(long objectCount, long outboxFence) {}

    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out) throws IOException {
        Names.requireDomain(domain);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                ByteArrayOutputStream plain = new ByteArrayOutputStream();
                ExportResult result;
                try (ZipOutputStream zip = new ZipOutputStream(plain)) {
                    result = writeArchive(c, domain, zip);
                }
                SealedArchive.seal(plain.toByteArray(), ownerMasterKey, out);
                c.rollback(); // read-only snapshot
                return result;
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("export failed", e);
        }
    }

    private static ExportResult writeArchive(Connection c, String domain, ZipOutputStream zip)
            throws SQLException, IOException {
        long fence;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(max(seq), 0) FROM state.%s_outbox".formatted(domain));
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            fence = rs.getLong(1);
        }

        // ---- portable state element, one ndjson per type
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> types = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT type FROM state.%s_data WHERE NOT deleted ORDER BY type"
                        .formatted(domain));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                types.add(rs.getString(1));
            }
        }
        long total = 0;
        for (String type : types) {
            zip.putNextEntry(new ZipEntry("state/" + type + ".ndjson"));
            long n = 0;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, version_id, payload_version, last_updated, payload
                    FROM state.%s_data WHERE type = ? AND NOT deleted ORDER BY id"""
                    .formatted(domain))) {
                ps.setString(1, type);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String line = "{\"t\":%s,\"id\":%s,\"v\":%d,\"pv\":%s,\"lu\":%s,\"resource\":%s}\n"
                                .formatted(Names.quote(type),
                                        Names.quote(rs.getObject(1).toString()),
                                        rs.getLong(2),
                                        Names.quote(rs.getString(3)),
                                        Names.quote(rs.getTimestamp(4).toInstant().toString()),
                                        Names.flatten(new String(rs.getBytes(5), StandardCharsets.UTF_8)));
                        zip.write(line.getBytes(StandardCharsets.UTF_8));
                        n++;
                    }
                }
            }
            zip.closeEntry();
            counts.put(type, n);
            total += n;
        }

        // ---- byte-faithful fidelity element: COPY dumps of state + history
        var copy = c.unwrap(PGConnection.class).getCopyAPI();
        for (String table : STATE_TABLES) {
            zip.putNextEntry(new ZipEntry("fidelity/state." + domain + "_" + table + ".csv"));
            StringWriter csv = new StringWriter();
            try {
                copy.copyOut("COPY state.%s_%s TO STDOUT WITH (FORMAT csv)".formatted(domain, table), csv);
            } catch (SQLException e) {
                throw new IllegalStateException("fidelity dump failed for " + table, e);
            }
            zip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.putNextEntry(new ZipEntry("fidelity/history." + domain + "_history.csv"));
        StringWriter csv = new StringWriter();
        try {
            copy.copyOut("COPY history.%s_history TO STDOUT WITH (FORMAT csv)".formatted(domain), csv);
        } catch (SQLException e) {
            throw new IllegalStateException("fidelity dump failed for history", e);
        }
        zip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        // ---- §14 vault (present only under PDI): wrapped keys, HMAC index,
        // shred ledger — ciphertext and key material only, blind to the
        // operator by construction; the identifying data itself rides
        // encrypted inside the payload dumps above
        for (String pdiTable : List.of("person", "identifier", "shred_ledger")) {
            if (!tableExists(c, "pdi", pdiTable)) {
                continue;
            }
            zip.putNextEntry(new ZipEntry("fidelity/pdi." + pdiTable + ".csv"));
            StringWriter pdiCsv = new StringWriter();
            try {
                copy.copyOut("COPY pdi.%s TO STDOUT WITH (FORMAT csv)".formatted(pdiTable), pdiCsv);
            } catch (SQLException e) {
                throw new IllegalStateException("fidelity dump failed for pdi." + pdiTable, e);
            }
            zip.write(pdiCsv.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        // ---- manifest
        zip.putNextEntry(new ZipEntry("manifest.json"));
        StringBuilder manifest = new StringBuilder();
        manifest.append("{\"domain\":").append(Names.quote(domain))
                .append(",\"outboxFence\":").append(fence)
                .append(",\"objectCount\":").append(total)
                .append(",\"types\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (!first) manifest.append(',');
            first = false;
            manifest.append(Names.quote(e.getKey())).append(':').append(e.getValue());
        }
        manifest.append("}}");
        zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        return new ExportResult(total, fence);
    }

    private static boolean tableExists(Connection c, String schema, String table) {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?""")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("table existence check failed", e);
        }
    }
}
