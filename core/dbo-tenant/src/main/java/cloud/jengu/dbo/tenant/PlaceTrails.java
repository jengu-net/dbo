package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.AuditReplay;
import cloud.jengu.dbo.runner.transport.Trail;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The trails a tenant takes from its places: each entry recorded as the
 * place's, through the one port that may write an entry somebody else
 * recorded, and where each place's trail has been taken up to.
 *
 * <p><b>The position is kept here, per place</b>, so a place that was away
 * or was replaced hands on from where the tenant stopped taking, and keeps
 * nothing of its own about it. It moves only after the batch it names is
 * recorded: a place that hands a batch twice finds the first copy in place,
 * and one whose answer was lost hands it again.
 */
final class PlaceTrails {

    private PlaceTrails() {
    }

    /** One place's trail, as this tenant takes it. */
    static Trail of(DataSource ds, AuditReplay replay, String place) {
        ensureTable(ds);
        return new Trail() {
            @Override
            public Position position() {
                return heldFor(ds, place);
            }

            @Override
            public Position handed(String feed, List<Entry> entries, String through) {
                for (Entry entry : entries) {
                    replay.replayAuditEntry(place, entry.id(), entry.version(), entry.payload(),
                            entry.recordedAt());
                }
                hold(ds, place, feed, through);
                return new Position(feed, through);
            }
        };
    }

    /** The tenants whose table is known to be there, held weakly so a closed one goes. */
    private static final java.util.Set<DataSource> ENSURED = java.util.Collections
            .synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private static void ensureTable(DataSource ds) {
        if (ENSURED.contains(ds)) {
            return;
        }
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("""
                        CREATE TABLE IF NOT EXISTS state.place_trail (
                          place      text        PRIMARY KEY,
                          feed       text        NOT NULL,
                          through    text,
                          updated_at timestamptz NOT NULL DEFAULT now()
                        )""")) {
            ps.execute();
            ENSURED.add(ds);
        } catch (SQLException e) {
            throw new IllegalStateException("where a place's trail was taken up to cannot be "
                    + "kept", e);
        }
    }

    private static Trail.Position heldFor(DataSource ds, String place) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT feed, through FROM state.place_trail WHERE place = ?")) {
            ps.setString(1, place);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Trail.Position(rs.getString(1), rs.getString(2)) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("where " + place + "'s trail was taken up to could "
                    + "not be read", e);
        }
    }

    private static void hold(DataSource ds, String place, String feed, String through) {
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO state.place_trail (place, feed, through, updated_at)
                        VALUES (?, ?, ?, now())
                        ON CONFLICT (place) DO UPDATE
                        SET feed = EXCLUDED.feed, through = EXCLUDED.through,
                            updated_at = now()""")) {
            ps.setString(1, place);
            ps.setString(2, feed);
            ps.setString(3, through);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("where " + place + "'s trail was taken up to could "
                    + "not be kept", e);
        }
    }
}
