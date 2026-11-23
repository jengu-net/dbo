package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.Domains;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * What of a domain this node holds and nobody else is known to: the versions
 * no mark covers and that came from nowhere else.
 *
 * <p>The number a place's retention waits on. A place cut off from its tenant
 * keeps all of it however short the declared bound, so the count and the age
 * of the oldest say how long the place has been holding work alone, and how
 * much of its disk that is.
 *
 * @param versions how many versions
 * @param oldest   when the oldest of them was written, or null when there is none
 */
public record HeldHereAlone(long versions, Instant oldest) {

    /** As it stands in the domain now. */
    public static HeldHereAlone in(DataSource dataSource, String domain) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*), min(h.last_updated) FROM %s_history h
                     WHERE NOT EXISTS (SELECT 1 FROM %s_held_elsewhere m
                                       WHERE m.object_id = h.id AND m.version_id >= h.version_id)
                       AND NOT EXISTS (SELECT 1 FROM %s_sync_origin o WHERE o.object_id = h.id)"""
                     .formatted(Domains.historyTables(domain), Domains.tables(domain),
                             Domains.tables(domain)));
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            Timestamp oldest = rs.getTimestamp(2);
            return new HeldHereAlone(rs.getLong(1), oldest == null ? null : oldest.toInstant());
        } catch (SQLException e) {
            throw new IllegalStateException("could not read what " + domain
                    + " holds that nothing else is known to", e);
        }
    }
}
