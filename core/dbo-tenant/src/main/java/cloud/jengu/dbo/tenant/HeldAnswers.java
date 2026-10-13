package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.work.WorkModel;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * What came back on a crossing, held by the tenant that asked for exactly as
 * long as the work it was asked for needs it.
 *
 * <p><b>Beside the run, never in it.</b> A run is versioned and its history
 * is kept, so an answer written into the run would outlive its purpose in
 * every earlier version of it. Held here instead, where nothing is versioned,
 * removing it removes the only copy: the run says an answer was held, and
 * once the purpose is over there is nothing left to read.
 */
final class HeldAnswers {

    private HeldAnswers() {}

    private static String table() {
        return Domains.tables(WorkModel.DOMAIN) + "_held_answer";
    }

    private static void ensure(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                CREATE TABLE IF NOT EXISTS %s (
                  crossing text PRIMARY KEY,
                  answer text NOT NULL,
                  held_at timestamptz NOT NULL
                )""".formatted(table()))) {
            ps.execute();
        }
    }

    static void hold(DataSource ds, String crossing, String answer) {
        try (Connection c = ds.getConnection()) {
            ensure(c);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO %s (crossing, answer, held_at) VALUES (?, ?, now())
                    ON CONFLICT (crossing) DO NOTHING""".formatted(table()))) {
                ps.setString(1, crossing);
                ps.setString(2, answer);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the answer to crossing " + crossing
                    + " could not be held", e);
        }
    }

    static Optional<String> held(DataSource ds, String crossing) {
        try (Connection c = ds.getConnection()) {
            ensure(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT answer FROM %s WHERE crossing = ?".formatted(table()))) {
                ps.setString(1, crossing);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the answer to crossing " + crossing
                    + " could not be read", e);
        }
    }

    /** Removes the answer; whether there was one to remove. */
    static boolean drop(DataSource ds, String crossing) {
        try (Connection c = ds.getConnection()) {
            ensure(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM %s WHERE crossing = ?".formatted(table()))) {
                ps.setString(1, crossing);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the answer to crossing " + crossing
                    + " could not be dropped", e);
        }
    }

    /**
     * Whether an answer held for work that ended at {@code ended} is due to
     * go: at once, unless the jurisdiction declared it may be kept, and then
     * once that long has passed.
     */
    static boolean due(java.time.Instant ended, java.time.Duration kept,
            java.time.Instant now) {
        return kept == null || !now.isBefore(ended.plus(kept));
    }
}
