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
     * property of the type rather than an omission from a list.
     */
    /**
     * Every domain the tenant database holds.
     *
     * <p>A tenant is not one domain. Clinical records live in the FHIR
     * personality's domain, credentials and signing keys in {@code identity},
     * the trail in {@code audit} — and a backup that covered only the one it
     * was asked for restored an installation that could not authenticate
     * anybody. The failure is silent in the worst way: the data is all there,
     * and nobody can log in to see it.
     *
     * <p>Discovered rather than listed, for the reason the table sweep is
     * discovered: a list is a second description of the database that stops
     * being true without anything failing.
     */
    static List<String> domainsOf(Connection c) throws SQLException {
        List<String> domains = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT replace(table_name, '_data', '') FROM information_schema.tables
                WHERE table_schema = 'state' AND table_name LIKE '%\\_data'
                ORDER BY table_name""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                domains.add(rs.getString(1));
            }
        }
        return domains;
    }

    private static List<String> stateTablesOf(Connection c, String domain) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'state'
                  AND (table_name LIKE ? OR table_name LIKE 'term\\_%'
                       OR table_name = 'projection_marker')
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

    /**
     * What an archive is for, declared in it rather than inferred by whoever
     * opens it.
     *
     * <p>The two carry different things, and the difference is not a
     * convenience. A backup holds the state that exists nowhere else —
     * credentials, enrollments, lifecycle — so a restored installation can
     * authenticate its own tenants. A portable export holds what belongs to
     * the customer and none of our keys.
     *
     * <p>Guessing between them is what makes the failure quiet: restoring an
     * export where a backup was meant produces an installation that looks
     * populated and cannot log anybody in, and the operator finds out at the
     * worst moment. So the kind travels in the manifest and the import
     * refuses the wrong one.
     */
    /** The interchange element's Bulk Data manifest, beside its NDJSON. */
    public static final String BULK_MANIFEST_ENTRY = "fhir/manifest.json";

    public enum Kind {
        BACKUP("backup"),
        PORTABLE_EXPORT("portable-export");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Kind ofWire(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown archive kind: " + wire);
        }
    }

    public record ExportResult(long objectCount, long outboxFence) {}

    /**
     * Exports without type knowledge — every stored type travels.
     *
     * <p>Kept for callers that hold no registry. It cannot honour a type
     * declared as never travelling, because it cannot tell one type from
     * another; pass the registrations to get that guarantee.
     */
    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out) throws IOException {
        return export(ds, domain, ownerMasterKey, out, List.of());
    }

    /**
     * Exports, honouring each type's declared travel.
     *
     * <p>A type whose handling says it never leaves is excluded from both
     * representations — the portable NDJSON and the byte-faithful dumps — so
     * "it cannot be placed in any archive" holds however the archive is read,
     * rather than only in the half somebody remembered.
     */
    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out, List<TypeRegistration> types) throws IOException {
        return export(ds, domain, ownerMasterKey, out, types, Kind.BACKUP);
    }

    /**
     * Exports as the named kind.
     *
     * <p>A <b>portable export</b> carries what the customer owns and the
     * vocabulary their codes resolve against, and nothing whose declared
     * travel stops at a backup — credentials, audit, projected configuration.
     * It also omits the byte-faithful element entirely: those dumps are this
     * store's internals, and an archive meant to be read by somebody else's
     * FHIR tooling should not carry a second copy of everything in a shape
     * only we can load.
     *
     * <p>An export whose CodeSystems were absent would be syntactically valid
     * FHIR and semantically unreadable, which is why replicated terminology
     * travels as a snapshot even though it is not the customer's to own.
     */
    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out, List<TypeRegistration> types, Kind kind) throws IOException {
        return export(ds, domain, ownerMasterKey, out, types, kind, null);
    }

    /**
     * As above, with the face's own interchange rendering.
     *
     * <p>A portable export carries a <b>{@code fhir/}</b> element: one
     * resource per line, ids and versions put back — FHIR Bulk Data, which a
     * reader who has never heard of this store can open. Without a rendering
     * a portable export is refused rather than emitted in a private shape
     * with a portable label on it.
     *
     * <p>The {@code state/} element stays as it is. It is dbo's own
     * interchange, and it carries identity codes <em>beside</em> each object
     * because writing them into the payload would change the bytes the version
     * chain and both signatures are over (§11). Collapsing the two into a
     * single attested Bulk Data artifact is separate work, and belongs there.
     */
    public static ExportResult export(DataSource ds, String domain, byte[] ownerMasterKey,
            OutputStream out, List<TypeRegistration> types, Kind kind,
            cloud.jengu.dbo.core.face.PortableRendering rendering) throws IOException {
        Names.requireDomain(domain);
        if (kind == Kind.PORTABLE_EXPORT && rendering == null) {
            throw new IllegalArgumentException("a portable export needs the face's interchange "
                    + "rendering: without it this would be a private format wearing a portable "
                    + "label, which is worse than refusing");
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                // Sealed as it is written. The archive never exists whole
                // in memory — a tenant's history is the volume, and holding it
                // twice (plain and ciphertext) fails on the first real hospital.
                ExportResult result;
                DigestingZip zip = null;
                try (OutputStream sealed = SealedArchive.sealing(ownerMasterKey, out)) {
                    zip = new DigestingZip(new ZipOutputStream(sealed));
                    result = writeArchive(c, domain, zip, grounded(types, kind), kind, rendering);
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
     * Writes entries and digests them on the way past.
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
     * exactly the tenants for whom leaving matters most.
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

        /** The digest of an entry already written — the same one the root is over. */
        String digestOf(String name) {
            String digest = digests.get(name);
            if (digest == null) {
                throw new IllegalStateException("no digest for " + name + ": an entry is "
                        + "digested when it closes, so it cannot be described before then");
            }
            return digest;
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
     * The FHIR element's own manifest, in the shape Bulk Data already defines
     * (REQ-DBO-MNT-PORTABLE-STATE-EXPORT).
     *
     * <p>An export that opens in somebody else's tools has to be findable by
     * them too: a reader who has never heard of this store knows what
     * {@code transactionTime} and {@code output} mean, and does not know what
     * {@code digests.json} is. So the interchange element carries the standard
     * manifest beside its NDJSON, with file names relative to it.
     *
     * <p>The digests are the SAME ones the root is over — read from the entries
     * as they were written, not recomputed — so the manifest a stranger checks
     * and the attestation both parties signed cannot disagree. That is also why
     * this manifest cannot describe itself: an entry is digested when it
     * closes, and {@code digests.json} covers this file in turn.
     *
     * <p>{@code transactionTime} is the store's own snapshot moment rather than
     * the exporting JVM's clock — the export runs in one repeatable-read
     * transaction, and that transaction's timestamp is when this data was true.
     */
    private static void writeBulkManifest(Connection c, DigestingZip zip,
            Map<String, Long> counts) throws IOException, SQLException {
        String transactionTime;
        try (PreparedStatement ps = c.prepareStatement("SELECT transaction_timestamp()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            transactionTime = rs.getTimestamp(1).toInstant().toString();
        }
        StringBuilder json = new StringBuilder("{\"transactionTime\":")
                .append(Names.quote(transactionTime))
                .append(",\"requiresAccessToken\":false,\"output\":[");
        boolean first = true;
        for (Map.Entry<String, Long> line : counts.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            String entry = "fhir/" + line.getKey() + ".ndjson";
            json.append("{\"type\":").append(Names.quote(line.getKey()))
                    .append(",\"url\":").append(Names.quote(line.getKey() + ".ndjson"))
                    .append(",\"count\":").append(line.getValue())
                    // not a Bulk Data field: the spec defines no checksum, and a
                    // reader who ignores this one still reads the export
                    .append(",\"digest\":").append(Names.quote("sha256:" + zip.digestOf(entry)))
                    .append('}');
        }
        json.append("],\"error\":[]}");
        zip.putNextEntry(new ZipEntry(BULK_MANIFEST_ENTRY));
        zip.write(json.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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
     * is this?" for a reader who cannot resolve our ids.
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

    /**
     * Whether a table holds <b>delivery state</b> rather than data.
     *
     * <p>A subscription is two things wearing one name: the declaration — who
     * wants what — is durable configuration and travels as an ordinary object;
     * the cursor and the counters are how far delivery has got, and they are
     * true for a moment. An exhausted delivery is neither: it is a run record
     * in the tenant's own store, and it travels because what somebody still
     * has to do about a failed delivery outlives the position of a cursor.
     *
     * <p>Carrying them is the sharpest failure in the whole backup design,
     * because it does not look like one. Delivery lags the feed by design, so
     * a backup almost always captures a cursor standing behind the outbox
     * head. Restoring it re-sends every event in that gap — a hospital's
     * downstream systems receive a day of duplicate notifications and the
     * restore reports success.
     */
    private static boolean isDeliveryState(String domain, String table) {
        return table.equals(domain + "_consumer")
                || table.equals(domain + "_topic_counter");
    }

    /**
     * The consumers, by name and nothing else.
     *
     * <p>Who reads the feed survives a restore; where they had got to does
     * not. Dropping the roster as well would be worse than keeping the
     * positions: a consumer with no row starts at zero, so the restore would
     * deliver the <em>entire</em> feed rather than merely the gap.
     */
    private static void writeConsumerRoster(Connection c, DigestingZip zip, String domain)
            throws SQLException, IOException {
        StringBuilder roster = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM state.%s_consumer ORDER BY name".formatted(domain));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                roster.append(rs.getString(1)).append('\n');
            }
        }
        zip.putNextEntry(new java.util.zip.ZipEntry("delivery/" + domain + ".consumers.txt"));
        zip.write(roster.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** The type names that must not appear in any archive. */
    private static Set<String> grounded(List<TypeRegistration> types, Kind kind) {
        Set<String> never = new java.util.LinkedHashSet<>();
        for (TypeRegistration type : types) {
            boolean travels = kind == Kind.BACKUP
                    ? type.handling().travelsInBackup()
                    : type.handling().travelsInPortableExport();
            if (!travels) {
                never.add(type.typeName());
            }
        }
        return never;
    }

    private static ExportResult writeArchive(Connection c, String domain, DigestingZip out,
            Set<String> grounded, Kind kind,
            cloud.jengu.dbo.core.face.PortableRendering rendering)
            throws SQLException, IOException {
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
            // version chain and both signatures are over (§11) — the
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

        // ---- the domain's own interchange element, for a reader who has
        // never heard of this store. One resource per line with its id and
        // version put back — the same projection every client already sees.
        if (kind == Kind.PORTABLE_EXPORT) {
            Map<String, Long> fhirCounts = new LinkedHashMap<>();
            for (String type : types) {
                zip.putNextEntry(new ZipEntry("fhir/" + type + ".ndjson"));
                long lines = 0;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT d.id, d.version_id, d.payload
                        FROM state.%s_data d WHERE d.type = ? AND NOT d.deleted
                        ORDER BY d.id""".formatted(domain))) {
                    ps.setString(1, type);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            zip.write((rendering.render(rs.getBytes(3),
                                    rs.getObject(1).toString(), rs.getLong(2)) + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            lines++;
                        }
                    }
                }
                zip.closeEntry();
                fhirCounts.put(type, lines);
            }
            writeBulkManifest(c, zip, fhirCounts);
        }

        Set<String> written = new java.util.LinkedHashSet<>();
        // ---- byte-faithful fidelity element: COPY dumps of state, history
        // and the vault.
        //
        // A backup's business only, and the vault is the sharp end of that:
        // those tables are wrapped keys and an HMAC index, and an archive a
        // customer walks away with must carry none of our key material. The
        // dumps are also this store's internals — an export somebody else
        // opens should not carry a second copy of everything in a shape only
        // we can load.
        //
        // A portable export still resolves its codes: CodeSystem and ValueSet
        // are objects, and they travel in the NDJSON above under their
        // declared snapshot travel. What stays behind is the expansion
        // machinery, which is ours.
        if (kind == Kind.BACKUP) {
            var copy = c.unwrap(PGConnection.class).getCopyAPI();
            // EVERY domain, not the one asked for. Credentials and the audit
            // trail live in their own domains, and a backup that skipped them
            // restored an installation nobody could log in to.
            //
            // The type-level travel exclusions apply only to the domain whose
            // registrations were supplied — the others are carried whole,
            // which is what a backup means. Nothing declared "never leaves"
            // can be in another domain unnoticed, because a domain with no
            // registrations here has no way to declare anything either.
            for (String backedUp : domainsOf(c)) {
                Set<String> exclusions = backedUp.equals(domain) ? grounded : Set.of();
                // The terminology tables are tenant-scoped rather than
                // domain-scoped: they appear once, under the first domain
                // that sweeps them up, and the import resolves them by name.
                for (String table : stateTablesOf(c, backedUp)) {
                    if (isDeliveryState(backedUp, table) || written.contains(table)) {
                        continue;
                    }
                    written.add(table);
                    dumpInto(copy, zip, "fidelity/state." + table + ".csv",
                            copyOf("state." + table,
                                    table.equals(backedUp + "_data") ? exclusions : Set.of()),
                            table);
                }
                writeConsumerRoster(c, zip, backedUp);
                dumpInto(copy, zip, "fidelity/history." + backedUp + "_history.csv",
                        copyOf("history." + backedUp + "_history", exclusions),
                        "history." + backedUp);
            }

            // §14 vault (present only under PDI): wrapped keys, HMAC index,
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
        }

        // ---- manifest
        zip.putNextEntry(new ZipEntry("manifest.json"));
        StringBuilder manifest = new StringBuilder();
        String configCommit = ProjectionMarker.read(c, ProjectionMarker.CONFIG_COMMIT);
        manifest.append("{\"kind\":").append(Names.quote(kind.wire()))
                // Which configuration this data was projected under, so a
                // restore can be reassembled against the same one rather than
                // against whatever the repository says today. Absent when the
                // tenant has never had a projection applied — stated as
                // absent rather than defaulted to a plausible commit.
                .append(",\"configCommit\":").append(
                        configCommit == null ? "null" : Names.quote(configCommit))
                .append(",\"domain\":").append(Names.quote(domain))
                // what the archive actually covers: every domain for a
                // backup, the one asked for in an export
                .append(",\"domains\":[").append(
                        (kind == Kind.BACKUP ? domainsOf(c) : List.of(domain)).stream()
                        .map(Names::quote).collect(java.util.stream.Collectors.joining(",")))
                .append(']')
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
