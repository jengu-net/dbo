package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import javax.sql.DataSource;

import java.io.PrintWriter;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The host's half of this bundle: it fills the runner's whiteboard from
 * configuration, so a container reaching the store over the substrate needs
 * no code of its own.
 *
 * <p>This bundle carries both ends of the stream carrier. The door is the
 * store's and is mounted by the tenant runtime beside every other surface a
 * tenant serves. The lane is the <b>host's</b>, and a host is by definition
 * somebody else's process — which is how the two ends came to be written,
 * proven and shipped with nothing in any deployment ever constructing one of
 * them. A carrier whose client is only ever built by a test is a served
 * surface nobody exercises.
 *
 * <p>So the lane arrives the way the runner's own activator says a lane
 * arrives: <i>the host registers one {@code Lane} service per tenant it
 * offers work from</i>. Installing this bundle beside {@code dbo-runner} and
 * the bundles that contribute steps is then the whole deployment of a
 * participant that reaches the store over the substrate it already shares
 * with it — no route into any tenant, no callback accepted, and no
 * composition root here or there naming the other.
 *
 * <p><b>Nothing happens unless a host says so.</b> Every container that
 * serves tenants installs this bundle for the door, and must not grow lanes
 * into its own tenants by having done so. {@link #TENANTS} is the switch:
 * absent, this registers nothing and holds nothing open. Named, everything a
 * lane is held with is required, because a host missing one of them is a host
 * whose work would fail on the first verb rather than at startup.
 */
public final class Activator implements BundleActivator {

    /** The tenants this host holds a lane into; absent means it is not a host. */
    static final String TENANTS = "dbo.lane.tenants";
    /** The participant name this host enrolled under — its cursor on each tenant's feed. */
    static final String PARTICIPANT = "dbo.lane.participant";
    /**
     * Who the runs this host closes are recorded as.
     *
     * <p>Separate from {@link #PARTICIPANT}, and that separation is the whole
     * point: the participant is the enrolment the signature is checked
     * against, and the executor is the worker a run names. Over HTTP they are
     * already two — a credential authenticates and {@code dbo.worker.identity}
     * is written on the run — and a carrier that collapsed them would put the
     * enrolment's name on every run it closed, which is the one difference a
     * runner could tell its carriers apart by.
     *
     * <p>Absent means the participant, because a host that does not say what
     * it reports as reports as itself.
     */
    static final String EXECUTOR_NAME = "dbo.lane.executor.name";
    /** The private half of the key the participant is sealed to, base64 PKCS#8. */
    static final String SEALING_KEY = "dbo.lane.sealing.key";
    /** The private half of the key the participant signs with, base64 PKCS#8. */
    static final String SIGNING_KEY = "dbo.lane.signing.key";
    /** Which behaviour this host's steps are, recorded on every run it closes. */
    static final String EXECUTOR_VERSION = "dbo.lane.executor.version";
    /** Whose code that is — a provider can be withdrawn, so a run has to name it. */
    static final String EXECUTOR_PROVIDER = "dbo.lane.executor.provider";
    /** Where on the scope chain this host runs: {@code zone:ee}, {@code organisation:x}, absent. */
    static final String SCOPE = "dbo.lane.scope";

    private final List<StreamLane> held = new ArrayList<>();
    private AutoCloseable substrate;

    @Override
    public void start(BundleContext context) {
        String tenants = context.getProperty(TENANTS);
        if (tenants == null || tenants.isBlank()) {
            return;
        }
        String participant = required(context, PARTICIPANT);
        String executor = context.getProperty(EXECUTOR_NAME);
        Executor identity = new Executor(
                executor == null || executor.isBlank() ? participant : executor,
                required(context, EXECUTOR_VERSION),
                required(context, EXECUTOR_PROVIDER), scope(context.getProperty(SCOPE)));
        PrivateKey sealing = privateKey("X25519", required(context, SEALING_KEY), SEALING_KEY);
        PrivateKey signing = privateKey("Ed25519", required(context, SIGNING_KEY), SIGNING_KEY);
        // The deployment's own substrate, under the name the serving side
        // already reads it by: there is one of these per deployment, and a
        // host pointed at a second one would be reaching a different store.
        com.zaxxer.hikari.HikariConfig pool = new com.zaxxer.hikari.HikariConfig();
        pool.setPoolName("dbo-lane-substrate");
        // THE DRIVER THIS BUNDLE IS WIRED TO, resolved here rather than named
        // for Hikari to find.
        //
        // A driver class NAME is resolved globally: Hikari scans the drivers
        // DriverManager has registered and then falls back to the thread
        // context loader. Both reach outside this bundle. Under an
        // application built on the Spring Boot assemblies the store's jars are
        // on the application's own classpath, that copy of the driver
        // registered itself with DriverManager, and the pool was built from it
        // — while `org.postgresql.PGConnection`, which this bundle imports,
        // came from the container's driver bundle. The two are one name and
        // two classes, and the unwrap DBOS needs to reach LISTEN cannot
        // succeed across them, so every wake-up degrades to a poll.
        //
        // Resolving the driver the same way the interface is resolved is what
        // makes the pair agree, whichever way this bundle is wired: to the
        // container's driver bundle where one exists, and to the copy in lib/
        // in a participant's container where none does. The import is optional
        // for that reason, and this keeps both of its homes self-consistent
        // rather than only the one that happens to be tested.
        pool.setDataSource(new OnThisBundlesDriver(
                required(context, "dbo.substrate.url"),
                context.getProperty("dbo.substrate.user"),
                context.getProperty("dbo.substrate.password")));
        com.zaxxer.hikari.HikariDataSource source = new com.zaxxer.hikari.HikariDataSource(pool);
        substrate = source;
        try {
            for (String tenant : tenants.split(",")) {
                String code = tenant.trim();
                if (code.isEmpty()) {
                    continue;
                }
                StreamLane lane = StreamLane.holding(source, code, participant, identity,
                        sealing, signing);
                held.add(lane);
                // Named on the service so an operator reading the registry
                // sees which tenant a lane is into and over what: a container
                // holding several is otherwise a row repeated.
                Hashtable<String, Object> properties = new Hashtable<>();
                properties.put("dbo.tenant", code);
                properties.put("dbo.lane.carrier", "stream");
                context.registerService(Lane.class, lane, properties);
            }
        } catch (RuntimeException | Error failed) {
            // A lane that did not open leaves none behind it: half a host is
            // worse than none, because the work it does not poll for sits
            // looking like work nobody wanted.
            stop(context);
            throw failed;
        }
    }

    @Override
    public void stop(BundleContext context) {
        // The framework unregisters the services; what it cannot do is close
        // the substrate connection each lane holds open.
        for (StreamLane lane : held) {
            try {
                lane.close();
            } catch (RuntimeException letGo) {
                // Shutting down. A lane that will not close cannot stop the
                // others from closing.
            }
        }
        held.clear();
        if (substrate != null) {
            try {
                substrate.close();
            } catch (Exception letGo) {
                // As above.
            }
            substrate = null;
        }
    }

    private static String required(BundleContext context, String property) {
        String value = context.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("'" + Activator.TENANTS + "' names tenants to hold a "
                    + "lane into, so this container is a host and '" + property + "' is not "
                    + "optional: a host holds a participant name, both private keys it enrolled "
                    + "with, the executor it reports as, and the substrate it reaches");
        }
        return value;
    }

    private static Scope scope(String wire) {
        if (wire == null || wire.isBlank() || "baseline".equalsIgnoreCase(wire)) {
            return Scope.BASELINE;
        }
        int colon = wire.indexOf(':');
        String at = colon < 0 ? wire : wire.substring(0, colon);
        String code = colon < 0 ? null : wire.substring(colon + 1);
        if ("zone".equalsIgnoreCase(at) && code != null) {
            return Scope.zone(code);
        }
        if ("organisation".equalsIgnoreCase(at) && code != null) {
            return Scope.organisation(code);
        }
        throw new IllegalStateException("'" + SCOPE + "' is one place on the chain — "
                + "'zone:<code>', 'organisation:<code>', or absent for the baseline: " + wire);
    }

    private static PrivateKey privateKey(String algorithm, String base64, String property) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (RuntimeException | java.security.GeneralSecurityException unreadable) {
            // The value never appears in the message. It is the private half
            // of an enrolment key, and a startup line is the last place it
            // should be able to land.
            throw new IllegalStateException("'" + property + "' is not a base64 PKCS#8 "
                    + algorithm + " private key, so this host cannot hold a lane", unreadable);
        }
    }

    /**
     * A {@link DataSource} over the driver this bundle's own wiring resolves.
     *
     * <p>Small on purpose: what it exists to do is decide WHICH copy of the
     * driver opens the connection, which a class name handed to a pool does
     * not decide. Everything else about pooling stays Hikari's.
     */
    private static final class OnThisBundlesDriver implements DataSource {

        private final Driver driver;
        private final String url;
        private final String user;
        private final String password;

        OnThisBundlesDriver(String url, String user, String password) {
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
}
