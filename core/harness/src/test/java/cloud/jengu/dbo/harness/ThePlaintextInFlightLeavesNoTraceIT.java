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
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Personal data passes through a tenant's database in the clear, in flight,
 * and the database writes none of it down.
 *
 * <p>The exposure is one setting: a Postgres server logs the parameters of a
 * logged or a slow statement in full unless told otherwise, and a slow
 * validation of a patient is then a patient in a log that is not per tenant.
 * What is proven is that a database this store provisions is pinned before
 * any session opens on it, and that an isolated tenant refuses a database
 * that was not.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThePlaintextInFlightLeavesNoTraceIT {

    private static final String ISOLATED = "vaikne";
    private static final String LEAKY = "vaikne-lekkiv";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-no-trace");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ThePlaintextInFlightLeavesNoTraceIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(ISOLATED + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(ISOLATED));
        UntilServed.scan(manager, ISOLATED);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    @DisplayName("a database this store provisions is pinned not to log parameters, before "
            + "any session opens on it")
    @Proving(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE)
    void aProvisionedDatabaseIsPinned() throws Exception {
        // As a fresh session sees it, which is how every pooled session the
        // store holds sees it: the pin is a property of the database.
        assertEquals("0", shown(ISOLATED, "log_parameter_max_length"),
                "a slow statement on this database would be logged with its parameters");
        assertEquals("0", shown(ISOLATED, "log_parameter_max_length_on_error"),
                "a failing statement on this database would be logged with its parameters");
    }

    @Test
    @DisplayName("an isolated tenant refuses to come up on a database that would log its "
            + "people, naming the setting")
    @Proving(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE)
    void anIsolatedTenantRefusesALeakyDatabase() throws Exception {
        // Provision it the store's way — pinned — and then undo the pin the
        // way a managed server's operator might: parameters logged in full
        // whenever a statement is slow.
        Files.writeString(dir.resolve(LEAKY + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(LEAKY));
        String db = "tenant_" + LEAKY.replace('-', '_');
        try (Connection admin = java.sql.DriverManager.getConnection(
                SharedPostgres.urlFor("ThePlaintextInFlightLeavesNoTraceIT"),
                postgres.getUsername(), postgres.getPassword());
             PreparedStatement create = admin.prepareStatement("CREATE DATABASE " + db)) {
            create.execute();
        }
        for (String undo : List.of(
                "ALTER DATABASE " + db + " SET log_parameter_max_length = -1",
                "ALTER DATABASE " + db + " SET log_min_duration_statement = 0")) {
            try (Connection admin = java.sql.DriverManager.getConnection(
                    SharedPostgres.urlFor("ThePlaintextInFlightLeavesNoTraceIT"),
                    postgres.getUsername(), postgres.getPassword());
                 PreparedStatement ps = admin.prepareStatement(undo)) {
                ps.execute();
            }
        }
        // The provisioner re-pins what it provisions, which is the point of
        // it; so the check is exercised on the judgement itself, over the
        // database as an operator left it.
        java.util.Optional<String> why = leakOf(db);
        assertTrue(why.isPresent(), "a database logging slow statements with their "
                + "parameters was judged safe");
        assertTrue(why.get().contains("log_parameter_max_length")
                        && why.get().contains("log_min_duration_statement"),
                "the refusal does not name what would leak: " + why.get());
        assertTrue(leakOf("tenant_" + ISOLATED).isEmpty(),
                "the pinned database was judged to leak: " + leakOf("tenant_" + ISOLATED));
    }

    private static java.util.Optional<String> leakOf(String db) throws Exception {
        // The judgement is package-private to the tenant module; reached the
        // way the manager reaches it, through the tenant's own database.
        java.lang.reflect.Method leak = Class.forName("cloud.jengu.dbo.tenant.LogDiscipline")
                .getDeclaredMethod("leak", javax.sql.DataSource.class);
        leak.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Optional<String> why = (java.util.Optional<String>) leak.invoke(null, source(db));
        return why;
    }

    private static String shown(String tenant, String setting) throws Exception {
        try (Connection c = source("tenant_" + tenant.replace('-', '_')).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT setting FROM pg_settings WHERE name = ?")) {
            ps.setString(1, setting);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static PGSimpleDataSource source(String db) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x").replaceAll("/[^/?]+(\\?.*)?$", "/" + db));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
