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

    /**
     * Where a sealed archive can be read from, more than once (#35).
     *
     * <p>Two passes are required and not a choice: the digest list is the last
     * entry, so a single pass would be importing before it could check, and
     * AES-GCM only authenticates at the tag, so a one-pass reader necessarily
     * writes unauthenticated data. A one-shot {@link InputStream} cannot
     * express that, so the caller supplies something re-openable.
     */
    @FunctionalInterface
    public interface ArchiveSource {
        InputStream open() throws IOException;
    }

    /**
     * What an import does with the versions the archive carries.
     *
     * <p>Two callers want opposite things, and conflating them silently
     * redefines one of them — which is exactly what happened here until a
     * test said so.
     */
    public enum HistoryMode {
        /**
         * The destination assigns its own versions and moments. A restore into
         * a fresh tenant is a new life for the data; nothing is claiming the
         * old versions happened here.
         */
        FRESH,
        /**
         * The archive's own versions and moments are kept — the shape a store
         * migration needs, because a version's timestamp is the evidence of
         * what somebody knew at a moment (ADR 0047 §7). Replayed in ascending
         * order, and a version already present is skipped rather than
         * rewritten, so a resumed move does not duplicate what landed.
         */
        PRESERVED
    }

    /**
     * Verifies the archive whole, then imports it (#34, #35, ADR 0052 §5).
     *
     * <p>Nothing is written until the digests match and both signatures
     * verify. A partially-applied archive leaves a tenant in a state neither
     * party attested, with nobody able to say which half is which.
     *
     * @return the root both parties signed — the caller records it, so that
     *         what was imported and what both parties said it was stays
     *         answerable without the archive
     */
    public static PortableResult importVerified(ObjectStore target, ArchiveSource source,
            byte[] ownerMasterKey, ArchiveAttestation attestation,
            byte[] vendorPublicKey, byte[] tenantPublicKey, HistoryMode history)
            throws IOException {
        // Pass one: read to the tag, digest every entry, check the signatures.
        try (InputStream sealed = source.open();
             InputStream plain = SealedArchive.opening(sealed, ownerMasterKey)) {
            ArchiveVerification.verifyStreaming(plain, attestation,
                    vendorPublicKey, tenantPublicKey);
        }
        // Pass two: the same bytes, now attested.
        try (InputStream sealed = source.open();
             InputStream plain = SealedArchive.opening(sealed, ownerMasterKey)) {
            return applyPortable(target, plain, history);
        }
    }

    /**
     * Imports without attestation — the legacy path, kept for archives that
     * carry none. It streams (#35) but it trusts what it is given, so it must
     * not be pointed at anything that arrived from outside.
     */
    public static PortableResult importPortable(ObjectStore target, InputStream sealed,
            byte[] ownerMasterKey) throws IOException {
        try (InputStream plain = SealedArchive.opening(sealed, ownerMasterKey)) {
            return applyPortable(target, plain, HistoryMode.FRESH);
        }
    }

    private static PortableResult applyPortable(ObjectStore target, InputStream plain,
            HistoryMode history) throws IOException {
        long imported = 0;
        long skipped = 0;
        try (ZipInputStream zip = new ZipInputStream(plain)) {
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

                    if (history == HistoryMode.PRESERVED) {
                        long version = jsonLong(line, "v");
                        java.time.Instant at = java.time.Instant.parse(jsonString(line, "lu"));
                        // Idempotent by VERSION, not by bytes: a history replays
                        // as many lines per object, and a resumed move must skip
                        // what already landed rather than rewrite it (#854).
                        if (existing.isPresent() && existing.get().versionId() >= version) {
                            skipped++;
                            continue;
                        }
                        target.put(PutRequest.restored(type, id, resource, version, at));
                        imported++;
                        continue;
                    }

                    // compare through the same flattening the export applied
                    if (existing.isPresent() && Arrays.equals(
                            Names.flatten(new String(existing.get().payload(), StandardCharsets.UTF_8))
                                    .getBytes(StandardCharsets.UTF_8),
                            resource)) {
                        skipped++;
                        continue; // same tenant re-import: a no-op
                    }
                    target.put(PutRequest.restoring(type, id, resource));
                    imported++;
                }
            }
        }
        return new PortableResult(imported, skipped);
    }

    private static long jsonLong(String line, String field) {
        String needle = "\"" + field + "\":";
        int start = line.indexOf(needle) + needle.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)))) {
            end++;
        }
        return Long.parseLong(line.substring(start, end));
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
                if (dumps.keySet().stream().anyMatch(t -> t.startsWith("pdi."))) {
                    ensurePdiTables(c);
                }
                for (Map.Entry<String, String> dump : dumps.entrySet()) {
                    String qualified = qualifiedTable(domain, dump.getKey());
                    if (qualified.equals("pdi.shred_ledger")) {
                        // the ledger MERGES, never replaces: live shred entries
                        // must survive a restore from a pre-shred archive —
                        // that asymmetry IS the resurrection defence (§14.4)
                        try (PreparedStatement ps = c.prepareStatement("""
                                CREATE TEMP TABLE pdi_ledger_in
                                (LIKE pdi.shred_ledger) ON COMMIT DROP""")) {
                            ps.execute();
                        }
                        copy.copyIn("COPY pdi_ledger_in FROM STDIN WITH (FORMAT csv)",
                                new StringReader(dump.getValue()));
                        try (PreparedStatement ps = c.prepareStatement("""
                                INSERT INTO pdi.shred_ledger
                                SELECT * FROM pdi_ledger_in ON CONFLICT (person_id) DO NOTHING""")) {
                            ps.executeUpdate();
                        }
                        continue;
                    }
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
                // §14.4 policy replay: a restore must never resurrect an
                // erased person — re-apply every ledger entry (idempotent;
                // the union of the archive's ledger and any pre-existing one
                // is already in the table after the load above only if the
                // archive carried it, so replay against whatever is present)
                if (dumps.keySet().stream().anyMatch(t -> t.startsWith("pdi."))) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            UPDATE pdi.person p SET wrapped_key = NULL,
                                   shredded_at = COALESCE(p.shredded_at, l.shredded_at)
                            FROM pdi.shred_ledger l
                            WHERE p.id = l.person_id AND p.wrapped_key IS NOT NULL""");
                         PreparedStatement psi = c.prepareStatement("""
                            DELETE FROM pdi.identifier i USING pdi.shred_ledger l
                            WHERE i.person_id = l.person_id""")) {
                        ps.executeUpdate();
                        psi.executeUpdate();
                    }
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
        if (archiveName.equals("pdi.person") || archiveName.equals("pdi.identifier")
                || archiveName.equals("pdi.shred_ledger")) {
            return archiveName;
        }
        String statePrefix = "state." + domain + "_";
        String historyPrefix = "history." + domain + "_";
        // Terminology is tenant-scoped rather than domain-scoped: one vocabulary
        // serves every domain in the tenant, so it carries no domain prefix.
        boolean terminology = archiveName.startsWith("state.term_");
        if (!terminology && !archiveName.startsWith(statePrefix)
                && !archiveName.startsWith(historyPrefix)) {
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

    /** Mirror of the vault DDL — a fresh restore target may predate PDI. */
    private static void ensurePdiTables(Connection c) throws SQLException {
        for (String ddl : new String[] {
                "CREATE SCHEMA IF NOT EXISTS pdi",
                """
                CREATE TABLE IF NOT EXISTS pdi.person (
                    id uuid PRIMARY KEY,
                    wrapped_key bytea,
                    restricted boolean NOT NULL DEFAULT false,
                    shredded_at timestamptz,
                    created_at timestamptz NOT NULL DEFAULT now())""",
                """
                CREATE TABLE IF NOT EXISTS pdi.identifier (
                    person_id uuid NOT NULL,
                    system text NOT NULL,
                    value_hmac bytea NOT NULL,
                    PRIMARY KEY (person_id, system, value_hmac))""",
                "CREATE UNIQUE INDEX IF NOT EXISTS pdi_identifier_claim ON pdi.identifier (system, value_hmac)",
                """
                CREATE TABLE IF NOT EXISTS pdi.shred_ledger (
                    person_id uuid PRIMARY KEY,
                    key_fingerprint text NOT NULL,
                    shredded_at timestamptz NOT NULL DEFAULT now())""",
        }) {
            try (PreparedStatement ps = c.prepareStatement(ddl)) {
                ps.execute();
            }
        }
    }
}
