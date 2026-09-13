package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A store that is not superuser mounts its tenants, and still says what the
 * server will do with their parameters.
 *
 * <p>The log-parameter settings are superuser-only, and a managed Postgres —
 * RDS, Cloud SQL, anything that keeps superuser for itself — hands this store
 * an ordinary role. The pin used to ride in the timeout DDL list, so the
 * refusal was fatal and every tenant failed to mount, under a message that
 * said "timeout settings failed" and named nothing about logging. A whole
 * deployment could not serve, and nothing in the chain from there to a 500 at
 * login mentioned the cause.
 *
 * <p>What the design always said should happen is what is proven here: pinned
 * where the store can pin, CHECKED where it cannot. The check is not weaker
 * for being the fallback — an isolated tenant refuses a database that would
 * write its people down whether or not this role could have pinned it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AStoreWithoutSuperuserStillMountsIT {

    private static final String TOKEN = "AStoreWithoutSuperuserStillMountsIT";
    private static final String ROLE = "ordinary_store";
    private static final String SECRET = "ordinary";
    private static final String TENANT = "tavaline";
    private static final String ON_A_LEAKY_ONE = "tavaline-lekkiv";

    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        String url = SharedPostgres.urlFor(TOKEN);
        superuser(url, "DROP ROLE IF EXISTS " + ROLE,
                "CREATE ROLE " + ROLE + " LOGIN CREATEDB PASSWORD '" + SECRET + "'");
        dir = Files.createTempDirectory("dbo-ordinary");
        provisioner = new LocalDatabasePerTenantProvisioner(url, ROLE, SECRET);
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        declare(TENANT);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    /**
     * Without this the class could pass while proving nothing: a role that
     * turned out to be superuser takes the pinning path and every assertion
     * below still holds, for the wrong reason.
     */
    @Test
    @DisplayName("the role is an ordinary one, and the server says the setting is superuser-only")
    void theServerIsOneThisStoreMayNotPin() throws Exception {
        try (Connection c = asOrdinary(null).getConnection()) {
            assertEquals("off", oneValue(c, "SELECT current_setting('is_superuser')"),
                    "the role is a superuser, so nothing here is exercising the fallback");
            assertEquals("superuser", oneValue(c,
                            "SELECT context FROM pg_settings WHERE name = 'log_parameter_max_length'"),
                    "this server would let an ordinary role set it, so nothing here is refused");
        }
    }

    @Test
    @DisplayName("a tenant comes up on a server this store may not pin, rather than failing to mount")
    @Proving(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE)
    void aTenantComesUp() {
        UntilServed.scan(manager, TENANT);
        assertTrue(manager.databaseOf(TENANT).isPresent(),
                "the tenant did not mount: " + manager.troubles());
    }

    /**
     * The other half of the same assertion. Mounting would also look like
     * this if the pin had quietly succeeded, and then the fallback would be
     * untested — so the database is asked what it actually carries.
     */
    @Test
    @DisplayName("and the pin really was skipped rather than quietly applied")
    void theDatabaseIsNotPinned() throws Exception {
        UntilServed.scan(manager, TENANT);
        try (Connection c = asOrdinary("tenant_" + TENANT).getConnection()) {
            assertEquals("-1", oneValue(c,
                            "SELECT setting FROM pg_settings WHERE name = 'log_parameter_max_length'"),
                    "the database was pinned, so the fallback was never taken");
        }
    }

    @Test
    @DisplayName("an isolated tenant still refuses a database that would log its people, "
            + "on a server the store could not have pinned")
    @Proving(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE)
    void theCheckIsNotWeakerForBeingTheFallback() throws Exception {
        // Left the way a managed server's operator might leave it, before the
        // store ever sees the database: the store attaches to what is there.
        String db = "tenant_" + ON_A_LEAKY_ONE.replace('-', '_');
        superuser(SharedPostgres.urlFor(TOKEN),
                "CREATE DATABASE " + db + " OWNER " + ROLE,
                "ALTER DATABASE " + db + " SET log_parameter_max_length = -1",
                "ALTER DATABASE " + db + " SET log_min_duration_statement = 0");
        declare(ON_A_LEAKY_ONE);

        Set<String> serving = manager.scanOnce();
        assertFalse(serving.contains(ON_A_LEAKY_ONE),
                "an isolated tenant came up on a database that logs its parameters");
        String why = manager.troubles().get(ON_A_LEAKY_ONE);
        assertTrue(why != null && why.contains("log_parameter_max_length"),
                "the refusal does not name what would leak: " + why);
    }

    private static void declare(String code) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code));
    }

    private static void superuser(String url, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(url,
                SharedPostgres.username(), SharedPostgres.password())) {
            for (String sql : List.of(statements)) {
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                }
            }
        }
    }

    private static String oneValue(Connection c, String query) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static PGSimpleDataSource asOrdinary(String database) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(database == null ? SharedPostgres.urlFor(TOKEN)
                : SharedPostgres.jdbcUrl(database));
        source.setUser(ROLE);
        source.setPassword(SECRET);
        return source;
    }
}
