package cloud.jengu.dbo.maintenance;

import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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

    /**
     * The fidelity dumps cover <b>every</b> table in the {@code state} schema
     * that belongs to this tenant, discovered rather than listed.
     *
     * <p>A hand-maintained list drifts from what it describes, silently, in
     * the direction that loses data: it carried five of the ten domain tables,
     * so a backup restored the delivery cursor while dropping the dead-letter
     * queues, the replication bookkeeping and the terminology store — and
     * nothing said so, because a missing table looks exactly like a table that
     * was never there.
     *
     * <p>Discovery defaults to including. A backup carrying something it did
     * not need is recoverable; one that silently omitted something is not.
     * Where a class of data genuinely must not travel, that is a declared
     * property of the type rather than an omission from a list
     * (jengu-platform#870).
     */
    private static List<String> stateTablesOf(Connection c, String domain) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'state'
                  AND (table_name LIKE ? OR table_name LIKE 'term\\_%')
                ORDER BY table_name""")) {
            ps.setString(1, domain + "\\_%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private TenantExport() {}

    public record ExportResult(long objectCount, long outboxFence) {}

    /**
     * Exports without type knowledge — every stored type travels.
     *
     * <p>Kept for callers that hold no registry. It cannot honour a type
     * declared as never travelling, because it cannot tell one type from
     * another; pass the registrations to get that guarantee
     * (jengu-platform#870).
     */
    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out) throws IOException {
        return export(ds, domain, ownerMasterKey, out, List.of());
    }

    /**
     * Exports, honouring each type's declared travel (jengu-platform#870).
     *
     * <p>A type whose handling says it never leaves is excluded from both
     * representations — the portable NDJSON and the byte-faithful dumps — so
     * "it cannot be placed in any archive" holds however the archive is read,
     * rather than only in the half somebody remembered.
     */
    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out, List<TypeRegistration> types) throws IOException {
        Names.requireDomain(domain);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                // #35: sealed as it is written. The archive never exists whole
                // in memory — a tenant's history is the volume, and holding it
                // twice (plain and ciphertext) fails on the first real hospital.
                ExportResult result;
                DigestingZip zip = null;
                try (OutputStream sealed = SealedArchive.sealing(ownerMasterKey, out)) {
                    zip = new DigestingZip(new ZipOutputStream(sealed));
                    result = writeArchive(c, domain, zip, grounded(types));
                    // The manifest can only be written once every digest is
                    // known, so it goes last — which is also why verification
                    // needs its own pass before an import writes anything.
                    zip.writeManifest();
                    zip.zip().finish();
                }
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


    /**
     * Writes entries and digests them on the way past (#34, #35).
     *
     * <p>Digesting during the write is what lets the manifest exist without a
     * second pass over the data: only names and digests accumulate, and those
     * are kilobytes however large the tenant is.
     */
    /**
     * Streams one {@code COPY … TO STDOUT} straight into an archive entry.
     *
     * <p>The dump is the byte-faithful element of the archive, which means it
     * is also the largest: a tenant's whole history, uncompressed, as CSV.
     * Materialising it before writing made the export need memory
     * proportional to the biggest table — the one shape guaranteed to fail on
     * exactly the tenants for whom leaving matters most (#35).
     */
    private static void dumpInto(org.postgresql.copy.CopyManager copy, DigestingZip zip,
            String entryName, String sql, String what) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        try (OutputStream entry = zip.entryStream()) {
            copy.copyOut(sql, entry);
        } catch (SQLException e) {
            throw new IllegalStateException("fidelity dump failed for " + what, e);
        }
        zip.closeEntry();
    }

    static final class DigestingZip {
        private final ZipOutputStream zip;
        private final java.util.TreeMap<String, String> digests = new java.util.TreeMap<>();
        private java.security.MessageDigest current;
        private String currentName;

        DigestingZip(ZipOutputStream zip) {
            this.zip = zip;
        }

        ZipOutputStream zip() {
            return zip;
        }

        void putNextEntry(ZipEntry entry) throws IOException {
            zip.putNextEntry(entry);
            currentName = entry.getName();
            try {
                current = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        void write(byte[] bytes) throws IOException {
            zip.write(bytes);
            current.update(bytes);
        }

        /**
         * The current entry as an {@link OutputStream}, so a producer that
         * writes into a stream — {@code COPY … TO STDOUT}, above all — can
         * write through to the archive instead of handing back a String that
         * has to exist all at once.
         *
         * <p>Closing it is a no-op: the entry is closed by
         * {@link #closeEntry()}, and a stream that closed the ZIP underneath
         * the caller would end the archive at the first table.
         */
        OutputStream entryStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    zip.write(b);
                    current.update((byte) b);
                }

                @Override
                public void write(byte[] bytes, int off, int len) throws IOException {
                    zip.write(bytes, off, len);
                    current.update(bytes, off, len);
                }
            };
        }

        void closeEntry() throws IOException {
            zip.closeEntry();
            digests.put(currentName, ArchiveManifest.hex(current.digest()));
            current = null;
            currentName = null;
        }

        /** The last entry: everything before it, digested, and one root over all. */
        void writeManifest() throws IOException {
            java.util.List<ArchiveManifest.Entry> entries = new ArrayList<>();
            digests.forEach((name, digest) -> entries.add(new ArchiveManifest.Entry(name, digest)));
            ArchiveManifest manifest = new ArchiveManifest(entries, ArchiveManifest.rootOf(entries));
            zip.putNextEntry(new ZipEntry(ArchiveManifest.MANIFEST_ENTRY));
            zip.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /**
     * A COPY that leaves out the rows of types which never travel.
     *
     * <p>The byte-faithful dumps are whole tables, so honouring travel here
     * means excluding rows rather than files. Without this the guarantee would
     * hold in the portable representation and quietly fail in the other one,
     * which is the worse of the two failures: the archive would look correct.
     *
     * <p>Type names are inlined because {@code TypeRegistration} validates them
     * against {@code [A-Za-z][A-Za-z0-9]*} at construction — there is no route
     * from user input to this string.
     */
    private static String copyOf(String qualifiedTable, Set<String> grounded) {
        if (grounded.isEmpty()) {
            return "COPY " + qualifiedTable + " TO STDOUT WITH (FORMAT csv)";
        }
        String excluded = grounded.stream().map(t -> "'" + t + "'")
                .collect(java.util.stream.Collectors.joining(","));
        return "COPY (SELECT * FROM " + qualifiedTable + " WHERE type NOT IN (" + excluded
                + ")) TO STDOUT WITH (FORMAT csv)";
    }

    /**
     * The object's identity codes as a JSON array — the answer to "which thing
     * is this?" for a reader who cannot resolve our ids (ADR 0050).
     *
     * <p>Several, not one: a corrected code becomes a new claim and the
     * previous value stays resolvable, so an object matched by an older code
     * is still matched.
     */
    private static String identityCodes(String aggregated) {
        if (aggregated == null || aggregated.isEmpty()) {
            return "[]";
        }
        StringBuilder codes = new StringBuilder("[");
        String[] parts = aggregated.split("\u001f");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                codes.append(',');
            }
            codes.append(Names.quote(parts[i]));
        }
        return codes.append(']').toString();
    }

    /** The type names that must not appear in any archive. */
    private static Set<String> grounded(List<TypeRegistration> types) {
        Set<String> never = new java.util.LinkedHashSet<>();
        for (TypeRegistration type : types) {
            if (!type.handling().travelsInBackup()) {
                never.add(type.typeName());
            }
        }
        return never;
    }

    private static ExportResult writeArchive(Connection c, String domain, DigestingZip out,
            Set<String> grounded) throws SQLException, IOException {
        DigestingZip zip = out;
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
                String type = rs.getString(1);
                if (!grounded.contains(type)) {
                    types.add(type);
                }
            }
        }
        long total = 0;
        for (String type : types) {
            zip.putNextEntry(new ZipEntry("state/" + type + ".ndjson"));
            long n = 0;
            // The identity codes travel beside the object, never inside it.
            // Rewriting a payload to carry them would change the bytes the
            // version chain and both signatures are over (ADR 0052) — the
            // archive would arrive self-contradicting. Alongside, a reader who
            // knows nothing of our ids can still say which thing this is.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.id, d.version_id, d.payload_version, d.last_updated, d.payload,
                           COALESCE((SELECT string_agg(i.system || '|' || i.value, '\u001f'
                                                       ORDER BY i.system, i.value)
                                     FROM state.%s_identifier i
                                     WHERE i.object_id = d.id AND i.identity), '')
                    FROM state.%s_data d WHERE d.type = ? AND NOT d.deleted ORDER BY d.id"""
                    .formatted(domain, domain))) {
                ps.setString(1, type);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String line = "{\"t\":%s,\"id\":%s,\"v\":%d,\"pv\":%s,\"lu\":%s,\"ic\":%s,\"resource\":%s}\n"
                                .formatted(Names.quote(type),
                                        Names.quote(rs.getObject(1).toString()),
                                        rs.getLong(2),
                                        Names.quote(rs.getString(3)),
                                        Names.quote(rs.getTimestamp(4).toInstant().toString()),
                                        identityCodes(rs.getString(6)),
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
        // The terminology tables are tenant-scoped rather than domain-scoped, so
        // a tenant serving two domains repeats them in each archive. Duplication
        // that costs disk beats an archive whose codes cannot be resolved.
        for (String table : stateTablesOf(c, domain)) {
            dumpInto(copy, zip, "fidelity/state." + table + ".csv",
                    copyOf("state." + table, table.equals(domain + "_data") ? grounded : Set.of()),
                    table);
        }
        dumpInto(copy, zip, "fidelity/history." + domain + "_history.csv",
                copyOf("history." + domain + "_history", grounded),
                "history");

        // ---- §14 vault (present only under PDI): wrapped keys, HMAC index,
        // shred ledger — ciphertext and key material only, blind to the
        // operator by construction; the identifying data itself rides
        // encrypted inside the payload dumps above
        for (String pdiTable : List.of("person", "identifier", "shred_ledger")) {
            if (!tableExists(c, "pdi", pdiTable)) {
                continue;
            }
            dumpInto(copy, zip, "fidelity/pdi." + pdiTable + ".csv",
                    "COPY pdi.%s TO STDOUT WITH (FORMAT csv)".formatted(pdiTable),
                    "pdi." + pdiTable);
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
