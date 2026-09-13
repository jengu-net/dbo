package cloud.jengu.dbo.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shipped default: a DATABASE per tenant on one admin-configured
 * instance — dev/test topology equals production topology (a tenant is a
 * database). Pools ride HikariCP (embedded privately in the bundle).
 *
 * <p>The admin credentials live HERE and only here; callers receive pools.
 */
public final class LocalDatabasePerTenantProvisioner implements TenantDatabaseProvisioner, AutoCloseable {

    private static final String DUPLICATE_DATABASE = "42P04";

    private final String adminUrl;
    private final String user;
    private final String password;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final Map<String, String> bootstrapSecrets = new ConcurrentHashMap<>();
    private final Map<String, String> rpSecrets = new ConcurrentHashMap<>();
    private volatile java.util.List<String> rpRedirectUris = java.util.List.of();
    private volatile String rpClientId;
    private volatile int connectionsPerTenant = connectionsPerTenantByDefault();

    public LocalDatabasePerTenantProvisioner(String adminUrl, String user, String password) {
        this.adminUrl = adminUrl;
        this.user = user;
        this.password = password;
    }

    /**
     * How many statements one tenant may have in flight at once.
     *
     * <p>Per TENANT, so a node serving twelve of them wants a hundred
     * connections of a server whose default is a hundred, and the thirteenth
     * tenant is refused rather than slowed. That makes this a number about
     * the node and the server together, not about a tenant — and it was a
     * constant, the same on four slow cores as on a large machine.
     *
     * <p>Set it from a measurement of the box it runs on. There is no value
     * that is right everywhere: below the number of cores a tenant cannot
     * use the machine, and far above it the queue moves from this pool into
     * the server, where it is harder to see and shared with everybody else.
     */
    public LocalDatabasePerTenantProvisioner connectionsPerTenant(int connections) {
        if (connections < 1) {
            throw new IllegalArgumentException("a tenant with no connections serves nothing: "
                    + connections);
        }
        this.connectionsPerTenant = connections;
        return this;
    }

    /**
     * The default, which is the smaller of eight and what the machine has —
     * a pool larger than the cores under it queues inside the database
     * instead of in front of it, and the eight was chosen when every machine
     * this ran on had at least that many.
     */
    private static int connectionsPerTenantByDefault() {
        String said = System.getProperty("dbo.connections.per.tenant");
        if (said != null) {
            return Integer.parseInt(said);
        }
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    @Override
    public TenantDatabase provision(TenantSpec spec) {
        String dbName = spec.databaseName(); // Postgres-safe, collision-free
        // Asked before created: Postgres has no CREATE DATABASE IF NOT
        // EXISTS in any version, and it logs an ERROR whenever it raises one,
        // whether or not the client catches it — so the catch-and-attach path,
        // correct as it is, put "database already exists" into the server log
        // on every boot after the first. The common re-attach now asks and
        // skips; two provisioners racing still land on 42P04, which the
        // handler below absorbs — the catch is what makes this correct, the
        // guard only makes it quiet.
        try (Connection c = adminConnection()) {
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, dbName);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                try (PreparedStatement ps = c.prepareStatement("CREATE DATABASE " + dbName)) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            if (!DUPLICATE_DATABASE.equals(e.getSQLState())) {
                throw new IllegalStateException("provisioning failed for " + spec.code(), e);
            }
            // lost the race to another provisioner: attach (idempotent)
        }
        applyTimeouts(dbName);
        DataSource pool = pools.computeIfAbsent(spec.code(), code -> {
            HikariConfig config = new HikariConfig();
            // in-container the driver resolves through OUR wiring
            // (org.postgresql imported), never DriverManager discovery
            config.setDriverClassName("org.postgresql.Driver");
            config.setJdbcUrl(tenantUrl(dbName));
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(connectionsPerTenant);
            config.setPoolName("dbo-tenant-" + code);
            return new HikariDataSource(config);
        });
        return new TenantDatabase(pool, bootstrapSecrets.computeIfAbsent(spec.code(),
                code -> generatedSecret()),
                rpClientId,
                rpRedirectUris.isEmpty() ? null
                        : rpSecrets.computeIfAbsent(spec.code(), code -> generatedSecret()),
                rpRedirectUris.stream()
                        .map(uri -> uri.replace("{code}", spec.code())).toList());
    }

