package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Domains;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Where a record a place's run produced came from, kept in the same
 * bookkeeping every streamed copy's source is kept in — so a reader of the
 * record is told which place made it, and a search can ask for one place's.
 */
final class PlaceOrigins {

    private PlaceOrigins() {}

    static void note(DataSource ds, String domain, String id, String place, String type,
            long version) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO %s_sync_origin
                       (object_id, dependency, type, source_version_id, applied_payload_version,
                        synced_at)
                     VALUES (?, ?, ?, ?, NULL, now())
                     ON CONFLICT (object_id) DO UPDATE SET
                       dependency = EXCLUDED.dependency,
                       source_version_id = EXCLUDED.source_version_id,
                       synced_at = now()""".formatted(Domains.tables(domain)))) {
            ps.setObject(1, UUID.fromString(id));
            ps.setString(2, place);
            ps.setString(3, type);
            ps.setLong(4, version);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("where " + type + "/" + id + " came from could not "
                    + "be noted", e);
        }
    }
}
