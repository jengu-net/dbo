package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.runner.transport.Trail;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A place handing its own trail to its tenant: what it recorded, read from its
 * audit feed in the order it was recorded, from where the tenant says it
 * stopped taking.
 *
 * <p><b>Only what was recorded here.</b> An entry that arrived from somewhere
 * else carries the name of where it was recorded, and handing it on would
 * send a trail round in a circle.
 *
 * <p><b>Its trail has an identity of its own</b>, kept in its own database: a
 * place rebuilt on a fresh database has a trail that starts over, and a
 * position the tenant kept in the old one means nothing in it.
 */
final class TrailPusher {

    /** The type a trail is recorded as. */
    private static final String AUDIT_ENTRY = "AuditEntry";
    /** Entries per push: under what the tenant takes at once, in count. */
    private static final int AT_ONCE = 200;
    /** Pushes per round, so a long-away place catches up without holding the round. */
    private static final int MOST_PER_ROUND = 50;

    private final DataSource ds;
    private final Trail trail;
    private final PgChangeFeed audit;
    private volatile String feed;

    TrailPusher(DataSource ds, Trail trail) {
        this.ds = ds;
        this.trail = trail;
        this.audit = new PgChangeFeed(ds, cloud.jengu.dbo.policy.AuditModel.DOMAIN);
    }

    /**
     * Hands what the tenant has not taken yet. Returns how many entries went.
     *
     * @throws cloud.jengu.dbo.core.api.StoreUnreachableException when the
     *         tenant cannot be reached; what was not taken waits here
     */
    int round() {
        String mine = feed();
        Trail.Position held = trail.position();
        String from = held != null && mine.equals(held.feed()) ? held.through() : null;
        int handed = 0;
        for (int push = 0; push < MOST_PER_ROUND; push++) {
            FeedChunk<FeedItem> chunk = audit.read(from, AT_ONCE);
            if (chunk.items().isEmpty()) {
                break;
            }
            List<Trail.Entry> entries = new ArrayList<>();
            for (FeedItem item : chunk.items()) {
                if (recordedHere(item)) {
                    entries.add(new Trail.Entry(item.objectId(), item.versionId(),
                            recordedAt(item), item.payload()));
                }
            }
            // Handed even when nothing in the chunk was recorded here, so the
            // position moves past what this place only passed on.
            trail.handed(mine, entries, chunk.nextCursor());
            handed += entries.size();
            from = chunk.nextCursor();
            if (chunk.drained()) {
                break;
            }
        }
        return handed;
    }

    private static boolean recordedHere(FeedItem item) {
        if (!AUDIT_ENTRY.equals(item.typeName()) || item.deleted()
                || item.payload() == null) {
            return false;
        }
        Object entry = Json.parse(new String(item.payload(), StandardCharsets.UTF_8));
        return entry instanceof java.util.Map<?, ?> map && map.get("appliance") == null;
    }

    /** When it was recorded, as the entry says; when it was committed, where it does not. */
    private static Instant recordedAt(FeedItem item) {
        Object entry = Json.parse(new String(item.payload(), StandardCharsets.UTF_8));
        if (entry instanceof java.util.Map<?, ?> map && map.get("at") != null) {
            try {
                return Instant.parse(String.valueOf(map.get("at")));
            } catch (java.time.format.DateTimeParseException notATime) {
                // the commit time below, which is when it became true here
            }
        }
        return item.committedAt();
    }

    /** This place's trail's own name, made once and kept in its database. */
    private String feed() {
        String known = feed;
        if (known != null) {
            return known;
        }
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS state.trail_source (
                      feed text PRIMARY KEY
                    )""")) {
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO state.trail_source (feed)
                    SELECT gen_random_uuid()::text
                    WHERE NOT EXISTS (SELECT 1 FROM state.trail_source)""")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT feed FROM state.trail_source ORDER BY feed LIMIT 1");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                feed = rs.getString(1);
                return feed;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("this place's trail could not be named", e);
        }
    }
}
