package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.TypeRegistry;
import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.HandlingRefusedException;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PayloadConverter;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.VersionChain;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.VersionConflictException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One tenant's object store over its (dedicated-tier) database. All writes
 * commit data + history + outbox in a single transaction
 * (REQ-DBO-CORE-READ-YOUR-WRITES, REQ-DBO-EVT-TRANSACTIONAL-OUTBOX); every
 * value is a bound parameter (REQ-DBO-CORE-PARAMETERIZED-SQL).
 */
public final class PgObjectStore implements ObjectStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource ds;
    private final TypeRegistry registry;
    private final SchemaManager schema;
    private final java.util.Map<String, PayloadConverter> convertersByFrom = new java.util.LinkedHashMap<>();

    public PgObjectStore(DataSource dataSource, List<TypeRegistration> registrations) {
        this(dataSource, registrations, List.of());
    }

    public PgObjectStore(DataSource dataSource, List<TypeRegistration> registrations,
            List<PayloadConverter> converters) {
        this.ds = dataSource;
        this.registry = new TypeRegistry(registrations);
        this.schema = new SchemaManager(dataSource);
        for (PayloadConverter converter : converters) {
            if (convertersByFrom.put(converter.fromVersion(), converter) != null) {
                throw new IllegalArgumentException(
                        "duplicate converter from version " + converter.fromVersion());
            }
        }
        schema.ensureSchema(registry);
    }

    /**
     * Lazy upgrade on read (REQ-DBO-CORE-UPGRADE-ON-READ): walk the converter
     * chain from the stored version to the registration's current version.
     * Stored bytes are never rewritten.
     */
    private StoredObject upgraded(TypeRegistration type, StoredObject stored) {
        if (stored.payloadVersion().equals(type.payloadVersion())) {
            return stored;
        }
        byte[] payload = stored.payload();
        String version = stored.payloadVersion();
        for (int hops = 0; hops < 8 && !version.equals(type.payloadVersion()); hops++) {
            PayloadConverter converter = convertersByFrom.get(version);
            if (converter == null) {
                throw new IllegalStateException("no converter from payload version " + version
                        + " toward " + type.payloadVersion() + " for " + type.typeName());
            }
            payload = converter.convert(type.typeName(), payload);
            version = converter.toVersion();
        }
        if (!version.equals(type.payloadVersion())) {
            throw new IllegalStateException("converter chain did not reach " + type.payloadVersion());
        }
        return new StoredObject(stored.id(), stored.typeName(), stored.versionId(),
                stored.lastUpdated(), payload, stored.deleted(), version);
    }

    // ------------------------------------------------------------------ put

    @Override
    public PutResult put(PutRequest request) {
        return put(request, Handling.Authority.TENANT_USERS);
    }

    /**
     * A write that says who is making it. The default caller is
     * {@code TENANT_USERS} — the least-privileged one — so a lane entitled to
     * write data its tenant may not must say so, rather than inheriting the
     * entitlement by being in-process.
     */
    public PutResult put(PutRequest request, Handling.Authority caller) {
        TypeRegistration type = registry.require(request.typeName());
        return inTx(c -> writeObject(c, type, request, caller));
    }

    @Override
    public List<PutResult> transact(List<PutRequest> requests) {
        // Resolve every registration BEFORE the transaction opens: an unknown
        // type refuses the whole unit with nothing begun, rather than half-way.
        List<TypeRegistration> types = requests.stream()
                .map(r -> registry.require(r.typeName())).toList();
        return inTx(c -> {
            List<PutResult> out = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                out.add(writeObject(c, types.get(i), requests.get(i),
                        Handling.Authority.TENANT_USERS));
            }
            return out;
        });
    }

    @Override
    public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
        TypeRegistration type = registry.require(request.typeName());
        Identifier ident = registry.identityIdentifier(type, identity);
        return inTx(c -> {
            Optional<String> existing = resolveIdentity(c, type, ident);
            if (existing.isPresent()) {
                long version = currentVersion(c, type, existing.get());
                return new PutResult(existing.get(), version, false);
            }
            return writeObject(c, type,
                    new PutRequest(request.typeName(), request.id(), request.expectedVersion(), request.payload()));
        });
    }

    @Override
    public PutResult putConditional(IdentityRef identity, PutRequest request) {
        TypeRegistration type = registry.require(request.typeName());
        Identifier ident = registry.identityIdentifier(type, identity);
        return inTx(c -> {
            Optional<String> existing = resolveIdentity(c, type, ident);
            String id = existing.orElse(request.id());
            return writeObject(c, type,
                    new PutRequest(request.typeName(), id, request.expectedVersion(), request.payload()));
        });
    }

    /**
     * Refuses a write the type's declared handling forbids.
     *
     * <p>The refusal names the rule. An opaque denial is indistinguishable from
     * a bug, and the caller has no way to tell whether it asked wrongly or
     * found one.
     *
     * <p><b>A restore passes.</b> Otherwise a backup containing an audit trail
     * or a replicated vocabulary could never be restored, which would make
     * these rules protect the data by losing it. The restore path is not open:
     * it needs the owner master key and an archive both parties signed
     * (§11), so the gate is cryptographic rather than absent.
     */
    private static void guardWrite(TypeRegistration type, PutRequest request,
            boolean created, Handling.Authority caller) {
        if (request.isRestore()) {
            return;
        }
        Handling handling = type.handling();
        // The same question the CapabilityStatement asks, so what is advertised
        // and what is accepted cannot drift apart again (#104).
        switch (handling.refusalFor(caller, created)) {
            case APPEND_ONLY -> throw new HandlingRefusedException(type.typeName(),
                    "append-only", "it may be written once and never altered — by anyone, "
                            + "including us");
            case READ_ONLY_HERE -> throw new HandlingRefusedException(type.typeName(),
                    "read-only-here", "it is published by " + handling.authority()
                            + " and only that lane may write it; an edit made here would be "
                            + "silently overwritten by the next sync, or silently kept");
            case null -> { }
        }
        if (handling.requiresARun() && Caller.run() == null) {
            // #82: the change would belong to nothing. History would still have
            // it and audit would still name who, and nobody could say what it
            // was for — which is also the moment the run stops being a complete
            // account of what changed, and stops being usable as a manifest.
            throw new HandlingRefusedException(type.typeName(), "under-a-run",
                    "every change to it belongs to a piece of work, and this write is inside "
                            + "none; open a run for it, or declare the type as writable on its "
                            + "own account");
        }
    }

    private PutResult writeObject(Connection c, TypeRegistration type, PutRequest request) throws SQLException {
        return writeObject(c, type, request, Handling.Authority.TENANT_USERS);
    }

    private PutResult writeObject(Connection c, TypeRegistration type, PutRequest request,
            Handling.Authority caller) throws SQLException {
        String id = request.id() != null ? request.id() : UuidV7.newId();
        UUID uuid = UUID.fromString(id);
        String d = type.domain();

        Long current = lockVersion(c, d, type.typeName(), uuid);
        if (request.expectedVersion() != null) {
            long actual = current == null ? 0 : current;
            if (actual != request.expectedVersion()) {
                throw new VersionConflictException(type.typeName(), id, request.expectedVersion(), actual);
            }
        }
        long newVersion = current == null ? 1 : current + 1;
        boolean created = current == null;
        guardWrite(type, request, created, caller);
        Instant now = Instant.now();
        if (request.carriesRecordedHistory()) {
            // Replaying a history that happened elsewhere: keep its version and
            // its moment. Monotonic or nothing — a version that would land at or
            // below the current one means the replay is out of order or already
            // applied, and either way writing it would corrupt the history this
            // exists to preserve.
            if (current != null && request.recordedVersion() <= current) {
                throw new IllegalArgumentException(type.typeName() + "/" + id
                        + ": restored version " + request.recordedVersion()
                        + " is not above the stored version " + current
                        + " — replay must be in ascending order, and an already-applied"
                        + " version must be skipped rather than rewritten");
            }
            newVersion = request.recordedVersion();
            now = request.recordedAt();
        }

        Envelope envelope = type.extractor().extract(type.typeName(), request.payload());
        String envelopeJson = JsonbCodec.envelopeJson(envelope.paths());
        // Link this version to the one before it. Computed on the ordinary
        // write path, so a restored version is chained exactly as a live one —
        // a restore that skipped chaining would be the hole the chain closes.
        byte[] chainHash = VersionChain.link(previousChain(c, d, uuid), request.payload(),
                newVersion, now, false);

        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO state.%s_data (id, type, version_id, last_updated, envelope, payload, deleted, payload_version, chain_hash)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, false, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  version_id = EXCLUDED.version_id,
                  last_updated = EXCLUDED.last_updated,
                  envelope = EXCLUDED.envelope,
                  payload = EXCLUDED.payload,
                  deleted = false,
                  payload_version = EXCLUDED.payload_version,
                  chain_hash = EXCLUDED.chain_hash""".formatted(d))) {
            ps.setObject(1, uuid);
            ps.setString(2, type.typeName());
            ps.setLong(3, newVersion);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setString(5, envelopeJson);
            ps.setBytes(6, request.payload());
            ps.setString(7, type.payloadVersion());
            ps.setBytes(8, chainHash);
            ps.executeUpdate();
        }

        replaceIdentifiers(c, type, uuid, envelope.identifiers());
        replaceReferences(c, d, uuid, envelope.references(), newVersion == 1);
        insertHistory(c, d, uuid, type.typeName(), newVersion, now, request.payload(), false,
                type.payloadVersion(), chainHash);
        if (!request.isRestore()) {
            // A restore re-establishes state; it does not change it. The outbox
            // is a log of changes, so restored objects do not belong in it —
            // otherwise a subscription cannot tell a recovered tenant from a
            // busy one, and a hospital's downstream systems receive its entire
            // history as fresh news on the day it is already having its worst
            // day.
            insertOutbox(c, d, uuid, type.typeName(), newVersion, created ? "C" : "U");
        }

        return new PutResult(id, newVersion, created);
    }

    private void replaceIdentifiers(Connection c, TypeRegistration type, UUID id, List<Identifier> identifiers)
            throws SQLException {
        String d = type.domain();
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM state.%s_identifier WHERE object_id = ?".formatted(d))) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        for (Identifier ident : identifiers) {
            boolean identity = registry.isIdentityBearing(type, ident);
            if (identity) {
                // Asked before inserted (#125). An exclusive claim held by
                // another object is an ANTICIPATED answer — the sync lane
                // meets it on every first delivery of a publication the
                // tenant already holds, and resolves it by content. Letting
                // the INSERT discover it made Postgres log an ERROR for every
                // handled case, which buries the log's real errors. The
                // unique index stays as the backstop, so a genuine race still
                // raises — and that one has earned its log line.
                Optional<String> holder = resolveIdentity(c, type, ident);
                if (holder.isPresent() && !holder.get().equals(id.toString())) {
                    throw identityConflict(c, type, ident, id.toString());
                }
            }
            // conflict target scoped to the PK: a duplicate row is a no-op, but a
            // violation of the partial identity-claim index still RAISES —
            // swallowing it would silently merge identities
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO state.%s_identifier (type, system, value, object_id, identity)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (type, system, value, object_id) DO NOTHING""".formatted(d))) {
                ps.setString(1, type.typeName());
                ps.setString(2, ident.system());
                ps.setString(3, ident.value());
                ps.setObject(4, id);
                ps.setBoolean(5, identity);
                ps.executeUpdate();
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState()) && identity) {
                    throw identityConflict(c, type, ident, id.toString());
                }
                throw e;
            }
        }
    }

    private IdentityConflictException identityConflict(Connection c, TypeRegistration type,
            Identifier ident, String claimingId) throws SQLException {
        // the tx is doomed; report the existing claimant read via a fresh connection
        try (Connection c2 = ds.getConnection();
             PreparedStatement ps = c2.prepareStatement("""
                     SELECT object_id FROM state.%s_identifier
                     WHERE type = ? AND system = ? AND value = ? AND identity""".formatted(type.domain()))) {
            ps.setString(1, type.typeName());
            ps.setString(2, ident.system());
            ps.setString(3, ident.value());
            try (ResultSet rs = ps.executeQuery()) {
                String existing = rs.next() ? rs.getObject(1).toString() : "unknown";
                return new IdentityConflictException(existing, claimingId, ident);
            }
        }
    }

    /**
     * The edges this object points along, replaced as a set.
     *
     * <p>One statement prepared once and sent as a batch, rather than one
     * prepared and executed per edge. Resources carry about four references
     * each in a real population, so the old shape spent four prepares and four
     * round trips on every write — the single busiest thing in the storage
     * path, measured: 23,781 reference rows against 5,860 data rows.
     *
     * <p>{@code firstVersion} skips the DELETE. A version 1 has no earlier
     * edges to clear, and issuing the statement anyway was a round trip per
     * write to delete nothing.
     */
    private void replaceReferences(Connection c, String d, UUID id,
            List<Envelope.ReferenceEdge> references, boolean firstVersion) throws SQLException {
        if (!firstVersion) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM state.%s_reference WHERE owner_id = ?".formatted(d))) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        }
        if (references.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO state.%s_reference (owner_id, ref_type, target_type, target_id)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""".formatted(d))) {
            for (Envelope.ReferenceEdge edge : references) {
                ps.setObject(1, id);
                ps.setString(2, edge.refType());
                ps.setString(3, edge.targetType());
                ps.setString(4, edge.targetId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertHistory(Connection c, String d, UUID id, String type, long version,
            Instant at, byte[] payload, boolean deleted, String payloadVersion, byte[] chainHash)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO history.%s_history (id, version_id, type, last_updated, payload, deleted, payload_version, chain_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".formatted(d))) {
            ps.setObject(1, id);
            ps.setLong(2, version);
            ps.setString(3, type);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setBytes(5, payload);
            ps.setBoolean(6, deleted);
            ps.setString(7, payloadVersion);
            ps.setBytes(8, chainHash);
            ps.executeUpdate();
        }
    }

    /**
     * The link the previous version left, or null when this object has none
     * yet. Read under the same lock as the version it belongs to, so two
     * concurrent writers cannot both chain onto the same predecessor.
     */
    /**
     * Walks an object's history and reports the first version whose link does
     * not follow from the one before it.
     *
     * <p>Recomputed rather than compared against a stored expectation: a
     * verifier that trusts a stored answer verifies nothing. Versions written
     * before the chain existed carry no link and are reported as UNCHAINED —
     * an honest distinction, because nothing was ever attested about them and
     * calling that "broken" would cry wolf on every legacy row.
     */
    public ChainCheck verifyChain(String typeName, String id) {
        TypeRegistration type = registry.require(typeName);
        UUID uuid = UUID.fromString(id);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT version_id, last_updated, payload, deleted, chain_hash
                     FROM history.%s_history WHERE id = ? ORDER BY version_id"""
                     .formatted(type.domain()))) {
            ps.setObject(1, uuid);
            byte[] previous = VersionChain.GENESIS;
            boolean sawAny = false;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sawAny = true;
                    long version = rs.getLong(1);
                    Instant at = rs.getTimestamp(2).toInstant();
                    byte[] payload = rs.getBytes(3);
                    boolean deleted = rs.getBoolean(4);
                    byte[] stored = rs.getBytes(5);
                    if (stored == null) {
                        return new ChainCheck(ChainCheck.Result.UNCHAINED, version);
                    }
                    byte[] expected = VersionChain.link(previous, payload, version, at, deleted);
                    if (!java.util.Arrays.equals(expected, stored)) {
                        return new ChainCheck(ChainCheck.Result.BROKEN, version);
                    }
                    previous = stored;
                }
            }
            return sawAny
                    ? new ChainCheck(ChainCheck.Result.INTACT, null)
                    : new ChainCheck(ChainCheck.Result.NO_HISTORY, null);
        } catch (SQLException e) {
            throw new IllegalStateException("chain verification failed for " + typeName + "/" + id, e);
        }
    }

    /** What a chain walk found, and where. */
    public record ChainCheck(Result result, Long atVersion) {
        public enum Result { INTACT, BROKEN, UNCHAINED, NO_HISTORY }

        public boolean isIntact() {
            return result == Result.INTACT;
        }
    }

    private byte[] previousChain(Connection c, String d, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT chain_hash FROM state.%s_data WHERE id = ?".formatted(d))) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private void insertOutbox(Connection c, String d, UUID id, String type, long version, String kind)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO state.%s_outbox (object_id, type, version_id, kind)
                VALUES (?, ?, ?, ?)""".formatted(d))) {
            ps.setObject(1, id);
            ps.setString(2, type);
            ps.setLong(3, version);
            ps.setString(4, kind);
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------------------- read

    @Override
    public Optional<StoredObject> get(String typeName, String id) {
        TypeRegistration type = registry.require(typeName);
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, type, version_id, last_updated, payload, deleted, payload_version
                    FROM state.%s_data WHERE id = ? AND type = ? AND NOT deleted""".formatted(type.domain()))) {
                ps.setObject(1, UUID.fromString(id));
                ps.setString(2, typeName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(upgraded(type, read(rs))) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
        TypeRegistration type = registry.require(typeName);
        if (identifiers.isEmpty()) {
            return List.of();
        }
        StringBuilder or = new StringBuilder();
        for (int i = 0; i < identifiers.size(); i++) {
            if (i > 0) or.append(" OR ");
            or.append("(i.system = ? AND i.value = ?)");
        }
        // i.type as well as d.type, and it is not redundant: the identifier
        // table's key is (type, system, value, object_id), so a query that
        // constrains only the DATA side leaves the index's leading column
        // free and Postgres falls back to scanning every identifier row.
        //
        // That is O(n) per lookup against a table that grows with every write,
        // and a transaction resolves hundreds of conditional references — so
        // ingest cost went quadratic and a store got measurably slower the
        // fuller it was. Measured on a Pi at 4,019 identifier rows: seq scan
        // 1.17ms and 67 buffers, index-only scan 0.26ms and 6, and the gap
        // widens with every row.
        String sql = """
                SELECT DISTINCT d.id, d.type, d.version_id, d.last_updated, d.payload, d.deleted, d.payload_version
                FROM state.%s_data d
                JOIN state.%s_identifier i ON i.object_id = d.id
                WHERE d.type = ? AND NOT d.deleted AND i.type = ? AND (%s)"""
                .formatted(type.domain(), type.domain(), or);
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int p = 1;
                ps.setString(p++, typeName);
                ps.setString(p++, typeName);
                for (Identifier ident : identifiers) {
                    ps.setString(p++, ident.system());
                    ps.setString(p++, ident.value());
                }
                return readAll(ps).stream().map(o -> upgraded(type, o)).toList();
            }
        });
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        TypeRegistration type = registry.require(typeName);
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, type, version_id, last_updated, payload, deleted, payload_version
                    FROM history.%s_history WHERE id = ? ORDER BY version_id""".formatted(type.domain()))) {
                ps.setObject(1, UUID.fromString(id));
                return readAll(ps);
            }
        });
    }

    @Override
    public List<StoredObject> select(Criteria criteria) {
        TypeRegistration type = registry.require(criteria.typeName());
        String d = type.domain();
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.type, d.version_id, d.last_updated, d.payload, d.deleted, d.payload_version
                FROM state.%s_data d WHERE d.type = ? AND NOT d.deleted""".formatted(d));
        List<Object> params = new ArrayList<>();
        params.add(criteria.typeName());
        appendWhere(criteria, d, sql, params);
        appendOrder(criteria, sql, true);
        sql.append(" LIMIT ?");
        params.add(criteria.limitValue());

        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                return readAll(ps).stream().map(o -> upgraded(type, o)).toList();
            }
        });
    }

    @Override
    public long count(Criteria criteria) {
        TypeRegistration type = registry.require(criteria.typeName());
        String d = type.domain();
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM state.%s_data d WHERE d.type = ? AND NOT d.deleted".formatted(d));
        List<Object> params = new ArrayList<>();
        params.add(criteria.typeName());
        appendWhere(criteria, d, sql, params);
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** All non-sort predicates, shared by select/page/count. Values are always bound. */
    private void appendWhere(Criteria criteria, String d, StringBuilder sql, List<Object> params) {
        if (criteria.idEqualsValue() != null) {
            sql.append(" AND d.id = ?");
            params.add(UUID.fromString(criteria.idEqualsValue()));
        }
        if (!criteria.equalsPredicates().isEmpty()) {
            sql.append(" AND d.envelope @> ?::jsonb");
            params.add(JsonbCodec.containmentJson(criteria.equalsPredicates()));
        }
        for (Criteria.NotEq ne : criteria.notEqualsPredicates()) {
            sql.append(" AND NOT (d.envelope @> ?::jsonb)");
            params.add(JsonbCodec.containmentJson(
                    List.of(new Criteria.Eq(ne.path(), ne.value()))));
        }
        for (Criteria.StartsWith sw : criteria.startsWithPredicates()) {
            sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements(d.envelope -> ?) e")
               .append(" WHERE e->>'v' LIKE ? ESCAPE '\\')");
            params.add(sw.path());
            params.add(likePrefix(sw.prefix()));
        }
        for (Criteria.Missing m : criteria.missingPredicates()) {
            sql.append(m.missing() ? " AND NOT jsonb_exists(d.envelope, ?)"
                    : " AND jsonb_exists(d.envelope, ?)");
            params.add(m.path());
        }
        for (Criteria.Range r : criteria.rangePredicates()) {
            sql.append(" AND ").append(Sql.typedPathExpression(r.path(), r.kind()))
               .append(' ').append(rangeOp(r.op())).append(" ?");
            params.add(r.kind() == cloud.jengu.dbo.core.api.ValueKind.NUMBER
                    ? new java.math.BigDecimal(r.value()) : r.value());
        }
        for (Criteria.LastUpdatedRange lu : criteria.lastUpdatedPredicates()) {
            sql.append(" AND d.last_updated ").append(rangeOp(lu.op())).append(" ?");
            params.add(Timestamp.from(lu.value()));
        }
        for (Criteria.Referencing ref : criteria.referencingPredicates()) {
            sql.append(" AND EXISTS (SELECT 1 FROM state.%s_reference r WHERE r.owner_id = d.id".formatted(d));
            sql.append(" AND r.ref_type = ? AND r.target_type = ? AND r.target_id = ?)");
            params.add(ref.refType());
            params.add(ref.targetType());
            params.add(ref.targetId());
        }
        for (Criteria.ReferencingAny any : criteria.referencingAnyPredicates()) {
            sql.append(" AND EXISTS (SELECT 1 FROM state.%s_reference r WHERE r.owner_id = d.id".formatted(d));
            sql.append(" AND r.ref_type = ? AND r.target_type = ? AND r.target_id IN (");
            params.add(any.refType());
            params.add(any.targetType());
            for (int i = 0; i < any.targetIds().size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(any.targetIds().get(i));
            }
            sql.append("))");
        }
        for (Criteria.RefMissing rm : criteria.refMissingPredicates()) {
            sql.append(rm.missing() ? " AND NOT EXISTS" : " AND EXISTS")
               .append(" (SELECT 1 FROM state.%s_reference r WHERE r.owner_id = d.id AND r.ref_type = ?)"
                       .formatted(d));
            params.add(rm.refType());
        }
        for (Criteria.Chained ch : criteria.chainedPredicates()) {
            String td = registry.require(ch.targetType()).domain();
            switch (ch.target()) {
                case Criteria.ChainTarget.ByIdentifier bi -> {
                    sql.append(" AND EXISTS (SELECT 1 FROM state.%s_reference r".formatted(d))
                       .append(" JOIN state.%s_identifier ti ON ti.object_id::text = r.target_id".formatted(td))
                       .append(" AND ti.type = r.target_type WHERE r.owner_id = d.id")
                       .append(" AND r.ref_type = ? AND r.target_type = ?");
                    params.add(ch.refPath());
                    params.add(ch.targetType());
                    if (bi.system() != null) {
                        sql.append(" AND ti.system = ?");
                        params.add(bi.system());
                    }
                    if (bi.value() != null) {
                        sql.append(" AND ti.value = ?");
                        params.add(bi.value());
                    }
                    sql.append(')');
                }
                case Criteria.ChainTarget.ByEq be -> {
                    sql.append(" AND EXISTS (SELECT 1 FROM state.%s_reference r".formatted(d))
                       .append(" JOIN state.%s_data td ON td.id::text = r.target_id AND NOT td.deleted".formatted(td))
                       .append(" WHERE r.owner_id = d.id AND r.ref_type = ? AND r.target_type = ?")
                       .append(" AND td.envelope @> ?::jsonb)");
                    params.add(ch.refPath());
                    params.add(ch.targetType());
                    params.add(JsonbCodec.containmentJson(
                            List.of(new Criteria.Eq(be.path(), be.value()))));
                }
            }
        }
    }

    private void appendOrder(Criteria criteria, StringBuilder sql, boolean withDefault) {
        if (criteria.sort() != null) {
            Criteria.Sort s = criteria.sort();
            sql.append(" ORDER BY ").append(Sql.typedPathExpression(s.path(), s.kind()));
            sql.append(s.ascending() ? " ASC" : " DESC").append(" NULLS LAST, d.id");
        } else if (criteria.sortLastUpdatedAscending() != null) {
            String dir = criteria.sortLastUpdatedAscending() ? "" : " DESC";
            sql.append(" ORDER BY d.last_updated").append(dir).append(", d.id").append(dir);
        } else if (withDefault) {
            sql.append(" ORDER BY d.last_updated, d.id");
        }
    }

    private static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String rangeOp(Criteria.RangeOp op) {
        return switch (op) {
            case GT -> ">";
            case LT -> "<";
            case GE -> ">=";
            case LE -> "<=";
        };
    }

    @Override
    public cloud.jengu.dbo.core.api.feed.FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
        TypeRegistration type = registry.require(criteria.typeName());
        String d = type.domain();
        Criteria.Sort s = criteria.sort();
        boolean ascending = s != null ? s.ascending()
                : criteria.sortLastUpdatedAscending() == null || criteria.sortLastUpdatedAscending();
        String sortExpr = s == null ? "d.last_updated" : Sql.typedPathExpression(s.path(), s.kind());

        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.type, d.version_id, d.last_updated, d.payload, d.deleted, d.payload_version, %s AS sort_key
                FROM state.%s_data d WHERE d.type = ? AND NOT d.deleted""".formatted(sortExpr, d));
        List<Object> params = new ArrayList<>();
        params.add(criteria.typeName());
        appendWhere(criteria, d, sql, params);

        if (cursor != null) {
            String[] keyset = Cursors.decodeKeyset(cursor);
            sql.append(" AND (").append(sortExpr).append(", d.id) ")
                    .append(ascending ? ">" : "<").append(" (?, ?)");
            params.add(sortParam(s, keyset[0]));
            params.add(UUID.fromString(keyset[1]));
        }
        String dir = ascending ? "" : " DESC";
        sql.append(" ORDER BY ").append(sortExpr).append(dir).append(", d.id").append(dir)
                .append(" LIMIT ?");
        params.add(criteria.limitValue());

        List<StoredObject> items = new ArrayList<>();
        String[] lastKey = new String[2];
        withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(read(rs));
                        Object sortKey = rs.getObject(8);
                        lastKey[0] = sortKey instanceof Timestamp ts
                                ? ts.toInstant().toString() : String.valueOf(sortKey);
                        lastKey[1] = rs.getObject(1).toString();
                    }
                }
            }
            return null;
        });
        String next = items.isEmpty() ? null : Cursors.encodeKeyset(lastKey[0], lastKey[1]);
        return new cloud.jengu.dbo.core.api.feed.FeedChunk<>(
                items.stream().map(o -> upgraded(type, o)).toList(), next,
                items.size() < criteria.limitValue());
    }

    private Object sortParam(Criteria.Sort s, String sortValue) {
        if (s == null) {
            return Timestamp.from(Instant.parse(sortValue));
        }
        return switch (s.kind()) {
            case NUMBER -> new java.math.BigDecimal(sortValue);
            case DATE, STRING, TOKEN, REFERENCE -> sortValue; // date keys are fixed-width text
        };
    }

    // --------------------------------------------------------------- delete

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        delete(typeName, id, expectedVersion, Handling.Authority.TENANT_USERS);
    }

    /** As {@link #delete(String, String, Long)}, saying who is asking. */
    public void delete(String typeName, String id, Long expectedVersion,
            Handling.Authority caller) {
        TypeRegistration type = registry.require(typeName);
        Handling handling = type.handling();
        // A delete is never a create, so append-only refuses it outright.
        switch (handling.refusalFor(caller, false)) {
            case APPEND_ONLY -> throw new HandlingRefusedException(typeName, "append-only",
                    "it may be written once and never removed — what the system recorded "
                            + "about who did what stays what it recorded");
            case READ_ONLY_HERE -> throw new HandlingRefusedException(typeName,
                    "read-only-here", "it is published by " + handling.authority()
                            + " and only that lane may remove it");
            case null -> { }
        }
        String d = type.domain();
        UUID uuid = UUID.fromString(id);
        inTx(c -> {
            Long current = lockVersion(c, d, typeName, uuid);
            if (current == null) {
                return null; // deleting the absent is a no-op (idempotent)
            }
            if (expectedVersion != null && !expectedVersion.equals(current)) {
                throw new VersionConflictException(typeName, id, expectedVersion, current);
            }
            long newVersion = current + 1;
            Instant now = Instant.now();
            byte[] lastPayload;
            String lastPayloadVersion;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payload, payload_version FROM state.%s_data WHERE id = ?".formatted(d))) {
                ps.setObject(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    lastPayload = rs.getBytes(1);
                    lastPayloadVersion = rs.getString(2);
                }
            }
            // A deletion is a version too. An unchained tombstone would be
            // the gap: remove a record, leave no link, and the history reads as
            // if it never held one.
            byte[] tombstoneChain = VersionChain.link(previousChain(c, d, uuid), lastPayload,
                    newVersion, now, true);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE state.%s_data SET deleted = true, version_id = ?, last_updated = ?,
                    envelope = '{}'::jsonb, chain_hash = ? WHERE id = ?""".formatted(d))) {
                ps.setLong(1, newVersion);
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setBytes(3, tombstoneChain);
                ps.setObject(4, uuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM state.%s_identifier WHERE object_id = ?".formatted(d))) {
                ps.setObject(1, uuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM state.%s_reference WHERE owner_id = ?".formatted(d))) {
                ps.setObject(1, uuid);
                ps.executeUpdate();
            }
            insertHistory(c, d, uuid, typeName, newVersion, now, lastPayload, true,
                    lastPayloadVersion, tombstoneChain);
            insertOutbox(c, d, uuid, typeName, newVersion, "D");
            return null;
        });
    }

    // -------------------------------------------------------------- rebuild

    @Override
    public int rebuildEnvelopes(String typeName) {
        return rebuildEnvelopes(typeName, 500);
    }

    /**
     * Chunked reindex: each batch is its own SHORT transaction —
     * a large reindex never pins the instance's xmin for its whole duration
     * (the barrier-liveness lesson applied to our own worst case).
     * Idempotent per object, so interruption just means re-running.
     */
    public int rebuildEnvelopes(String typeName, int batchSize) {
        TypeRegistration type = registry.require(typeName);
        schema.applyIndexes(registry);
        String d = type.domain();
        int total = 0;
        UUID after = null;
        while (true) {
            final UUID cursor = after;
            record Row(UUID id, byte[] payload, String storedVersion) {}
            List<Row> batch = inTx(c -> {
                List<Row> rows = new ArrayList<>();
                String sql = cursor == null
                        ? "SELECT id, payload, payload_version FROM state.%s_data WHERE type = ? AND NOT deleted ORDER BY id LIMIT ?"
                        : "SELECT id, payload, payload_version FROM state.%s_data WHERE type = ? AND NOT deleted AND id > ? ORDER BY id LIMIT ?";
                try (PreparedStatement ps = c.prepareStatement(sql.formatted(d))) {
                    int p = 1;
                    ps.setString(p++, typeName);
                    if (cursor != null) {
                        ps.setObject(p++, cursor);
                    }
                    ps.setInt(p, batchSize);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new Row((UUID) rs.getObject(1), rs.getBytes(2), rs.getString(3)));
                        }
                    }
                }
                for (Row row : rows) {
                    byte[] current = upgraded(type, new StoredObject(row.id().toString(), typeName,
                            0, Instant.EPOCH, row.payload(), false, row.storedVersion())).payload();
                    Envelope envelope = type.extractor().extract(typeName, current);
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE state.%s_data SET envelope = ?::jsonb WHERE id = ?".formatted(d))) {
                        up.setString(1, JsonbCodec.envelopeJson(envelope.paths()));
                        up.setObject(2, row.id());
                        up.executeUpdate();
                    }
                    replaceIdentifiers(c, type, row.id(), envelope.identifiers());
                    // a reindex rewrites edges for rows that already have them
                    replaceReferences(c, d, row.id(), envelope.references(), false);
                }
                return rows;
            });
            total += batch.size();
            if (batch.size() < batchSize) {
                return total;
            }
            after = batch.get(batch.size() - 1).id();
        }
    }

    // -------------------------------------------------------------- helpers

    private Optional<String> resolveIdentity(Connection c, TypeRegistration type, Identifier ident)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT object_id FROM state.%s_identifier
                WHERE type = ? AND system = ? AND value = ? AND identity""".formatted(type.domain()))) {
            ps.setString(1, type.typeName());
            ps.setString(2, ident.system());
            ps.setString(3, ident.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1).toString()) : Optional.empty();
            }
        }
    }

    private long currentVersion(Connection c, TypeRegistration type, String id) throws SQLException {
        Long v = lockVersion(c, type.domain(), type.typeName(), UUID.fromString(id));
        return v == null ? 0 : v;
    }

    private Long lockVersion(Connection c, String d, String typeName, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT version_id FROM state.%s_data WHERE id = ? AND type = ? FOR UPDATE".formatted(d))) {
            ps.setObject(1, id);
            ps.setString(2, typeName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private StoredObject read(ResultSet rs) throws SQLException {
        return new StoredObject(
                rs.getObject(1).toString(),
                rs.getString(2),
                rs.getLong(3),
                rs.getTimestamp(4).toInstant(),
                rs.getBytes(5),
                rs.getBoolean(6),
                rs.getString(7));
    }

    private List<StoredObject> readAll(PreparedStatement ps) throws SQLException {
        List<StoredObject> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(read(rs));
            }
        }
        return out;
    }

    @FunctionalInterface
    private interface TxBody<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T inTx(TxBody<T> body) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = body.run(c);
                c.commit();
                return result;
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("transaction failed", e);
        }
    }

    private <T> T withConnection(TxBody<T> body) {
        try (Connection c = ds.getConnection()) {
            return body.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed", e);
        }
    }
}
