package cloud.jengu.dbo.sync;

import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PayloadConverter;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One declared content dependency's stream (§6): source feed → converted,
 * provenance-tagged, read-only copies in the target store.
 *
 * <p>Copies keep the SOURCE object id, so updates and deletes map naturally —
 * and because apply is an ordinary put, copies land in the TARGET's outbox,
 * which is exactly why chains compose hop-by-hop
 * (REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY: each engine only ever reads its direct
 * upstream).
 *
 * <p>Shadowing is conflict-driven (REQ-DBO-SYNC-LOCAL-SHADOWING): an apply
 * that hits an identity conflict against an object NOT of streamed origin is
 * a local override — the event parks in the shadow table (latest payload
 * kept) and {@link #reconcile()} re-attempts, so deleting the override falls
 * back to the live upstream version.
 *
 * <p>In production the platform plane instantiates these engines (§7.4 — no
 * direct tenant-to-tenant connection); this component is the stream mechanic.
 */
public final class ContentSyncEngine {

    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private final ContentDependency dependency;
    private final ChangeFeed sourceFeed;
    private final ObjectStore targetStore;
    private final DataSource targetDs;
    private final String targetDomain;
    private final String targetPayloadVersion;
    private final String consumer;
    private final Map<String, PayloadConverter> convertersByFrom = new LinkedHashMap<>();

    public ContentSyncEngine(ContentDependency dependency, ChangeFeed sourceFeed,
            ObjectStore targetStore, DataSource targetDataSource, String targetDomain,
            String targetPayloadVersion, List<PayloadConverter> converters) {
        this(dependency, sourceFeed, targetStore, targetDataSource, targetDomain,
                targetPayloadVersion, converters, null);
    }

    /**
     * The ack cursor lives in the SOURCE feed keyed by consumer — when several
     * dependents declare the same upstream, each needs its own consumer id or
     * they share a cursor and split the event stream between them (dbo#30).
     */
    public ContentSyncEngine(ContentDependency dependency, ChangeFeed sourceFeed,
            ObjectStore targetStore, DataSource targetDataSource, String targetDomain,
            String targetPayloadVersion, List<PayloadConverter> converters, String consumer) {
        if (!DOMAIN.matcher(targetDomain).matches()) {
            throw new IllegalArgumentException("invalid domain: " + targetDomain);
        }
        this.consumer = consumer;
        this.dependency = dependency;
        this.sourceFeed = sourceFeed;
        this.targetStore = targetStore;
        this.targetDs = targetDataSource;
        this.targetDomain = targetDomain;
        this.targetPayloadVersion = targetPayloadVersion;
        for (PayloadConverter converter : converters) {
            convertersByFrom.put(converter.fromVersion(), converter);
        }
        ensureTables();
    }

    private String consumer() {
        return consumer != null ? consumer : "sync." + dependency.name();
    }

    // -------------------------------------------------------------- syncing

    /** One sync round: read the upstream feed, apply declared changes, ack. Returns events seen. */
    public int syncOnce(int chunkSize) {
        FeedChunk<FeedItem> chunk = sourceFeed.readFor(consumer(), chunkSize);
        if (chunk.items().isEmpty()) {
            return 0;
        }
        for (FeedItem item : chunk.items()) {
            if (!dependency.declaredTypes().contains(item.typeName())) {
                continue; // REQ-DBO-SYNC-DECLARED-ONLY: nothing syncs undeclared
            }
            applyItem(item);
        }
        sourceFeed.ack(consumer(), chunk.nextCursor());
        return chunk.items().size();
    }

    /** Re-attempts parked (shadowed) events — the fallback path after a local override is removed. */
    public int reconcile() {
        int applied = 0;
        for (ShadowedEvent shadowed : shadowedEvents()) {
            FeedItem replay = new FeedItem(0, shadowed.objectId(), shadowed.typeName(),
                    shadowed.sourceVersionId(), shadowed.deleted() ? ChangeKind.DELETED : ChangeKind.UPDATED,
                    java.time.Instant.now(), shadowed.payload(), shadowed.deleted(),
                    shadowed.payloadVersion());
            if (tryApply(replay)) {
                removeShadow(shadowed.objectId());
                applied++;
            }
        }
        return applied;
    }

    private void applyItem(FeedItem item) {
        if (!tryApply(item)) {
            park(item);
        }
    }

    /** True when applied; false when shadowed by a local override. */
    private boolean tryApply(FeedItem item) {
        if (item.kind() == ChangeKind.DELETED || item.deleted()) {
            targetStore.delete(item.typeName(), item.objectId(), null);
            recordOrigin(item, null);
            return true;
        }
        byte[] payload = item.payload();
        String version = item.payloadVersion();
        try {
            for (int hops = 0; hops < 8 && !version.equals(targetPayloadVersion); hops++) {
                PayloadConverter converter = convertersByFrom.get(version);
                if (converter == null) {
                    throw new IllegalStateException(
                            "no converter from " + version + " toward " + targetPayloadVersion);
                }
                payload = converter.convert(item.typeName(), payload);
                version = converter.toVersion();
            }
            if (!version.equals(targetPayloadVersion)) {
                throw new IllegalStateException("converter chain did not reach " + targetPayloadVersion);
            }
        } catch (RuntimeException conversionFailure) {
            // REQ-DBO-SYNC-CONVERT-ON-APPLY: dead-letter visibly, never skip silently
            deadLetter(item, String.valueOf(conversionFailure.getMessage()));
            return true; // handled (visible); not a shadow
        }
        try {
            targetStore.put(new PutRequest(item.typeName(), item.objectId(), null, payload));
            recordOrigin(item, version);
            return true;
        } catch (IdentityConflictException conflict) {
            if (isStreamedOrigin(conflict.existingId())) {
                // stale claim from an earlier copy of ours — surface loudly
                throw conflict;
            }
            return false; // local override wins: REQ-DBO-SYNC-LOCAL-SHADOWING
        }
    }

    // ---------------------------------------------------------- bookkeeping

    /** REQ-DBO-SYNC-PROVENANCE-COPIES: why this tenant has this object, always answerable. */
    public record Origin(String objectId, String typeName, long sourceVersionId,
            String appliedPayloadVersion, java.time.Instant syncedAt) {}

    public List<Origin> origins() {
        List<Origin> out = new ArrayList<>();
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT object_id, type, source_version_id, applied_payload_version, synced_at
                     FROM state.%s_sync_origin WHERE dependency = ? ORDER BY synced_at"""
                     .formatted(targetDomain))) {
            ps.setString(1, dependency.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Origin(rs.getObject(1).toString(), rs.getString(2), rs.getLong(3),
                            rs.getString(4), rs.getTimestamp(5).toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("origin read failed", e);
        }
        return out;
    }

    public boolean isStreamedOrigin(String objectId) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM state.%s_sync_origin WHERE object_id = ?".formatted(targetDomain))) {
            ps.setObject(1, UUID.fromString(objectId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("origin lookup failed", e);
        }
    }

    public record DeadLetter(String objectId, String typeName, long sourceVersionId, String reason) {}

    public List<DeadLetter> deadLetters() {
        List<DeadLetter> out = new ArrayList<>();
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT object_id, type, source_version_id, reason
                     FROM state.%s_sync_dlq WHERE dependency = ? ORDER BY created_at"""
                     .formatted(targetDomain))) {
            ps.setString(1, dependency.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DeadLetter(rs.getObject(1).toString(), rs.getString(2),
                            rs.getLong(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("dlq read failed", e);
        }
        return out;
    }

    /** A dependency with dead letters is degraded — visible, never silent. */
    public boolean degraded() {
        return !deadLetters().isEmpty();
    }

    public record ShadowedEvent(String objectId, String typeName, long sourceVersionId,
            byte[] payload, boolean deleted, String payloadVersion) {}

    public List<ShadowedEvent> shadowedEvents() {
        List<ShadowedEvent> out = new ArrayList<>();
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT object_id, type, source_version_id, payload, deleted, payload_version
                     FROM state.%s_sync_shadow WHERE dependency = ?""".formatted(targetDomain))) {
            ps.setString(1, dependency.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ShadowedEvent(rs.getObject(1).toString(), rs.getString(2),
                            rs.getLong(3), rs.getBytes(4), rs.getBoolean(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shadow read failed", e);
        }
        return out;
    }

    // ------------------------------------------------------------- plumbing

    private void recordOrigin(FeedItem item, String appliedVersion) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_sync_origin
                       (object_id, dependency, type, source_version_id, applied_payload_version, synced_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (object_id) DO UPDATE SET
                       source_version_id = EXCLUDED.source_version_id,
                       applied_payload_version = EXCLUDED.applied_payload_version,
                       synced_at = now()""".formatted(targetDomain))) {
            ps.setObject(1, UUID.fromString(item.objectId()));
            ps.setString(2, dependency.name());
            ps.setString(3, item.typeName());
            ps.setLong(4, item.versionId());
            ps.setString(5, appliedVersion);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("origin write failed", e);
        }
    }

    private void park(FeedItem item) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_sync_shadow
                       (dependency, object_id, type, source_version_id, payload, deleted, payload_version, parked_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, now())
                     ON CONFLICT (dependency, object_id) DO UPDATE SET
                       source_version_id = EXCLUDED.source_version_id,
                       payload = EXCLUDED.payload,
                       deleted = EXCLUDED.deleted,
                       payload_version = EXCLUDED.payload_version,
                       parked_at = now()""".formatted(targetDomain))) {
            ps.setString(1, dependency.name());
            ps.setObject(2, UUID.fromString(item.objectId()));
            ps.setString(3, item.typeName());
            ps.setLong(4, item.versionId());
            ps.setBytes(5, item.payload());
            ps.setBoolean(6, item.deleted());
            ps.setString(7, item.payloadVersion());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("shadow write failed", e);
        }
    }

    private void removeShadow(String objectId) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM state.%s_sync_shadow WHERE dependency = ? AND object_id = ?"
                             .formatted(targetDomain))) {
            ps.setString(1, dependency.name());
            ps.setObject(2, UUID.fromString(objectId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("shadow delete failed", e);
        }
    }

    private void deadLetter(FeedItem item, String reason) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_sync_dlq
                       (dependency, object_id, type, source_version_id, reason, created_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (dependency, object_id, source_version_id) DO NOTHING"""
                     .formatted(targetDomain))) {
            ps.setString(1, dependency.name());
            ps.setObject(2, UUID.fromString(item.objectId()));
            ps.setString(3, item.typeName());
            ps.setLong(4, item.versionId());
            ps.setString(5, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("dlq write failed", e);
        }
    }

    private void ensureTables() {
        try (Connection c = targetDs.getConnection()) {
            for (String ddl : List.of(
                    """
                    CREATE TABLE IF NOT EXISTS state.%s_sync_origin (
                      object_id uuid PRIMARY KEY,
                      dependency text NOT NULL,
                      type text NOT NULL,
                      source_version_id bigint NOT NULL,
                      applied_payload_version text,
                      synced_at timestamptz NOT NULL
                    )""".formatted(targetDomain),
                    """
                    CREATE TABLE IF NOT EXISTS state.%s_sync_shadow (
                      dependency text NOT NULL,
                      object_id uuid NOT NULL,
                      type text NOT NULL,
                      source_version_id bigint NOT NULL,
                      payload bytea NOT NULL,
                      deleted boolean NOT NULL,
                      payload_version text NOT NULL,
                      parked_at timestamptz NOT NULL,
                      PRIMARY KEY (dependency, object_id)
                    )""".formatted(targetDomain),
                    """
                    CREATE TABLE IF NOT EXISTS state.%s_sync_dlq (
                      dependency text NOT NULL,
                      object_id uuid NOT NULL,
                      type text NOT NULL,
                      source_version_id bigint NOT NULL,
                      reason text NOT NULL,
                      created_at timestamptz NOT NULL,
                      PRIMARY KEY (dependency, object_id, source_version_id)
                    )""".formatted(targetDomain))) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("sync schema setup failed", e);
        }
    }
}
