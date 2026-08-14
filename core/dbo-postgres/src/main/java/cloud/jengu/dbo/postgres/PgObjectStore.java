package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.TypeRegistry;
import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
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

    public PgObjectStore(DataSource dataSource, List<TypeRegistration> registrations) {
        this.ds = dataSource;
        this.registry = new TypeRegistry(registrations);
        this.schema = new SchemaManager(dataSource);
        schema.ensureSchema(registry);
    }

    // ------------------------------------------------------------------ put

    @Override
    public PutResult put(PutRequest request) {
        TypeRegistration type = registry.require(request.typeName());
        return inTx(c -> writeObject(c, type, request));
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

    private PutResult writeObject(Connection c, TypeRegistration type, PutRequest request) throws SQLException {
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
        Instant now = Instant.now();

        Envelope envelope = type.extractor().extract(type.typeName(), request.payload());
        String envelopeJson = JsonbCodec.envelopeJson(envelope.paths());

        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO state.%s_data (id, type, version_id, last_updated, envelope, payload, deleted)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, false)
                ON CONFLICT (id) DO UPDATE SET
                  version_id = EXCLUDED.version_id,
                  last_updated = EXCLUDED.last_updated,
                  envelope = EXCLUDED.envelope,
                  payload = EXCLUDED.payload,
                  deleted = false""".formatted(d))) {
            ps.setObject(1, uuid);
            ps.setString(2, type.typeName());
            ps.setLong(3, newVersion);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setString(5, envelopeJson);
            ps.setBytes(6, request.payload());
            ps.executeUpdate();
        }

        replaceIdentifiers(c, type, uuid, envelope.identifiers());
        replaceReferences(c, d, uuid, envelope.references());
        insertHistory(c, d, uuid, type.typeName(), newVersion, now, request.payload(), false);
        insertOutbox(c, d, uuid, type.typeName(), newVersion, created ? "C" : "U");

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

    private void replaceReferences(Connection c, String d, UUID id, List<Envelope.ReferenceEdge> references)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM state.%s_reference WHERE owner_id = ?".formatted(d))) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        for (Envelope.ReferenceEdge edge : references) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO state.%s_reference (owner_id, ref_type, target_type, target_id)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""".formatted(d))) {
                ps.setObject(1, id);
                ps.setString(2, edge.refType());
                ps.setString(3, edge.targetType());
                ps.setString(4, edge.targetId());
                ps.executeUpdate();
            }
        }
    }

    private void insertHistory(Connection c, String d, UUID id, String type, long version,
            Instant at, byte[] payload, boolean deleted) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO history.%s_history (id, version_id, type, last_updated, payload, deleted)
                VALUES (?, ?, ?, ?, ?, ?)""".formatted(d))) {
            ps.setObject(1, id);
            ps.setLong(2, version);
            ps.setString(3, type);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setBytes(5, payload);
            ps.setBoolean(6, deleted);
            ps.executeUpdate();
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
                    SELECT id, type, version_id, last_updated, payload, deleted
                    FROM state.%s_data WHERE id = ? AND type = ? AND NOT deleted""".formatted(type.domain()))) {
                ps.setObject(1, UUID.fromString(id));
                ps.setString(2, typeName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
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
        String sql = """
                SELECT DISTINCT d.id, d.type, d.version_id, d.last_updated, d.payload, d.deleted
                FROM state.%s_data d
                JOIN state.%s_identifier i ON i.object_id = d.id
                WHERE d.type = ? AND NOT d.deleted AND (%s)""".formatted(type.domain(), type.domain(), or);
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int p = 1;
                ps.setString(p++, typeName);
                for (Identifier ident : identifiers) {
                    ps.setString(p++, ident.system());
                    ps.setString(p++, ident.value());
                }
                return readAll(ps);
            }
        });
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        TypeRegistration type = registry.require(typeName);
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, type, version_id, last_updated, payload, deleted
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
                SELECT d.id, d.type, d.version_id, d.last_updated, d.payload, d.deleted
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
                return readAll(ps);
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
                SELECT d.id, d.type, d.version_id, d.last_updated, d.payload, d.deleted, %s AS sort_key
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
                        Object sortKey = rs.getObject(7);
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
                items, next, items.size() < criteria.limitValue());
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
        TypeRegistration type = registry.require(typeName);
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
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payload FROM state.%s_data WHERE id = ?".formatted(d))) {
                ps.setObject(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    lastPayload = rs.getBytes(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE state.%s_data SET deleted = true, version_id = ?, last_updated = ?,
                    envelope = '{}'::jsonb WHERE id = ?""".formatted(d))) {
                ps.setLong(1, newVersion);
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setObject(3, uuid);
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
            insertHistory(c, d, uuid, typeName, newVersion, now, lastPayload, true);
            insertOutbox(c, d, uuid, typeName, newVersion, "D");
            return null;
        });
    }

    // -------------------------------------------------------------- rebuild

    @Override
    public int rebuildEnvelopes(String typeName) {
        TypeRegistration type = registry.require(typeName);
        schema.applyIndexes(registry);
        String d = type.domain();
        return inTx(c -> {
            int count = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, payload FROM state.%s_data WHERE type = ? AND NOT deleted".formatted(d))) {
                ps.setString(1, typeName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = (UUID) rs.getObject(1);
                        byte[] payload = rs.getBytes(2);
                        Envelope envelope = type.extractor().extract(typeName, payload);
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE state.%s_data SET envelope = ?::jsonb WHERE id = ?".formatted(d))) {
                            up.setString(1, JsonbCodec.envelopeJson(envelope.paths()));
                            up.setObject(2, id);
                            up.executeUpdate();
                        }
                        replaceIdentifiers(c, type, id, envelope.identifiers());
                        replaceReferences(c, d, id, envelope.references());
                        count++;
                    }
                }
            }
            return count;
        });
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
                rs.getBoolean(6));
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
