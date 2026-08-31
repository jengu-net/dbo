package cloud.jengu.dbo.harness;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * ONE Postgres for the whole IT suite, so the classes can run concurrently
 * instead of each paying a container start (26 of them, serially, was most
 * of the suite's wall-clock).
 *
 * <p>Isolation is the same shape the store itself uses: a database per
 * unit. Every test class takes {@link #urlFor} with a token of its own and
 * never touches the container's default database — and a class that drives
 * the tenant manager gives its tenants class-unique CODES, because tenant
 * database names are derived from them and the server is now shared.
 *
 * <p>The container is deliberately never stopped: it is reaped by Ryuk when
 * the JVM exits, and stopping it from any one class's {@code @AfterAll}
 * would pull the floor out from under the classes still running.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer<?> CONTAINER = start();

    private SharedPostgres() {
    }

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:17-alpine")
                // concurrent classes × per-tenant pools exhaust the default
                // 100 quickly, and exhaustion presents as "too many clients"
                // from whichever class happened to be unlucky
                .withCommand("postgres", "-c", "max_connections=400");
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(SharedPostgres::dropSuiteDatabases));
        return container;
    }

    /**
     * Every database this run created goes away with it: the per-class
     * {@code it_*} databases and the {@code tenant_*} ones the provisioner
     * made underneath them. The server outlives individual classes now, so
     * nothing else would clean up after a class that finished.
     */
    private static void dropSuiteDatabases() {
        try (Connection c = DriverManager.getConnection(CONTAINER.getJdbcUrl(),
                CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement st = c.createStatement()) {
            java.util.List<String> databases = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT datname FROM pg_database WHERE datistemplate = false")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    // the container belongs to this suite, so everything
                    // beside its own two databases was made by a test —
                    // it_*, tenant_*, and the ones classes create by hand
                    if (!name.equals(CONTAINER.getDatabaseName()) && !name.equals("postgres")) {
                        databases.add(name);
                    }
                }
            }
            for (String database : databases) {
                // FORCE: pooled connections from a closed manager can outlive
                // it, and a single straggler blocks an ordinary DROP
                st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        } catch (SQLException e) {
            // best effort: the container is about to be reaped anyway, and
            // failing the run over cleanup would be worse than a leftover
            System.err.println("shared postgres cleanup skipped: " + e.getMessage());
        }
    }

    public static PostgreSQLContainer<?> get() {
        return CONTAINER;
    }

    /** JDBC url for a database of this caller's own, created on first ask. */
    public static String urlFor(String token) {
        String database = "it_" + token.toLowerCase();
        try (Connection c = DriverManager.getConnection(CONTAINER.getJdbcUrl(),
                CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            if (!"42P04".equals(e.getSQLState())) { // duplicate_database
                throw new IllegalStateException("cannot create " + database, e);
            }
        }
        return jdbcUrl(database);
    }

    /** Same host/params as the container's own url, pointed at another database. */
    static String jdbcUrl(String database) {
        String url = CONTAINER.getJdbcUrl();
        int lastSlash = url.lastIndexOf('/');
        int params = url.indexOf('?', lastSlash);
        return params < 0
                ? url.substring(0, lastSlash + 1) + database
                : url.substring(0, lastSlash + 1) + database + url.substring(params);
    }

    static String username() {
        return CONTAINER.getUsername();
    }

    static String password() {
        return CONTAINER.getPassword();
    }
}
