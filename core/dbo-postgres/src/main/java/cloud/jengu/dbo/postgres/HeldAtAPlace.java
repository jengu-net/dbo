package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.Domains;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What of a domain a place holds, in the two parts that fill its disk: what
 * it holds alone, which retention waits on its tenant to hold, and what it
 * holds that is held elsewhere and not yet let go, which the coming sweeps
 * remove. Each by type and in total, as versions and the bytes their payloads
 * take as stored.
 *
 * <p><b>One pass over the history</b>, so a place with months of work reads
 * it every few minutes. A payload's stored size is read from its header and
 * never decompressed, which is also the size the disk holds.
 *
 * @param alone      by type, the versions of the bounded types no mark covers
 *                   and that came from nowhere else, as {@link HeldHereAlone} counts
 *                   them; {@code goesFrom} is null, as nothing says when they go
 * @param elsewhere  by type, the versions a mark covers that are held here still
 * @param unbounded  how many versions this node holds that no declaration bounds
 */
public record HeldAtAPlace(Map<String, Part> alone, Map<String, Part> elsewhere,
        long unbounded) {

    /**
     * One part of what is held.
     *
     * @param versions how many versions
     * @param bytes    what their payloads take as stored
     * @param oldest   when the oldest of them was written, or null when there is none
     * @param goesFrom of what is held elsewhere, the soonest retention may let any
     *                 of it go: the earliest mark on a record's latest version, past
     *                 its type's {@code removeAfterHeldElsewhere}. Null where the type
     *                 sets none or no record's latest version is marked. No sooner
     *                 than this, as open work and a sweep's own hour keep it longer.
     */
    public record Part(long versions, long bytes, Instant oldest, Instant goesFrom) {

        static final Part NONE = new Part(0, 0, null, null);

        Part plus(Part other) {
            return new Part(versions + other.versions, bytes + other.bytes,
                    earliest(oldest, other.oldest), earliest(goesFrom, other.goesFrom));
        }
    }

    public HeldAtAPlace {
        alone = Map.copyOf(alone);
        elsewhere = Map.copyOf(elsewhere);
    }

    /** What the place holds alone, all types together. */
    public Part aloneInTotal() {
        return alone.values().stream().reduce(Part.NONE, Part::plus);
    }

    /** What it holds that is held elsewhere, all types together. */
    public Part elsewhereInTotal() {
        return elsewhere.values().stream().reduce(Part.NONE, Part::plus);
    }

    /**
     * As it stands now.
     *
     * @param bounded      the types of the domain a declaration bounds, or null for all
     * @param alongside    further domains held here, the store's own, counted as unbounded
     * @param heldElsewhere by type, the shortest {@code removeAfterHeldElsewhere} a
     *                     record of it may be held to
     */
    public static HeldAtAPlace in(DataSource dataSource, String domain, Set<String> bounded,
            List<String> alongside, Map<String, Duration> heldElsewhere) {
        Map<String, Part> alone = new TreeMap<>();
        Map<String, Part> elsewhere = new TreeMap<>();
        long unbounded = 0;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT h.type,
                           EXISTS (SELECT 1 FROM %2$s_held_elsewhere m
                                   WHERE m.object_id = h.id AND m.version_id >= h.version_id)
                             AS marked,
                           EXISTS (SELECT 1 FROM %2$s_sync_origin o WHERE o.object_id = h.id)
                             AS copied,
                           count(*), coalesce(sum(pg_column_size(h.payload)), 0),
                           min(h.last_updated)
                    FROM %1$s_history h
                    GROUP BY 1, 2, 3""".formatted(Domains.historyTables(domain),
                    Domains.tables(domain)));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString(1);
                    boolean marked = rs.getBoolean(2);
                    boolean copied = rs.getBoolean(3);
                    Timestamp oldest = rs.getTimestamp(6);
                    Part part = new Part(rs.getLong(4), rs.getLong(5),
                            oldest == null ? null : oldest.toInstant(), null);
                    boolean isBounded = bounded == null || bounded.contains(type);
                    if (!isBounded) {
                        unbounded += part.versions();
                    }
                    if (copied) {
                        continue;
                    }
                    if (marked) {
                        elsewhere.merge(type, part, Part::plus);
                    } else if (isBounded) {
                        alone.merge(type, part, Part::plus);
                    }
                }
            }
            // What a sweep measures its bound from: the mark on a record's
            // latest version, as an earlier version marked goes only with it.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.type, min(m.marked_at) FROM %s_data d
                    JOIN %s_held_elsewhere m
                      ON m.object_id = d.id AND m.version_id = d.version_id
                    GROUP BY d.type""".formatted(Domains.tables(domain),
                    Domains.tables(domain)));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Duration bound = heldElsewhere.get(rs.getString(1));
                    Part part = elsewhere.get(rs.getString(1));
                    if (bound != null && part != null) {
                        elsewhere.put(rs.getString(1), new Part(part.versions(), part.bytes(),
                                part.oldest(), rs.getTimestamp(2).toInstant().plus(bound)));
                    }
                }
            }
            for (String own : alongside) {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM %s_history"
                        .formatted(Domains.historyTables(own)));
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    unbounded += rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read what " + domain
                    + " holds at this place", e);
        }
        return new HeldAtAPlace(alone, elsewhere, unbounded);
    }

    private static Instant earliest(Instant one, Instant other) {
        return one == null ? other : other == null ? one : one.isBefore(other) ? one : other;
    }
}
