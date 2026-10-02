package cloud.jengu.dbo.samples.stories;

import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A tenant's database, read the way somebody holding the server and no key
 * would read it.
 *
 * <p>Some promises are about what is NOT written down: a pseudonym kept
 * nowhere, content at rest that is not the bytes sent, a server told not to
 * log what passes through it. Those are answered beneath the store's doors,
 * with the connection the deployment itself was given, and every question is
 * asked about a value or a key the story made.
 *
 * <p>And some are about how the store keeps what it was told: a grant withdrawn
 * rather than deleted, a parameter compiled into a row, an index built, a
 * database provisioned with its timeouts. Those are read here too, the way an
 * operator would read them, so every story asks its database one way.
 */
final class WhatTheDatabaseHolds {

    private final String url;
    private final String user;
    private final String password;

    WhatTheDatabaseHolds(Environment environment, String tenant) {
        this(tenantUrl(environment.getRequiredProperty("dbo.admin.jdbc-url"), tenant),
                environment);
    }

    private WhatTheDatabaseHolds(String url, Environment environment) {
        this.url = url;
        this.user = environment.getRequiredProperty("dbo.admin.user");
        this.password = environment.getRequiredProperty("dbo.admin.password");
    }

    /**
     * The server the tenants' databases are on, for what is asked of it rather
     * than of one of them: which databases exist, and how each is set.
     */
    static WhatTheDatabaseHolds theServer(Environment environment) {
        return new WhatTheDatabaseHolds(environment.getRequiredProperty("dbo.admin.jdbc-url"),
                environment);
    }

    private static String tenantUrl(String admin, String tenant) {
        int slash = admin.lastIndexOf('/');
        int params = admin.indexOf('?', slash);
        return admin.substring(0, slash + 1) + "tenant_" + tenant.replace('-', '_')
                + (params < 0 ? "" : admin.substring(params));
    }

    /** How one row is read, for a question whose answer is more than one column. */
    @FunctionalInterface
    interface Row<T> {
        T read(ResultSet row) throws SQLException;
    }

    /**
     * Every row a question answers, read the way it says.
     *
     * @param parameters bound in order; a {@code String[]} is bound as a text array
     */
    <T> List<T> each(String sql, Row<T> row, Object... parameters) {
        List<T> found = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, parameters);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(row.read(rs));
                }
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not ask " + url + ": " + sql, unreadable);
        }
        return found;
    }

    /** One column, as text, a row each. */
    List<String> rows(String sql, Object... parameters) {
        return each(sql, rs -> rs.getString(1), parameters);
    }

    /** The first row's first column, as text, or null where there is no row. */
    String one(String sql, Object... parameters) {
        List<String> found = rows(sql, parameters);
        return found.isEmpty() ? null : found.get(0);
    }

    /** A count, asked as one. */
    long count(String sql, Object... parameters) {
        return each(sql, rs -> rs.getLong(1), parameters).get(0);
    }

    /** A statement that changes rows, answering how many it changed. */
    int change(String sql, Object... parameters) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, parameters);
            return ps.executeUpdate();
        } catch (SQLException refused) {
            throw new IllegalStateException("could not change " + url + ": " + sql, refused);
        }
    }

    private static void bind(Connection c, PreparedStatement ps, Object... parameters)
            throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] instanceof String[] texts) {
                ps.setArray(i + 1, c.createArrayOf("text", texts));
            } else {
                ps.setObject(i + 1, parameters[i]);
            }
        }
    }

    /** A server setting as a fresh session on this database sees it. */
    String setting(String name) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT setting FROM pg_settings WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read " + name + " on " + url, unreadable);
        }
    }

    /**
     * How many rows anywhere in the database carry the value, as their row
     * text and inside a stored payload.
     *
     * <p>Two questions, because a payload is bytes and a row's text renders it
     * as hex: a value sitting in one would pass a look at the row text without
     * ever being seen.
     */
    long mentioning(String value) {
        long found = 0;
        try (Connection c = connect()) {
            for (String table : tables(c, """
                    SELECT table_schema, table_name FROM information_schema.tables
                    WHERE table_type = 'BASE TABLE'
                      AND table_schema NOT IN ('pg_catalog', 'information_schema')""")) {
                found += count(c, "SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?",
                        value);
            }
            for (String table : tables(c, """
                    SELECT table_schema, table_name FROM information_schema.columns
                    WHERE column_name = 'payload'
                      AND table_schema NOT IN ('pg_catalog', 'information_schema')""")) {
                found += count(c, "SELECT count(*) FROM " + table
                        + " WHERE convert_from(payload, 'UTF8') LIKE ?", value);
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read " + url, unreadable);
        }
        return found;
    }

    /** Content kept under a key, as it lies at rest, and whose the row says it is. */
    Optional<KeptContent> content(String key) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content, person::text FROM state.blob WHERE key = ?::uuid")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new KeptContent(rs.getBytes(1), rs.getString(2)))
                        : Optional.empty();
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("could not read the content kept as " + key,
                    unreadable);
        }
    }

    record KeptContent(byte[] atRest, String person) {
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static List<String> tables(Connection c, String query) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add("\"" + rs.getString(1) + "\".\"" + rs.getString(2) + "\"");
            }
        }
        return tables;
    }

    private static long count(Connection c, String query, String value) {
        try (PreparedStatement ps = c.prepareStatement(query)) {
            ps.setString(1, "%" + value + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException notText) {
            // A row type that will not render as text, or bytes that are not
            // text: neither is a place a value could be sitting as a string.
            return 0;
        }
    }
}
