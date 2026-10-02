package cloud.jengu.dbo.stream;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A {@link DataSource} over the driver this bundle's own wiring resolves.
 *
 * <p>Public because both halves of the substrate need it: a host's lanes
 * here, and the serving side's doors and step queues, which run the same
 * durable layer from this bundle and so need connections it can unwrap. A
 * pool given a JDBC URL instead is handed whichever copy of the driver
 * registered itself first, and under an application on the Spring Boot
 * assemblies that is the application's: every LISTEN then fails to unwrap,
 * and the listener retries once a second for the life of the door.
 *
 * <p>Small on purpose: what it exists to do is decide WHICH copy of the
 * driver opens the connection, which a class name handed to a pool does
 * not decide. Everything else about pooling stays Hikari's.
 */
public final class SubstrateConnections implements DataSource {

    private final Driver driver;
    private final String url;
    private final String user;
    private final String password;

    public SubstrateConnections(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        try {
            // Class.forName HERE, so the loader is this bundle's and the
            // package is the one it imports — the same resolution that
            // gives it org.postgresql.PGConnection.
            this.driver = (Driver) Class.forName("org.postgresql.Driver")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException("this bundle carries a PostgreSQL driver and "
                    + "imports the package a container's driver bundle exports, and "
                    + "neither answered — so a lane on the substrate cannot open one",
                    unreachable);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    @Override
    public Connection getConnection(String asUser, String withPassword) throws SQLException {
        Properties properties = new Properties();
        if (asUser != null) {
            properties.setProperty("user", asUser);
        }
        if (withPassword != null) {
            properties.setProperty("password", withPassword);
        }
        Connection open = driver.connect(url, properties);
        if (open == null) {
            // A driver returning null means the URL is not its own, which
            // here means the substrate was given an address for something
            // that is not PostgreSQL.
            throw new SQLException("not a PostgreSQL address: " + url);
        }
        return open;
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // The runtime has one logging binding and a driver's own writer is
        // not it.
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // Held by the pool, which is the thing that waits.
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
