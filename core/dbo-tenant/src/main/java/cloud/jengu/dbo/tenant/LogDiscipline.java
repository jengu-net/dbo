package cloud.jengu.dbo.tenant;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The database keeps no plaintext log
 * (REQ-DBO-PDI-PLAINTEXT-IN-FLIGHT-LEAVES-NO-TRACE).
 *
 * <p>A tenant's database is part of this store's runtime, and personal data
 * passes through it in the clear the way it passes through the JVM's heap:
 * in flight, to be validated, extracted or converted, and never at rest
 * anywhere the person's key does not cover. What would break that is not the
 * database seeing a value but the database WRITING one down — and a Postgres
 * server logs the parameters of a slow or a logged statement in full unless
 * told otherwise. A slow validation of a patient would then be a patient in
 * the server log, which is not per tenant and, on a managed server, not
 * even the store's.
 *
 * <p>This is the JVM's own logging rule carried across: identifying data
 * never reaches a log, and over there the MDC is a no-op for exactly that
 * reason. Here it is one setting, pinned where the store can pin it and
 * CHECKED wherever it cannot, because a managed server may let this role
 * read the setting and not change it — and a discipline that is assumed is
 * the kind that turns out to have been off since the upgrade.
 */
final class LogDiscipline {

    private LogDiscipline() {}

    /** What the pin sets, on every database this store provisions itself. */
    static List<String> pins(String dbName) {
        return List.of(
                "ALTER DATABASE " + dbName + " SET log_parameter_max_length = 0",
                "ALTER DATABASE " + dbName + " SET log_parameter_max_length_on_error = 0");
    }

    /**
     * Whether this database, as its sessions actually see it, would write a
     * statement's parameters to the server log.
     *
     * @return why it would, naming the setting, or empty when it would not
     */
    static Optional<String> leak(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String onError = shown(c, "log_parameter_max_length_on_error");
            if (onError != null && !"0".equals(onError)) {
                return Optional.of("log_parameter_max_length_on_error is " + onError
                        + ", so a statement that fails is logged with its parameters");
            }
            String length = shown(c, "log_parameter_max_length");
            if (length == null || "0".equals(length)) {
                return Optional.empty();
            }
            // Parameters are logged only with a statement, and a statement is
            // logged only when something asks for it.
            String statements = shown(c, "log_statement");
            String slow = shown(c, "log_min_duration_statement");
            boolean logsStatements = statements != null && !"none".equals(statements);
            boolean logsSlowOnes = slow != null && !"-1".equals(slow);
            if (logsStatements) {
                return Optional.of("log_statement is " + statements
                        + " and log_parameter_max_length is " + length
                        + ", so statements are logged with their parameters");
            }
            if (logsSlowOnes) {
                return Optional.of("log_min_duration_statement is " + slow
                        + " and log_parameter_max_length is " + length
                        + ", so a slow statement is logged with its parameters");
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("the database's logging settings could not be read", e);
        }
    }

    /** A setting as this session sees it, or null on a server that lacks it. */
    private static String shown(Connection c, String setting) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT setting FROM pg_settings WHERE name = ?")) {
            ps.setString(1, setting);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
