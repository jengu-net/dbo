package cloud.jengu.dbo.maintenance;

import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * How a COPY reaches the database: the driver's copy interface, as the class
 * space that opened the connection sees it.
 *
 * <p>A seam because this package and the connection need not share one. Under
 * the Spring Boot assemblies this package is shared with the application, so
 * its classes link the application's copy of the driver, while a tenant's pool
 * is opened inside the container by the driver bundle there. Unwrapping one
 * copy's connection to the other copy's interface fails — and a backup fails
 * after its response has begun, so the operator is handed a truncated archive
 * under a 200. The caller that opened the connection is the one whose classes
 * match it, so it says how to copy.
 */
public interface Copying {

    /** {@code COPY ... TO STDOUT}, into {@code to}; the rows copied. */
    long out(Connection connection, String sql, OutputStream to)
            throws SQLException, IOException;

    /** {@code COPY ... FROM STDIN}, from {@code from}; the rows copied. */
    long in(Connection connection, String sql, InputStream from)
            throws SQLException, IOException;

    /**
     * Through the driver as this package sees it: right wherever the
     * connection comes from the same copy, which is everywhere but a shared
     * package under an embedding host.
     */
    Copying HERE = new Copying() {
        @Override
        public long out(Connection connection, String sql, OutputStream to)
                throws SQLException, IOException {
            return connection.unwrap(PGConnection.class).getCopyAPI().copyOut(sql, to);
        }

        @Override
        public long in(Connection connection, String sql, InputStream from)
                throws SQLException, IOException {
            return connection.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, from);
        }
    };
}
