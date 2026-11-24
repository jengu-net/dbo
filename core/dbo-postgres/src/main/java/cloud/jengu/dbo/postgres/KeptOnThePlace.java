package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.Domains;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a place handed its tenant and the tenant declined, as it holds a
 * record of its own in its place: kept on the place for good, as nothing
 * marks it held elsewhere and it is not handed again.
 *
 * <p>Said on the place because the tenant's trail is the only other place it
 * is said, and nobody reading the place reads that. A record the tenant takes
 * later, at that version or a newer one, is no longer counted.
 *
 * @param records how many records
 * @param types   how many of each type
 * @param since   when the first of them was declined, or null when there is none
 */
public record KeptOnThePlace(long records, Map<String, Long> types, Instant since) {

    public KeptOnThePlace {
        types = types == null ? Map.of() : Map.copyOf(types);
    }

    /**
     * Notes what the tenant declined, each as its answer said it: the version
     * and the record it holds instead. A record declined again keeps when it
     * was first declined.
     *
     * @param kept each {@code type/id/version Type/id}
     */
    public static void declined(DataSource dataSource, String domain, List<String> kept) {
        if (kept.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO %s_kept_on_the_place (object_id, type, version_id, holds, declined_at)
                     VALUES (?::uuid, ?, ?, ?, ?)
                     ON CONFLICT (object_id) DO UPDATE
                       SET version_id = EXCLUDED.version_id, holds = EXCLUDED.holds"""
                     .formatted(Domains.tables(domain)))) {
            Timestamp now = Timestamp.from(Instant.now());
            for (String said : kept) {
                String[] parts = said.split(" ");
                String[] version = parts[0].split("/");
                if (parts.length != 2 || version.length != 3) {
                    continue;
                }
                try {
                    ps.setString(1, java.util.UUID.fromString(version[1]).toString());
                    ps.setString(2, version[0]);
                    ps.setLong(3, Long.parseLong(version[2]));
                } catch (IllegalArgumentException notThisSide) {
                    // Not a record this side wrote: nothing here to say it of.
                    continue;
                }
                ps.setString(4, parts[1]);
                ps.setTimestamp(5, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("could not note what the tenant of " + domain
                    + " kept on the place", e);
        }
    }

    /** As it stands now: what is declined and not held elsewhere since. */
    public static KeptOnThePlace in(DataSource dataSource, String domain) {
        Map<String, Long> types = new TreeMap<>();
        long records = 0;
        Instant since = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT k.type, count(*), min(k.declined_at) FROM %1$s_kept_on_the_place k
                     WHERE EXISTS (SELECT 1 FROM %1$s_data d WHERE d.id = k.object_id)
                       AND NOT EXISTS (SELECT 1 FROM %1$s_held_elsewhere m
                                       WHERE m.object_id = k.object_id
                                         AND m.version_id >= k.version_id)
                     GROUP BY k.type""".formatted(Domains.tables(domain)));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                types.put(rs.getString(1), rs.getLong(2));
                records += rs.getLong(2);
                Instant first = rs.getTimestamp(3).toInstant();
                since = since == null || first.isBefore(since) ? first : since;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read what the tenant of " + domain
                    + " kept on the place", e);
        }
        return new KeptOnThePlace(records, types, since);
    }
}
