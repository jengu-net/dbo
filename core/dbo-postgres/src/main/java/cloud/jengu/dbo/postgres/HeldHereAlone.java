package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.Domains;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What of a domain this node holds and nobody else is known to: the versions
 * no mark covers and that came from nowhere else.
 *
 * <p>The number a place's retention waits on, so it counts the types a
 * declaration bounds and nothing else. A place cut off from its tenant keeps
 * all of them however short the bound, so the count and the age of the oldest
 * say how long the place has been holding work alone, and how much of its
 * disk that is. What no declaration bounds — the store's own housekeeping,
 * and types declared to be kept — is said apart, as retention never lets go
 * of it and its age says nothing about a cut-off.
 *
 * @param versions  how many versions of the bounded types
 * @param oldest    when the oldest of them was written, or null when there is none
 * @param unbounded how many versions this node holds that no declaration bounds
 */
public record HeldHereAlone(long versions, Instant oldest, long unbounded) {

    /** Compatibility with callers that predate {@code unbounded}. */
    public HeldHereAlone(long versions, Instant oldest) {
        this(versions, oldest, 0);
    }

    /** As it stands in the domain now, counting every type as bounded. */
    public static HeldHereAlone in(DataSource dataSource, String domain) {
        return in(dataSource, domain, null, List.of());
    }

    /**
     * As it stands now.
     *
     * @param bounded    the types of the domain a declaration bounds, or null for all of them
     * @param alongside  further domains held here, the store's own, counted as unbounded
     */
    public static HeldHereAlone in(DataSource dataSource, String domain, Set<String> bounded,
            List<String> alongside) {
        try (Connection c = dataSource.getConnection()) {
            long versions;
            Timestamp oldest;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT count(*), min(h.last_updated) FROM %s_history h
                    WHERE (?::text[] IS NULL OR h.type = ANY (?::text[]))
                      AND NOT EXISTS (SELECT 1 FROM %s_held_elsewhere m
                                      WHERE m.object_id = h.id AND m.version_id >= h.version_id)
                      AND NOT EXISTS (SELECT 1 FROM %s_sync_origin o WHERE o.object_id = h.id)"""
                    .formatted(Domains.historyTables(domain), Domains.tables(domain),
                            Domains.tables(domain)))) {
                java.sql.Array types = bounded == null ? null
                        : c.createArrayOf("text", bounded.toArray());
                ps.setArray(1, types);
                ps.setArray(2, types);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    versions = rs.getLong(1);
                    oldest = rs.getTimestamp(2);
                }
            }
            long unbounded = 0;
            if (bounded != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM %s_history WHERE NOT (type = ANY (?::text[]))"
                                .formatted(Domains.historyTables(domain)))) {
                    ps.setArray(1, c.createArrayOf("text", bounded.toArray()));
                    unbounded += counted(ps);
                }
            }
            for (String own : alongside) {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM %s_history"
                        .formatted(Domains.historyTables(own)))) {
                    unbounded += counted(ps);
                }
            }
            return new HeldHereAlone(versions, oldest == null ? null : oldest.toInstant(),
                    unbounded);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read what " + domain
                    + " holds that nothing else is known to", e);
        }
    }

    /**
     * The records of the domain whose latest version no mark covers and that
     * came from nowhere else, after the one given in id order: what a place
     * asks its tenant about, a page at a time.
     *
     * @param after the id of the last record asked of, or null from the first
     * @return each {@code type/id/version}
     */
    public static List<String> unmarkedAfter(DataSource dataSource, String domain, String after,
            int limit) {
        List<String> unmarked = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT d.type, d.id::text, d.version_id FROM %1$s_data d
                     WHERE (?::uuid IS NULL OR d.id > ?::uuid)
                       AND NOT EXISTS (SELECT 1 FROM %1$s_held_elsewhere m
                                       WHERE m.object_id = d.id AND m.version_id >= d.version_id)
                       AND NOT EXISTS (SELECT 1 FROM %1$s_sync_origin o WHERE o.object_id = d.id)
                     ORDER BY d.id
                     LIMIT ?""".formatted(Domains.tables(domain)))) {
            ps.setString(1, after);
            ps.setString(2, after);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    unmarked.add(rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getLong(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read what " + domain
                    + " holds unmarked", e);
        }
        return unmarked;
    }

    private static long counted(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
