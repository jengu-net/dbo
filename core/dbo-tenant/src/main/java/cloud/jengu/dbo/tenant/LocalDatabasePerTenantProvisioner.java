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

    public LocalDatabasePerTenantProvisioner(String adminUrl, String user, String password) {
        this.adminUrl = adminUrl;
        this.user = user;
        this.password = password;
    }

    @Override
    public TenantDatabase provision(TenantSpec spec) {
        String dbName = spec.databaseName(); // Postgres-safe, collision-free
        try (Connection c = adminConnection();
             PreparedStatement ps = c.prepareStatement("CREATE DATABASE " + dbName)) {
            ps.execute();
        } catch (SQLException e) {
            if (!DUPLICATE_DATABASE.equals(e.getSQLState())) {
                throw new IllegalStateException("provisioning failed for " + spec.code(), e);
            }
            // already provisioned: attach (idempotent)
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
            config.setMaximumPoolSize(8);
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
        String dbName = TenantSpec.databaseName(tenantCode);
        if (!tenantCode.matches("[a-z][a-z0-9_]{0,15}")) {
            throw new IllegalArgumentException("invalid tenant code: " + tenantCode);
        }
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
            for (String ddl : new String[] {
                    "ALTER DATABASE " + dbName + " SET idle_in_transaction_session_timeout = '60s'",
                    "ALTER DATABASE " + dbName + " SET transaction_timeout = '300s'",
                    "ALTER ROLE " + quoteIdent(user) + " SET transaction_timeout = '0'",
            }) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                } catch (SQLException e) {
                    if ("42704".equals(e.getSQLState())) {
                        // transaction_timeout arrived in PG17. An embedding
                        // host (runs the platform's own
                        // PG16) keeps the timeouts its major supports —
                        // never a bring-up failure.
                        continue;
                    }
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("timeout settings failed for " + dbName, e);
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

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
