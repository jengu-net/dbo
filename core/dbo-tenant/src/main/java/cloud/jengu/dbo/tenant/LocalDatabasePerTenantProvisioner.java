package cloud.jengu.dbo.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
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

    public LocalDatabasePerTenantProvisioner(String adminUrl, String user, String password) {
        this.adminUrl = adminUrl;
        this.user = user;
        this.password = password;
    }

    @Override
    public TenantDatabase provision(TenantSpec spec) {
        String dbName = "tenant_" + spec.code(); // code already validated
        try (Connection c = DriverManager.getConnection(adminUrl, user, password);
             PreparedStatement ps = c.prepareStatement("CREATE DATABASE " + dbName)) {
            ps.execute();
        } catch (SQLException e) {
            if (!DUPLICATE_DATABASE.equals(e.getSQLState())) {
                throw new IllegalStateException("provisioning failed for " + spec.code(), e);
            }
            // already provisioned: attach (idempotent)
        }
        DataSource pool = pools.computeIfAbsent(spec.code(), code -> {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(tenantUrl(dbName));
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(8);
            config.setPoolName("dbo-tenant-" + code);
            return new HikariDataSource(config);
        });
        return new TenantDatabase(pool);
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
        String dbName = "tenant_" + tenantCode;
        if (!tenantCode.matches("[a-z][a-z0-9_]{0,15}")) {
            throw new IllegalArgumentException("invalid tenant code: " + tenantCode);
        }
        try (Connection c = DriverManager.getConnection(adminUrl, user, password);
             PreparedStatement ps = c.prepareStatement(
                     "DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)")) {
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("deprovision failed for " + tenantCode, e);
        }
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
