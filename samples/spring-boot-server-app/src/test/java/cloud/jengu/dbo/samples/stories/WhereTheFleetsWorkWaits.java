package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.work.WorkModel;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Where a deployment's step keeps its work, read beneath every door.
 *
 * <p>A step the deployment performs has a database of its own, which the
 * runtime owns and no tenant does. No door answers what sits in it, because
 * nobody but the joiner and the bean performing the step has any business
 * there. Read here with the connection the deployment was given, and every
 * question names a tenant or a run the story made.
 */
final class WhereTheFleetsWorkWaits {

    private final String admin;
    private final String user;
    private final String password;

    WhereTheFleetsWorkWaits(Environment environment) {
        this.admin = environment.getRequiredProperty("dbo.admin.jdbc-url");
        this.user = environment.getRequiredProperty("dbo.admin.user");
        this.password = environment.getRequiredProperty("dbo.admin.password");
    }

    /** Whether a database of this name exists on the deployment's server. */
    boolean exists(String database) {
        try (Connection c = connect(admin);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not list the databases", unreadable);
        }
    }

    /** The schemas holding tables in that database. */
    Set<String> schemas(String database) {
        Set<String> schemas = new LinkedHashSet<>();
        try (Connection c = connect(on(database));
             PreparedStatement ps = c.prepareStatement("""
                     SELECT DISTINCT table_schema FROM information_schema.tables
                     WHERE table_schema NOT IN ('pg_catalog', 'information_schema')""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read " + database, unreadable);
        }
        return schemas;
    }

    /** The queue an item sits in, by the item's own id, if it was ever offered. */
    Optional<String> queueOf(String database, String item) {
        try (Connection c = connect(on(database));
             PreparedStatement ps = c.prepareStatement(
                     "SELECT queue_name FROM dbos.workflow_status WHERE workflow_uuid = ?")) {
            ps.setString(1, item);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read the queue in " + database,
                    unreadable);
        }
    }

    /** Every item offered from one tenant, by id: {@code fleet:<tenant>:<run>}. */
    Set<String> offeredFrom(String database, String tenant) {
        Set<String> items = new LinkedHashSet<>();
        try (Connection c = connect(on(database));
             PreparedStatement ps = c.prepareStatement(
                     "SELECT workflow_uuid FROM dbos.workflow_status WHERE workflow_uuid LIKE ?")) {
            ps.setString(1, "fleet:" + tenant + ":%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(rs.getString(1));
                }
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read the queue in " + database,
                    unreadable);
        }
        return items;
    }

    /**
     * Puts the joiner's place in a tenant's work back to the beginning, as a
     * restart that lost its last acknowledgement would find it.
     */
    void joinerForgets(String tenant, String joiner) {
        try (Connection c = connect(on(tenantDatabase(tenant)));
             PreparedStatement ps = c.prepareStatement(("UPDATE %s_consumer SET seq = 0, "
                     + "cursor_xid = '0'::text::xid8 WHERE name = ?")
                     .formatted(Domains.tables(WorkModel.DOMAIN)))) {
            ps.setString(1, joiner);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("the joiner has no place in " + tenant
                        + "'s work to forget, so it never read any");
            }
        } catch (SQLException unwritable) {
            throw new IllegalStateException("could not move the joiner in " + tenant,
                    unwritable);
        }
    }

    /** Whether the joiner has read anything of a tenant's work since it last forgot. */
    boolean joinerHasRead(String tenant, String joiner) {
        try (Connection c = connect(on(tenantDatabase(tenant)));
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq FROM %s_consumer WHERE name = ?"
                             .formatted(Domains.tables(WorkModel.DOMAIN)))) {
            ps.setString(1, joiner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read the joiner's place in " + tenant,
                    unreadable);
        }
    }

    private static String tenantDatabase(String tenant) {
        return "tenant_" + tenant.replace('-', '_');
    }

    private String on(String database) {
        int slash = admin.lastIndexOf('/');
        int params = admin.indexOf('?', slash);
        return admin.substring(0, slash + 1) + database
                + (params < 0 ? "" : admin.substring(params));
    }

    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
