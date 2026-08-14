package cloud.jengu.dbo.postgres;

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
import java.util.List;
import java.util.regex.Pattern;

/**
 * The outbox change feed of one domain. Gap-free by construction: only rows
 * whose {@code xact_id} lies below the current snapshot's xmin are delivered
 * — every transaction below xmin has finished, so no later commit can insert
 * behind the cursor (see SchemaManager outbox DDL note).
 */
public final class PgChangeFeed implements ChangeFeed {

    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern CONSUMER = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    /**
     * The delivery barrier (dbo#5 + dbo#18 R1, corrected in dbo#19).
     * Delivered rows must satisfy {@code xact_id < H} where H is the local
     * horizon taken BEFORE the read snapshot:
     *
     * <p>If this database had no write-transaction in flight at horizon
     * time, H = the horizon snapshot's xmax — every same-database
     * transaction below it has already finished, so its rows are visible to
     * the (later) read snapshot and no commit can land behind the cursor. A
     * long transaction in a FOREIGN database (the dbo#16 finding) no longer
     * delays the feed. If a local write IS in flight, H = null and the read
     * falls back to the conservative cluster-wide xmin barrier.
     *
     * <p>The order is load-bearing: evaluating liveness inside the read
     * statement (the first R1 attempt) races — a writer that commits
     * between the statement's snapshot and its pg_stat_activity scan is
     * visible in neither, and its event is skipped forever.
     */
    private static final String LOCAL_HORIZON = """
            SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_stat_activity
                                     WHERE datname = current_database()
                                       AND backend_xid IS NOT NULL)
                        THEN NULL
                        ELSE pg_snapshot_xmax(pg_current_snapshot())::text END""";

    private static final String BARRIER =
            "o.xact_id < COALESCE(?::xid8, pg_snapshot_xmin(pg_current_snapshot()))";

    private final DataSource ds;
    private final String domain;

    public PgChangeFeed(DataSource dataSource, String domain) {
        if (!DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("invalid domain: " + domain);
        }
        this.ds = dataSource;
        this.domain = domain;
    }

    @Override
    public FeedChunk<FeedItem> read(String cursor, int limit) {
        Cursors.FeedCursor after = cursor == null
                ? new Cursors.FeedCursor(0, 0) : Cursors.decodeFeed(cursor);
        return readAfter(after, limit);
    }

    @Override
    public FeedChunk<FeedItem> readFor(String consumer, int limit) {
        requireConsumer(consumer);
        return readAfter(consumerCursor(consumer), limit);
    }

