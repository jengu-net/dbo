package cloud.jengu.dbo.maintenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Which configuration commit this tenant's projection came from.
 *
 * <p>A restore puts a tenant's data back. Whether the restore is
 * <b>reproducible</b> or merely approximate depends on knowing which
 * configuration that data was projected under — otherwise an operator
 * reassembles the installation against whatever the configuration repository
 * happens to say today, and the difference is invisible until something
 * behaves unexpectedly weeks later.
 *
 * <p>Recorded <b>when the projection is applied</b>, not read at backup time.
 * Those differ whenever the sync is behind, and taking the repository's
 * current head at backup time would stamp an archive with a commit its data
 * never saw — which is worse than no stamp, because it looks like an answer.
 *
 * <p>Tenant-scoped rather than domain-scoped: a tenant has one configuration
 * however many domains it serves.
 */
public final class ProjectionMarker {

    /** The marker the archive manifest carries. */
    public static final String CONFIG_COMMIT = "config-commit";

    private ProjectionMarker() {
    }

    /** The table is created on first use — a tenant with no projection has none. */
    static void ensureTable(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                CREATE TABLE IF NOT EXISTS state.projection_marker (
                  name text PRIMARY KEY,
                  value text NOT NULL,
                  recorded_at timestamptz NOT NULL DEFAULT now()
                )""")) {
            ps.execute();
        }
    }

    /**
     * Records the commit a projection was applied from.
     *
     * <p>Last write wins, and that is right: the marker answers "what is this
     * tenant projected from now", not "what has it ever been projected from".
     * The history of that question is the archive's, one file per backup.
     */
    public static void record(DataSource ds, String name, String value) {
        try (Connection c = ds.getConnection()) {
            ensureTable(c);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO state.projection_marker (name, value, recorded_at)
                    VALUES (?, ?, now())
                    ON CONFLICT (name) DO UPDATE
                      SET value = EXCLUDED.value, recorded_at = EXCLUDED.recorded_at""")) {
                ps.setString(1, name);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("recording " + name + " failed", e);
        }
    }

    /** The recorded value, or null when nothing has been recorded. */
    public static String read(Connection c, String name) throws SQLException {
        if (!tableExists(c)) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM state.projection_marker WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean tableExists(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = 'state' AND table_name = 'projection_marker'""");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }
}
