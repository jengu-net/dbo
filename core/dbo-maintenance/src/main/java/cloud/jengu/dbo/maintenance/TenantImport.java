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
import java.util.Objects;
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
     * Where a sealed archive can be read from, more than once.
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
         * what somebody knew at a moment. Replayed in ascending
         * order, and a version already present is skipped rather than
         * rewritten, so a resumed move does not duplicate what landed.
         */
        PRESERVED
    }

    /**
     * Verifies the archive whole, then imports it (§11,
     * REQ-DBO-MNT-IMPORT-REFUSES-UNATTESTED).
     *
     * <p>Nothing is written until the digests match and both signatures
     * verify. A partially-applied archive leaves a tenant in a state neither
     * party attested, with nobody able to say which half is which.
     *
     * <p><b>This is the only way objects enter a store from an archive.</b>
     * There was a second one — an unattested import "kept for archives that
     * carry none" — and a path that trusts what it is given is the path
     * everything eventually arrives through. An archive without both
     * signatures is refused rather than imported carefully.
     *
     * @param ledger where the destination writes down what it accepted
     *               (REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED) — required, because an
     *               import nobody recorded is one nobody can account for later
     * @return the root both parties signed
     */
    public static PortableResult importVerified(ObjectStore target, ArchiveSource source,
            byte[] ownerMasterKey, ArchiveAttestation attestation,
            byte[] vendorPublicKey, byte[] tenantPublicKey, HistoryMode history,
            ImportLedger ledger)
            throws IOException {
        Objects.requireNonNull(ledger, "an import records what it accepted, or does not happen");
        // Pass one: read to the tag, digest every entry, check the signatures.
        String root;
        try (InputStream sealed = source.open();
             InputStream plain = SealedArchive.opening(sealed, ownerMasterKey)) {
            root = ArchiveVerification.verifyStreaming(plain, attestation,
                    vendorPublicKey, tenantPublicKey);
        }
        // Pass two: the same bytes, now attested.
        PortableResult result;
        try (InputStream sealed = source.open();
             InputStream plain = SealedArchive.opening(sealed, ownerMasterKey)) {
            result = applyPortable(target, plain, history);
        }
        // Recorded after the objects land: a root recorded for an import that
        // then failed would be a claim about data the tenant does not have.
        ledger.accepted(new ImportLedger.Accepted(root,
                ImportLedger.Accepted.fingerprint(vendorPublicKey),
                ImportLedger.Accepted.fingerprint(tenantPublicKey),
                result.imported(), result.skippedIdentical()));
        return result;
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
                        // what already landed rather than rewrite it.
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
        Map<String, java.util.List<String>> consumers = new LinkedHashMap<>();
        TenantExport.Kind[] declaredKind = {null};
        java.util.List<String> declaredDomains = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plain))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("fidelity/") && entry.getName().endsWith(".csv")) {
                    String table = entry.getName()
                            .substring("fidelity/".length(), entry.getName().length() - ".csv".length());
                    dumps.put(table, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                } else if (entry.getName().equals("manifest.json")) {
                    String manifestJson = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    declaredKind[0] = kindOf(manifestJson);
                    declaredDomains.addAll(domainsOf(manifestJson, domain));
                } else if (entry.getName().startsWith("delivery/")
                        && entry.getName().endsWith(".consumers.txt")) {
                    // one roster per domain: a consumer of the clinical feed
                    // must not be seeded into the identity one, where it would
                    // be a reader nobody wrote
                    String of = entry.getName().substring("delivery/".length(),
                            entry.getName().length() - ".consumers.txt".length());
                    for (String name : new String(zip.readAllBytes(), StandardCharsets.UTF_8)
                            .split("\n")) {
                        if (!name.isBlank()) {
                            consumers.computeIfAbsent(of, k -> new java.util.ArrayList<>())
                                    .add(name.trim());
                        }
                    }
                }
            }
        }
        requireBackup(declaredKind[0]);
        try (Connection c = target.getConnection()) {
            c.setAutoCommit(false);
            try {
                var copy = c.unwrap(PGConnection.class).getCopyAPI();
                if (dumps.keySet().stream().anyMatch(t -> t.startsWith("pdi."))) {
                    ensurePdiTables(c);
                }
                // A target that has never had a projection applied has no
                // marker table, and the load truncates before it copies
                if (dumps.containsKey("state.projection_marker")) {
                    ProjectionMarker.ensureTable(c);
                }
                for (Map.Entry<String, String> dump : dumps.entrySet()) {
                    String qualified = qualifiedTable(declaredDomains, dump.getKey());
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
                for (String restored : declaredDomains) {
                    seedConsumersAtHead(c, restored,
                            consumers.getOrDefault(restored, java.util.List.of()));
                    // realign the outbox serial after the byte-exact load —
                    // every domain has its own, and one left behind hands out
                    // sequence numbers that already exist
                    try (PreparedStatement ps = c.prepareStatement("""
                            SELECT setval(pg_get_serial_sequence('state.%s_outbox', 'seq'),
                                          COALESCE((SELECT max(seq) FROM state.%s_outbox), 0) + 1,
                                          false)""".formatted(restored, restored))) {
                        ps.execute();
                    }
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
    /** The kind an archive declares, or null when it declares none. */
    private static TenantExport.Kind kindOf(String manifestJson) {
        int at = manifestJson.indexOf("\"kind\"");
        if (at < 0) {
            return null;
        }
        int open = manifestJson.indexOf('"', manifestJson.indexOf(':', at)) + 1;
        return TenantExport.Kind.ofWire(manifestJson.substring(open, manifestJson.indexOf('"', open)));
    }

    /**
     * Refuses a byte-faithful restore from anything but a backup.
     *
     * <p>A portable export deliberately carries no credentials, no audit and
     * no configuration projection. Loading one where a backup was meant
     * produces an installation that looks populated and cannot authenticate
     * anybody — a failure discovered at sign-in, long after the restore
     * reported success, by somebody who has no reason to suspect the archive.
     *
     * <p>An archive declaring no kind at all is refused too. It predates this
     * rule, and treating "did not say" as "is a backup" would let exactly the
     * archives that were never checked through the check.
     */
    private static void requireBackup(TenantExport.Kind declared) {
        if (declared == TenantExport.Kind.BACKUP) {
            return;
        }
        throw new IllegalArgumentException(declared == null
                ? "this archive does not say what kind it is, so it cannot be restored as a "
                        + "backup — re-export it"
                : "this is a " + declared.wire() + ", not a backup: it carries no credentials "
                        + "and no configuration, so restoring it would produce an installation "
                        + "that cannot authenticate its own tenants");
    }

    /**
     * Every restored consumer starts at the head of the restored feed.
     *
     * <p>Not where it stood when the backup was taken, and not at zero.
     * Delivery lags the feed by design, so the backed-up position stands
     * behind the head and reinstating it re-sends the gap; starting at zero
     * re-sends everything the archive carries, which is worse. Head is the
     * only position that asserts nothing about what was delivered.
     *
     * <p>The consequence is deliberate and worth stating: events written
     * before the backup and not yet delivered when it was taken are
     * <b>not</b> delivered after a restore. A restore is a recovery, not a
     * replay, and re-sending a day of notifications to a hospital is the
     * worse of the two failures.
     */
    private static void seedConsumersAtHead(Connection c, String domain,
            java.util.List<String> consumers)
            throws SQLException {
        if (consumers.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO state.%s_consumer (name, seq, cursor_xid, updated_at)
                SELECT ?, COALESCE(head.seq, 0), COALESCE(head.xact_id, '0'::xid8), now()
                FROM (SELECT 1) one
                LEFT JOIN LATERAL (
                  SELECT seq, xact_id FROM state.%s_outbox
                  ORDER BY xact_id DESC, seq DESC LIMIT 1) head ON true
                ON CONFLICT (name) DO UPDATE
                  SET seq = EXCLUDED.seq, cursor_xid = EXCLUDED.cursor_xid,
                      updated_at = EXCLUDED.updated_at""".formatted(domain, domain))) {
            for (String consumer : consumers) {
                ps.setString(1, consumer);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * The domains an archive says it covers, defaulting to the one asked for.
     *
     * <p>A backup covers every domain the tenant held — clinical records,
     * credentials, the audit trail — because an installation restored without
     * the others cannot authenticate anybody.
     */
    private static java.util.List<String> domainsOf(String manifestJson, String fallback) {
        int at = manifestJson.indexOf("\"domains\"");
        if (at < 0) {
            return java.util.List.of(fallback);
        }
        int open = manifestJson.indexOf('[', at);
        String body = manifestJson.substring(open + 1, manifestJson.indexOf(']', open));
        java.util.List<String> domains = new java.util.ArrayList<>();
        for (String raw : body.split(",")) {
            String name = raw.trim().replace("\"", "");
            if (!name.isBlank()) {
                domains.add(name);
            }
        }
        return domains.isEmpty() ? java.util.List.of(fallback) : domains;
    }

    private static String qualifiedTable(java.util.List<String> domains, String archiveName) {
        if (archiveName.equals("pdi.person") || archiveName.equals("pdi.identifier")
                || archiveName.equals("pdi.shred_ledger")) {
            return archiveName;
        }
        // Terminology is tenant-scoped rather than domain-scoped: one vocabulary
        // serves every domain in the tenant, so it carries no domain prefix.
        boolean terminology = archiveName.startsWith("state.term_");
        // tenant-scoped, like terminology: one configuration however many
        // domains the tenant serves
        if (archiveName.equals("state.projection_marker")) {
            return archiveName;
        }
        // The guard still holds: a table must belong to a domain the archive
        // DECLARED. Widening it to "any domain" would let an archive name an
        // arbitrary table in the target database, which is what this check
        // exists to prevent.
        boolean known = terminology || domains.stream().anyMatch(d ->
                archiveName.startsWith("state." + d + "_")
                        || archiveName.startsWith("history." + d + "_"));
        if (!known) {
            throw new IllegalArgumentException("unexpected fidelity table: " + archiveName
                    + " — the archive declares " + domains);
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
