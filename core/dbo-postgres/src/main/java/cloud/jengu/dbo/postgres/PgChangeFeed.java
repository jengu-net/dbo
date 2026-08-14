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
     * The delivery barrier (dbo#5 + dbo#18 R1). Conservative path: the row's
     * transaction lies below the snapshot's xmin — decided cluster-wide. Fast
     * path: when THIS database has no write-transaction in flight, every
     * visible committed row is decidable regardless of foreign databases —
     * a long transaction elsewhere in the instance (the dbo#16 finding) no
     * longer delays a quiet tenant's feed.
     */
    private static final String BARRIER = """
            (o.xact_id < pg_snapshot_xmin(pg_current_snapshot())
             OR NOT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND backend_xid IS NOT NULL))""";

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
        long after = cursor == null ? 0 : Cursors.decodeSeq(cursor);
        return readAfter(after, limit);
    }

    @Override
    public FeedChunk<FeedItem> readFor(String consumer, int limit) {
        requireConsumer(consumer);
        return readAfter(consumerSeq(consumer), limit);
    }

    @Override
    public void ack(String consumer, String cursor) {
        requireConsumer(consumer);
        long seq = Cursors.decodeSeq(cursor);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_consumer (name, seq, updated_at) VALUES (?, ?, now())
                     ON CONFLICT (name) DO UPDATE SET seq = EXCLUDED.seq, updated_at = now()
                     WHERE %s_consumer.seq < EXCLUDED.seq""".formatted(domain, domain))) {
            ps.setString(1, consumer);
            ps.setLong(2, seq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("ack failed", e);
        }
    }

    @Override
    public void resetConsumer(String consumer, String cursor) {
        requireConsumer(consumer);
        long seq = cursor == null ? 0 : Cursors.decodeSeq(cursor);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_consumer (name, seq, updated_at) VALUES (?, ?, now())
                     ON CONFLICT (name) DO UPDATE SET seq = EXCLUDED.seq, updated_at = now()"""
                     .formatted(domain))) {
            ps.setString(1, consumer);
            ps.setLong(2, seq);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("reset failed", e);
        }
    }

    @Override
    public String cursorOf(String consumer) {
        requireConsumer(consumer);
        long seq = consumerSeq(consumer);
        return seq == 0 ? null : Cursors.encodeSeq(seq);
    }

    @Override
    public long lag(String consumer) {
        requireConsumer(consumer);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM state.%s_outbox o
                     WHERE o.seq > ? AND %s""".formatted(domain, BARRIER))) {
            ps.setLong(1, consumerSeq(consumer));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("lag query failed", e);
        }
    }

    private FeedChunk<FeedItem> readAfter(long after, int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit out of range: " + limit);
        }
        String sql = """
                SELECT o.seq, o.object_id, o.type, o.version_id, o.kind, o.committed_at,
                       h.payload, h.deleted, h.payload_version
                FROM state.%s_outbox o
                JOIN history.%s_history h ON h.id = o.object_id AND h.version_id = o.version_id
                WHERE o.seq > ? AND %s
                ORDER BY o.seq LIMIT ?""".formatted(domain, domain, BARRIER);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, after);
            ps.setInt(2, limit);
            List<FeedItem> items = new ArrayList<>();
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
                }
            }
            String next = items.isEmpty() ? null : Cursors.encodeSeq(items.get(items.size() - 1).seq());
            return new FeedChunk<>(items, next, items.size() < limit);
        } catch (SQLException e) {
            throw new IllegalStateException("feed read failed", e);
        }
    }

    private long consumerSeq(String consumer) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq FROM state.%s_consumer WHERE name = ?".formatted(domain))) {
            ps.setString(1, consumer);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
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