    /** Dev: redirect URIs for the per-tenant relying-party client. */
    public void rpRedirectUris(java.util.List<String> uris) {
        this.rpRedirectUris = java.util.List.copyOf(uris);
    }

    /** Dev: the client id the relying party authenticates as. */
    public void rpClientId(String clientId) {
        this.rpClientId = clientId;
    }

    /**
     * How long a caller asking for a secret waits for provisioning to produce
     * one. Generous, because it is only ever spent when the answer would
     * otherwise have been wrong.
     */
    static final java.time.Duration SECRET_WAIT = java.time.Duration.ofSeconds(10);

    /** Dev/test convenience: the generated relying-party client secret. */
    public String rpClientSecret(String tenantCode) {
        return awaitSecret(rpSecrets, tenantCode, "relying-party", SECRET_WAIT);
    }

    /** Dev/test convenience: the generated bootstrap-client secret (§13). */
    public String bootstrapClientSecret(String tenantCode) {
        return awaitSecret(bootstrapSecrets, tenantCode, "bootstrap", SECRET_WAIT);
    }

    /**
     * Waits for the secret, then refuses to invent one.
     *
     * <p>Both of these used to answer {@code null} for a tenant whose
     * provisioning had not finished, and a scan returning is not provisioning
     * having finished. The null then travelled — into {@code URLEncoder.encode},
     * which reports a null string rather than a missing tenant, so the
     * diagnosis arrived two frames from the cause and named neither the tenant
     * nor the reason.
     *
     * <p>So: wait for the condition rather than for the call, and if it never
     * arrives, say which tenant and which secret. A caller cannot do anything
     * useful with a null here — every one of them is about to put it in a
     * request.
     */
    private static String awaitSecret(java.util.Map<String, String> secrets, String tenantCode,
            String which, java.time.Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            String secret = secrets.get(tenantCode);
            if (secret != null) {
                return secret;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("tenant '" + tenantCode + "' has no " + which
                        + " secret after " + wait.toMillis() + "ms: it has not been provisioned"
                        + " (a scan that returned is not provisioning that finished)");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for tenant '"
                        + tenantCode + "' to be provisioned", interrupted);
            }
        }
    }

    /**
     * As above, with the caller's own deadline — for a caller that would rather
     * be told quickly than wait the default out.
     */
    public String bootstrapClientSecret(String tenantCode, java.time.Duration wait) {
        return awaitSecret(bootstrapSecrets, tenantCode, "bootstrap", wait);
    }

    /**
     * DriverManager discovery is boot-classloader-blind inside OSGi — the
     * driver bundle's classes are invisible to it. Ask the driver directly
     * through this bundle's own wiring instead — a landmine hit twice: once
     * in the serving distribution, once in an embedded host container.
     */
    private Connection adminConnection() throws SQLException {
        java.util.Properties props = new java.util.Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        java.sql.Driver driver = new org.postgresql.Driver();
        Connection connection = driver.connect(adminUrl, props);
        if (connection == null) {
            throw new SQLException("driver refused url " + adminUrl);
        }
        return connection;
    }

    private static String generatedSecret() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Closes the pool WITHOUT dropping data — spec retraction, not erasure. */
    @Override
    public void release(String tenantCode) {
        HikariDataSource pool = pools.remove(tenantCode);
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    public void deprovision(String tenantCode) {
        release(tenantCode);
        // Checked against what a code IS, rather than a second, narrower idea
        // of one. This used to demand no hyphen and at most sixteen
        // characters, while a spec accepts a hyphen and a hundred and
        // twenty-eight — so a tenant with an ordinary name could be
        // provisioned and never dropped, and erasure-by-drop failed on the
        // codes most tenants actually have.
        if (!TenantSpec.isCode(tenantCode)) {
            throw new IllegalArgumentException("invalid tenant code: " + tenantCode);
        }
        String dbName = TenantSpec.databaseName(tenantCode);
        try (Connection c = adminConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)")) {
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("deprovision failed for " + tenantCode, e);
        }
    }

    /**
     * Worst-case feed delay becomes the timeout, by construction —
     * idle-in-transaction and runaway transactions are capped per tenant
     * database; the admin role is exempted (fidelity restores may run long).
     */
    private void applyTimeouts(String dbName) {
        try (Connection c = adminConnection()) {
            // The server knows its own version, so it is asked once rather
            // than probed by a statement whose failure it logs:
            // transaction_timeout arrived in PG17, and an embedding host on
            // PG16 was collecting an "unrecognized configuration parameter"
            // ERROR per tenant per boot for a condition the code handles.
            if (transactionTimeoutSupported == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM pg_settings WHERE name = 'transaction_timeout'");
                     java.sql.ResultSet rs = ps.executeQuery()) {
                    transactionTimeoutSupported = rs.next();
                }
            }
            java.util.List<String> ddls = new java.util.ArrayList<>(java.util.List.of(
                    "ALTER DATABASE " + dbName + " SET idle_in_transaction_session_timeout = '60s'"));
            // Personal data passes through this database in the clear, in
            // flight; the one way it could land is the server logging a
            // statement's parameters. Pinned here, before the pool opens, so
            // every session the store ever holds on it sees the pin.
            ddls.addAll(LogDiscipline.pins(dbName));
            if (transactionTimeoutSupported) {
                ddls.add("ALTER DATABASE " + dbName + " SET transaction_timeout = '300s'");
                // The ROLE setting is role-global, not per tenant: every
                // tenant was re-applying one row, so N concurrent bring-ups
                // meant N writers on ONE catalogue tuple. Applied once per
                // process instead — the same end state, without the crowd.
                if (ROLE_TIMEOUT_APPLIED.compareAndSet(false, true)) {
                    ddls.add("ALTER ROLE " + quoteIdent(user) + " SET transaction_timeout = '0'");
                }
            }
            for (String ddl : ddls) {
                applyWithRetry(c, ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("timeout settings failed for " + dbName, e);
        }
    }

    /** Whether this server's major has transaction_timeout — asked once. */
    private volatile Boolean transactionTimeoutSupported;

    /**
     * The role-wide timeout, applied once per process.
     *
     * <p>Static rather than per provisioner: a test JVM builds several
     * provisioners against one server, and the setting they were each
     * applying is the same row for all of them.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ROLE_TIMEOUT_APPLIED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * One settings DDL, retried through a concurrent catalogue write.
     *
     * <p>{@code ALTER DATABASE/ROLE ... SET} rewrites a row in
     * {@code pg_db_role_setting}, and Postgres does not serialise those for
     * us: two bring-ups landing together get {@code tuple concurrently
     * updated} — an internal error, not a conflict the caller did anything
     * to cause. It is transient by construction, so it is retried rather
     * than failing a tenant's bring-up over a race between two tenants.
     *
     * <p>Matched on the message because the SQLState is the catch-all
     * {@code XX000}: gating on that alone would swallow unrelated internal
     * errors, which is how a real fault becomes an infinite retry.
     */
    private static void applyWithRetry(java.sql.Connection c, String ddl) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try (PreparedStatement ps = c.prepareStatement(ddl)) {
                ps.execute();
                return;
            } catch (SQLException e) {
                if ("42704".equals(e.getSQLState())) {
                    // the backstop for anything a major does not recognise —
                    // a missing timeout is never a bring-up failure
                    return;
                }
                boolean raced = String.valueOf(e.getMessage()).contains("tuple concurrently updated");
                if (!raced || attempt >= 5) {
                    throw e;
                }
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private String tenantUrl(String dbName) {
        int lastSlash = adminUrl.lastIndexOf('/');
        int query = adminUrl.indexOf('?', lastSlash);
        String suffix = query < 0 ? "" : adminUrl.substring(query);
        return adminUrl.substring(0, lastSlash + 1) + dbName + suffix;
    }

    /** The tenants this provisioner has made a database for. */
    public java.util.Set<String> provisioned() {
        return java.util.Set.copyOf(pools.keySet());
    }

    @Override

    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
