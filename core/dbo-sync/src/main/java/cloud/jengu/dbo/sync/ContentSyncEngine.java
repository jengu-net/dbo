package cloud.jengu.dbo.sync;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.face.GrainCodec;
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
    private final GrainCodec sourceGrain;
    private final GrainCodec targetGrain;
    private final String consumer;
    private final Map<String, PayloadConverter> convertersByFrom = new LinkedHashMap<>();
    private volatile cloud.jengu.dbo.work.Runs runs;

    public ContentSyncEngine(ContentDependency dependency, ChangeFeed sourceFeed,
            ObjectStore targetStore, DataSource targetDataSource, String targetDomain,
            String targetPayloadVersion, List<PayloadConverter> converters) {
        this(dependency, sourceFeed, targetStore, targetDataSource, targetDomain,
                targetPayloadVersion, converters, null);
    }

    /**
     * The ack cursor lives in the SOURCE feed keyed by consumer — when several
     * dependents declare the same upstream, each needs its own consumer id or
     * they share a cursor and split the event stream between them.
     */
    public ContentSyncEngine(ContentDependency dependency, ChangeFeed sourceFeed,
            ObjectStore targetStore, DataSource targetDataSource, String targetDomain,
            String targetPayloadVersion, List<PayloadConverter> converters, String consumer) {
        this(dependency, sourceFeed, targetStore, targetDataSource, targetDomain,
                targetPayloadVersion, converters, consumer, null, null);
    }

    /**
     * With the grain codecs of both ends
     * (REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES).
     *
     * <p>Two, not one: reassembly reads the SOURCE's native form and the
     * destination writes its own. One codec would be one store's, and the
     * dependent would rebuild from concepts it does not hold.
     */
    public ContentSyncEngine(ContentDependency dependency, ChangeFeed sourceFeed,
            ObjectStore targetStore, DataSource targetDataSource, String targetDomain,
            String targetPayloadVersion, List<PayloadConverter> converters, String consumer,
            GrainCodec sourceGrain, GrainCodec targetGrain) {
        this.sourceGrain = sourceGrain;
        this.targetGrain = targetGrain;
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

    /**
     * Records this stream's rounds as runs in the dependent tenant's store
     *.
     *
     * <p>The run belongs to the <b>dependent</b> — it is their work — with the
     * upstream named rather than parented, because parenthood cannot cross a
     * tenant and a tenant is a legal person.
     *
     * <p>Declared rather than assumed: this component is also the stream
     * mechanic used on its own, and a mechanic with nowhere to write runs is a
     * different thing from one that has forgotten to.
     */
    public ContentSyncEngine withRuns(cloud.jengu.dbo.work.Runs runs) {
        this.runs = runs;
        return this;
    }

    /** What this stream is called wherever runs are read. */
    public static final String PROCESS = "dbo.sync.stream";

    /** Its one step: applying what the upstream published. */
    public static final String STEP = "apply";

    /**
     * One recorded round: read the upstream, re-attempt what is parked, and say
     * what is left.
     *
     * <p>A <b>sweep</b>, because a stream closes when it agrees with its
     * upstream and never when a list runs out. What is still parked after the
     * round is an item held by a person — a local override is shadowing an
     * upstream version, and no amount of retrying decides that — and it closes
     * itself on the pass that stops finding it, so removing the override closes
     * the card without anybody clicking resolved.
     *
     * <p>An unreachable upstream is a <b>retry</b> and nobody's card. A queue
     * that collects "the other tenant was down" stops being read, and then the
     * override that needed a person is in it.
     */
    public cloud.jengu.dbo.work.Run pass(int chunkSize) {
        if (runs == null) {
            throw new IllegalStateException("this stream was not given anywhere to record runs — "
                    + "withRuns(...) is how a mechanic becomes a stream somebody can watch");
        }
        cloud.jengu.dbo.work.Run sweep = runs.sweep(PROCESS, STEP, dependency.name(),
                List.of(targetDomain));
        cloud.jengu.dbo.work.Runs.Pass pass = runs.pass(sweep);
        long seen = 0;
        boolean reachable = true;
        try {
            seen = syncOnce(chunkSize);
        } catch (RuntimeException unreachable) {
            reachable = false;
            pass.item("upstream:" + dependency.name(),
                    cloud.jengu.dbo.work.Failure.of(unreachable),
                    String.valueOf(unreachable.getMessage()));
        }
        long applied = reachable ? reconcile() : 0;
        List<ShadowedEvent> parked = shadowedEvents();
        for (ShadowedEvent shadow : parked) {
            pass.item(shadow.typeName() + "/" + shadow.objectId(),
                    cloud.jengu.dbo.work.Failure.RECORD,
                    "a local override shadows the upstream version — remove the override and "
                            + "the upstream version applies again");
        }
        List<DeadLetter> undelivered = deadLetters();
        for (DeadLetter dead : undelivered) {
            pass.item(dead.typeName() + "/" + dead.objectId(),
                    cloud.jengu.dbo.work.Failure.RECORD, dead.reason());
        }
        return pass.counted("seen", seen)
                .counted("applied", applied)
                .counted("parked", parked.size())
                .counted("undelivered", undelivered.size())
                .done();
    }

    private String consumer() {
        return consumer != null ? consumer : "sync." + dependency.name();
    }

    /**
     * What this stream is called where somebody is reading about it: the
     * consumer name, which already says which dependency into which tenant.
     * Here so a round can say which stream spent the time rather than that
     * some stream did.
     */
    public String name() {
        return consumer();
    }

    // -------------------------------------------------------------- syncing

    /** One sync round: read the upstream feed, apply declared changes, ack. Returns events seen. */
    public int syncOnce(int chunkSize) {
        FeedChunk<FeedItem> chunk = sourceFeed.readFor(consumer(), chunkSize);
        if (chunk.items().isEmpty()) {
            return 0;
        }
        List<FeedItem> declared = chunk.items().stream()
                .filter(item -> dependency.declaredTypes().contains(item.typeName()))
                .toList(); // REQ-DBO-SYNC-DECLARED-ONLY: nothing syncs undeclared
        if (!applyTogether(declared)) {
            for (FeedItem item : declared) {
                applyItem(item);
            }
        }
        sourceFeed.ack(consumer(), chunk.nextCursor());
        return chunk.items().size();
    }

    /**
     * A chunk of plain upserts as ONE unit: one transaction for the writes,
     * one statement for their origins. Applying a first sync one item at a
     * time cost eight statements and two connections per definition, and a
     * subscriber taking its version from a face root drained two thousand of
     * them before it could serve — measured at twenty seconds, of which the
     * parse this was blamed on was under three.
     *
     * <p>Only a chunk that needs nothing decided per item qualifies: no
     * deletions, no conversion. The first sync onto an empty store is exactly
     * that chunk. An identity conflict is decided per item — a local override
     * shadows, a verbatim copy is skipped — so a unit that meets one is rolled
     * back whole, nothing written, and the chunk takes the per-item path that
     * knows how to decide. Returns false when the chunk did not qualify or
     * was rolled back.
     */
    private boolean applyTogether(List<FeedItem> items) {
        if (items.isEmpty()) {
            return true;
        }
        for (FeedItem item : items) {
            if (item.kind() == ChangeKind.DELETED || item.deleted()
                    || !item.payloadVersion().equals(targetPayloadVersion)) {
                return false;
            }
        }
        List<byte[]> transported = new ArrayList<>(items.size());
        List<PutRequest> requests = new ArrayList<>(items.size());
        for (FeedItem item : items) {
            byte[] payload = sourceGrain != null && sourceGrain.handles(item.typeName())
                    ? sourceGrain.forTransport(item.typeName(), item.payload())
                    : item.payload();
            byte[] stored = targetGrain != null && targetGrain.handles(item.typeName())
                    ? targetGrain.storedFormOf(item.typeName(), payload)
                    : payload;
            transported.add(payload);
            requests.add(new PutRequest(item.typeName(), item.objectId(), null, stored)
                    .stamped(item.shape()));
        }
        try {
            targetStore.transact(requests, cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);
        } catch (IdentityConflictException conflict) {
            return false;
        }
        if (targetGrain != null) {
            List<GrainCodec.Part> parts = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                FeedItem item = items.get(i);
                if (targetGrain.handles(item.typeName())) {
                    parts.add(new GrainCodec.Part(item.typeName(), transported.get(i)));
                }
            }
            targetGrain.keep(parts);
        }
        recordOrigins(items, targetPayloadVersion);
        return true;
    }

    /** Re-attempts parked (shadowed) events — the fallback path after a local override is removed. */
    public int reconcile() {
        int applied = 0;
        for (ShadowedEvent shadowed : shadowedEvents()) {
            FeedItem replay = new FeedItem(0, shadowed.objectId(), shadowed.typeName(),
                    shadowed.sourceVersionId(), shadowed.deleted() ? ChangeKind.DELETED : ChangeKind.UPDATED,
                    java.time.Instant.now(), shadowed.payload(), shadowed.deleted(),
                    shadowed.payloadVersion());
            if (tryApply(replay) == null) {
                removeShadow(shadowed.objectId());
                applied++;
            }
        }
        return applied;
    }

    private void applyItem(FeedItem item) {
        String shadowedBy = tryApply(item);
        if (shadowedBy != null) {
            park(item, shadowedBy);
        }
    }

    /**
     * Null when applied; otherwise the id of the LOCAL record whose override
     * shadows this item. Returned rather than swallowed: the shadow row
     * records which record stands in front of it, so the serving path can say
     * so on that record -- a parked shadow was visible only to whoever queried
     * the sync engine, where one can sit unnoticed for a very long time.
     */
    private String tryApply(FeedItem item) {
        if (item.kind() == ChangeKind.DELETED || item.deleted()) {
            targetStore.delete(item.typeName(), item.objectId(), null);
            recordOrigin(item, null);
            return null;
        }
        // REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES: what the source STORES is not
        // always what a dependent needs to receive. A CodeSystem is kept as a
        // shell with its concepts in the native form, and a shell alone cannot
        // be rebuilt into anything a dependent can answer with. The stream
        // carries the whole thing; each end keeps its own form.
        byte[] payload = sourceGrain != null && sourceGrain.handles(item.typeName())
                ? sourceGrain.forTransport(item.typeName(), item.payload())
                : item.payload();
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
            return null; // handled (visible); not a shadow
        }
        // Computed before the try, not inside it: the conflict handler
        // compares what WOULD have been written against what is already here,
        // so it has to be in scope where the conflict is caught.
        // the destination takes the wire form apart into its own: concepts
        // to the native store, and the shell back here to be written under
        // the source's identity like everything else
        byte[] stored = targetGrain != null && targetGrain.handles(item.typeName())
                ? targetGrain.storedFormOf(item.typeName(), payload)
                : payload;
        try {
            // As the replication lane, which is what this is. A type declared
            // read-only-here refuses every other caller, and the lane that may
            // write it has to say so — the alternative is inferring it from
            // whichever credential happened to be in play, which would make
            // the shield depend on deployment wiring rather than on what the
            // code is doing.
            // the destination takes the wire form apart into its own: concepts
            // to the native store, and the shell back here to be written under
            // the source's identity like everything else
            // The stamp travels with the copy: a mirrored record keeps the
            // shape stamp of the store that VALIDATED it — the receiving
            // store never did (REQ-DBO-SHAPE-MIRRORED-KEEPS-ITS-STAMP).
            targetStore.put(new PutRequest(item.typeName(), item.objectId(), null, stored)
                            .stamped(item.shape()),
                    cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);
            // Only now, with the write ACCEPTED, do the parts with their own
            // home land there: taking a CodeSystem apart before the
            // engine had ruled replaced a local override's concepts with the
            // parked publication's -- the shadow protected the document and
            // lost the answers, and $lookup served the copy shadowing was
            // built to hold back.
            if (targetGrain != null && targetGrain.handles(item.typeName())) {
                targetGrain.keep(item.typeName(), payload);
            }
            recordOrigin(item, version);
            return null;
        } catch (IdentityConflictException conflict) {
            if (isStreamedOrigin(conflict.existingId())) {
                // stale claim from an earlier copy of ours — surface loudly
                throw conflict;
            }
            if (alreadyHeldVerbatim(item.typeName(), conflict.existingId(), payload)) {
                // Not an override: the same publication, held here already.
                //
                // Shadowing exists to protect a local DIFFERENCE — somebody
                // here decided something other than what upstream says, and
                // that decision must not be overwritten by a stream. Identical
                // content has no difference to protect, and parking it made a
                // shadow nobody could clear: every round re-attempted it, every
                // round raised a unique-constraint violation in the database
                // log, and removing "the override" was impossible because
                // there was none.
                //
                // The engine's own vocabulary is how this arises. Every tenant
                // is given it at bring-up so that urn:dbo: codes resolve in the
                // tenant that served them; a tenant that also inherits
                // CodeSystem is given the identical publication twice, once by
                // each route.
                return null;
            }
            return conflict.existingId(); // local override wins: REQ-DBO-SYNC-LOCAL-SHADOWING
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
                     FROM %s_sync_origin WHERE dependency = ? ORDER BY synced_at"""
                     .formatted(Domains.tables(targetDomain)))) {
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

    /**
     * Whether the object already here is byte-identical to what arrived.
     *
     * <p>Byte equality on purpose, rather than "same canonical" — two objects
     * claiming one canonical are the same THING, but only identical content
     * makes them the same VERSION of it, and a stream that treated a differing
     * upstream copy as already-applied would silently drop an update. What is
     * compared is the stored form on both sides: the payload this tenant holds,
     * against the payload about to be written after the grain has taken the
     * wire form apart.
     */
    /**
     * Whether the local claimant already holds this publication, judged on the
     * WHOLE thing. For a grain type the stored form is a shell, and two
     * shells are byte-equal whenever their counts are — so comparing stored
     * forms judged two different code lists to be the same publication, deduped
     * the arrival, and the shadow that should have said 'a local decision
     * stands here' was never parked. The local side is reassembled through the
     * same codec the wire form came through, so identical publications still
     * compare equal byte for byte.
     */
    private boolean alreadyHeldVerbatim(String typeName, String existingId, byte[] arrivingWire) {
        return targetStore.get(typeName, existingId)
                .map(held -> {
                    byte[] local = targetGrain != null && targetGrain.handles(typeName)
                            ? targetGrain.forTransport(typeName, held.payload())
                            : held.payload();
                    return java.util.Arrays.equals(local, arrivingWire);
                })
                .orElse(false);
    }

    public boolean isStreamedOrigin(String objectId) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM %s_sync_origin WHERE object_id = ?".formatted(Domains.tables(targetDomain)))) {
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
                     FROM %s_sync_dlq WHERE dependency = ? ORDER BY created_at"""
                     .formatted(Domains.tables(targetDomain)))) {
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
                     FROM %s_sync_shadow WHERE dependency = ?""".formatted(Domains.tables(targetDomain)))) {
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
        recordOrigins(List.of(item), appliedVersion);
    }

    private void recordOrigins(List<FeedItem> items, String appliedVersion) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO %s_sync_origin
                       (object_id, dependency, type, source_version_id, applied_payload_version, synced_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (object_id) DO UPDATE SET
                       source_version_id = EXCLUDED.source_version_id,
                       applied_payload_version = EXCLUDED.applied_payload_version,
                       synced_at = now()""".formatted(Domains.tables(targetDomain)))) {
            for (FeedItem item : items) {
                ps.setObject(1, UUID.fromString(item.objectId()));
                ps.setString(2, dependency.name());
                ps.setString(3, item.typeName());
                ps.setLong(4, item.versionId());
                ps.setString(5, appliedVersion);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("origin write failed", e);
        }
    }

    private void park(FeedItem item, String shadowedBy) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO %s_sync_shadow
                       (dependency, object_id, type, source_version_id, payload, deleted, payload_version, parked_at, shadows_object_id)
                     VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?)
                     ON CONFLICT (dependency, object_id) DO UPDATE SET
                       source_version_id = EXCLUDED.source_version_id,
                       payload = EXCLUDED.payload,
                       deleted = EXCLUDED.deleted,
                       payload_version = EXCLUDED.payload_version,
                       parked_at = now(),
                       shadows_object_id = EXCLUDED.shadows_object_id""".formatted(Domains.tables(targetDomain)))) {
            ps.setString(1, dependency.name());
            ps.setObject(2, UUID.fromString(item.objectId()));
            ps.setString(3, item.typeName());
            ps.setLong(4, item.versionId());
            ps.setBytes(5, item.payload());
            ps.setBoolean(6, item.deleted());
            ps.setString(7, item.payloadVersion());
            ps.setObject(8, UUID.fromString(shadowedBy));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("shadow write failed", e);
        }
    }

    private void removeShadow(String objectId) {
        try (Connection c = targetDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM %s_sync_shadow WHERE dependency = ? AND object_id = ?"
                             .formatted(Domains.tables(targetDomain)))) {
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
                     INSERT INTO %s_sync_dlq
                       (dependency, object_id, type, source_version_id, reason, created_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (dependency, object_id, source_version_id) DO NOTHING"""
                     .formatted(Domains.tables(targetDomain)))) {
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
                    CREATE TABLE IF NOT EXISTS %s_sync_origin (
                      object_id uuid PRIMARY KEY,
                      dependency text NOT NULL,
                      type text NOT NULL,
                      source_version_id bigint NOT NULL,
                      applied_payload_version text,
                      synced_at timestamptz NOT NULL
                    )""".formatted(Domains.tables(targetDomain)),
                    """
                    CREATE TABLE IF NOT EXISTS %s_sync_shadow (
                      dependency text NOT NULL,
                      object_id uuid NOT NULL,
                      type text NOT NULL,
                      source_version_id bigint NOT NULL,
                      payload bytea NOT NULL,
                      deleted boolean NOT NULL,
                      payload_version text NOT NULL,
                      parked_at timestamptz NOT NULL,
                      shadows_object_id uuid,
                      PRIMARY KEY (dependency, object_id)
                    )""".formatted(Domains.tables(targetDomain)),
                    // idempotent, for shadow tables that predate the column
                    "ALTER TABLE %s_sync_shadow ADD COLUMN IF NOT EXISTS shadows_object_id uuid"
                            .formatted(Domains.tables(targetDomain)),
                    """
                    CREATE TABLE IF NOT EXISTS %s_sync_dlq (
                      dependency text NOT NULL,
                      object_id uuid NOT NULL,
                      type text NOT NULL,
                      source_version_id bigint NOT NULL,
                      reason text NOT NULL,
                      created_at timestamptz NOT NULL,
                      PRIMARY KEY (dependency, object_id, source_version_id)
                    )""".formatted(Domains.tables(targetDomain)))) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("sync schema setup failed", e);
        }
    }
}