    @Override
    public void ack(String consumer, String cursor) {
        requireConsumer(consumer);
        Cursors.FeedCursor at = Cursors.decodeFeed(cursor);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_consumer (name, seq, cursor_xid, updated_at)
                     VALUES (?, ?, ?::text::xid8, now())
                     ON CONFLICT (name) DO UPDATE
                     SET seq = EXCLUDED.seq, cursor_xid = EXCLUDED.cursor_xid, updated_at = now()
                     WHERE (%s_consumer.cursor_xid, %s_consumer.seq)
                         < (EXCLUDED.cursor_xid, EXCLUDED.seq)"""
                     .formatted(domain, domain, domain))) {
            ps.setString(1, consumer);
            ps.setLong(2, at.seq());
            ps.setString(3, Long.toString(at.xid()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("ack failed", e);
        }
    }

    @Override
    public void resetConsumer(String consumer, String cursor) {
        requireConsumer(consumer);
        Cursors.FeedCursor at = cursor == null
                ? new Cursors.FeedCursor(0, 0) : Cursors.decodeFeed(cursor);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_consumer (name, seq, cursor_xid, updated_at)
                     VALUES (?, ?, ?::text::xid8, now())
                     ON CONFLICT (name) DO UPDATE
                     SET seq = EXCLUDED.seq, cursor_xid = EXCLUDED.cursor_xid, updated_at = now()"""
                     .formatted(domain))) {
            ps.setString(1, consumer);
            ps.setLong(2, at.seq());
            ps.setString(3, Long.toString(at.xid()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("reset failed", e);
        }
    }

    @Override
    public String cursorOf(String consumer) {
        requireConsumer(consumer);
        Cursors.FeedCursor at = consumerCursor(consumer);
        return at.xid() == 0 && at.seq() == 0 ? null : Cursors.encodeFeed(at.xid(), at.seq());
    }

    @Override
    public long lag(String consumer) {
        requireConsumer(consumer);
        Cursors.FeedCursor at = consumerCursor(consumer);
        try (Connection c = ds.getConnection()) {
            String horizon = localHorizon(c);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT count(*) FROM state.%s_outbox o
                    WHERE (o.xact_id, o.seq) > (?::text::xid8, ?) AND %s"""
                    .formatted(domain, BARRIER))) {
                ps.setString(1, Long.toString(at.xid()));
                ps.setLong(2, at.seq());
                ps.setString(3, horizon);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("lag query failed", e);
        }
    }

    /**
     * dbo#25 fence: xid-major order. seq alone cannot fence — the outbox seq
     * is drawn BEFORE the transaction's xid is assigned, so xid order and
     * seq order interleave across backends, and a seq-ordered cursor could
     * pass a not-yet-visible lower seq (permanent loss, caught by FeedIT in
     * CI). Ordered by (xact_id, seq), every future commit carries an xid at
     * or above the barrier and lands AFTER the cursor by construction.
     */
    private FeedChunk<FeedItem> readAfter(Cursors.FeedCursor after, int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit out of range: " + limit);
        }
        String sql = """
                SELECT o.seq, o.object_id, o.type, o.version_id, o.kind, o.committed_at,
                       h.payload, h.deleted, h.payload_version, o.xact_id::text
                FROM state.%s_outbox o
                JOIN history.%s_history h ON h.id = o.object_id AND h.version_id = o.version_id
                WHERE (o.xact_id, o.seq) > (?::text::xid8, ?) AND %s
                ORDER BY o.xact_id, o.seq LIMIT ?""".formatted(domain, domain, BARRIER);
        try (Connection c = ds.getConnection()) {
            String horizon = localHorizon(c); // BEFORE the read snapshot
            try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Long.toString(after.xid()));
            ps.setLong(2, after.seq());
            ps.setString(3, horizon);
            ps.setInt(4, limit);
            List<FeedItem> items = new ArrayList<>();
            long lastXid = after.xid();
            long lastSeq = after.seq();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new FeedItem(
                            rs.getLong(1),
                            rs.getObject(2).toString(),
                            rs.getString(3),
                            rs.getLong(4),
                            ChangeKind.fromCode(rs.getString(5)),
                            rs.getTimestamp(6).toInstant(),
                            rs.getBytes(7),
                            rs.getBoolean(8),
                            rs.getString(9)));
                    lastXid = Long.parseLong(rs.getString(10));
                    lastSeq = rs.getLong(1);
                }
            }
            String next = items.isEmpty() ? null : Cursors.encodeFeed(lastXid, lastSeq);
            return new FeedChunk<>(items, next, items.size() < limit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("feed read failed", e);
        }
    }

    /** The dbo#19 horizon: xmax when this database is write-quiet, else null. */
    private String localHorizon(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(LOCAL_HORIZON);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private Cursors.FeedCursor consumerCursor(String consumer) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT cursor_xid::text, seq FROM state.%s_consumer WHERE name = ?"
                             .formatted(domain))) {
            ps.setString(1, consumer);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Cursors.FeedCursor(Long.parseLong(rs.getString(1)), rs.getLong(2))
                        : new Cursors.FeedCursor(0, 0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("consumer lookup failed", e);
        }
    }

    private static void requireConsumer(String consumer) {
        if (consumer == null || !CONSUMER.matcher(consumer).matches()) {
            throw new IllegalArgumentException("invalid consumer name: " + consumer);
        }
    }
}
