package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Erasure drops the tenant's database.
 *
 * <p>An operator act with no door: nothing a deployment serves reaches it, so
 * it is proven here, against the provisioner, and nowhere else. A tenant's
 * life short of erasure — coming up, being retracted and declared again, its
 * database's timeouts, its name — is walked in Rowling Land, in the
 * tenant-opening story.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantRuntimeIT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("TenantRuntimeIT");
        dir = Files.createTempDirectory("dbo-tenants");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        manager.close();
        SuiteDatabases.retire(provisioner);
    }

    private static String specA() {
        return """
                {"code":"aiakas","face":"r4","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}""".formatted(EID);
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Spec file → provisioned database → live FHIR endpoint. */
    @Test
    @Order(1)
    void aSpecFileBecomesALiveTenant() throws Exception {
        Files.writeString(dir.resolve("aiakas.json"), specA());
        assertEquals(java.util.Set.of("aiakas"), UntilServed.scan(manager, "aiakas"));

        String base = manager.baseUrl("aiakas");
        HttpResponse<String> created = post(base + "/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38001010001"}],
                 "name":[{"family":"Aiakas"}]}""".formatted(EID));
        assertEquals(201, created.statusCode());
        assertTrue(get(base + "/Patient?family=aiakas").body().contains("Aiakas"));
    }

    /** Erasure is only the explicit deprovision: the database is dropped. */
    @Test
    @Order(4)
    @Proving(DboPromises.TEN_ERASURE_BY_DROP)
    void deprovisionDropsTheDatabase() throws Exception {
        Files.delete(dir.resolve("aiakas.json"));
        manager.scanOnce();
        provisioner.deprovision("aiakas");

        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname = 'tenant_aiakas'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getLong(1), "erasure-by-drop must remove the database");
        }
        assertFalse(manager.codes().contains("aiakas"));
    }

    /**
     * The codes tenants actually have, not the short ones tests pick.
     *
     * <p>A spec accepts a hyphen and up to a hundred and twenty-eight
     * characters; the drop used to demand no hyphen and at most sixteen. So a
     * tenant with an ordinary name could be provisioned and never erased, and
     * every test of erasure had happened to use a name short and plain enough
     * to slip through the narrower rule.
     */
    @Test
    @Order(5)
    @Proving(DboPromises.TEN_ERASURE_BY_DROP)
    void erasureTakesTheCodesASpecAccepts() {
        // Nothing of this name was provisioned, so what is being asked is
        // whether the drop will CONSIDER the name at all — it refused before
        // it ever reached the database.
        provisioner.deprovision("mingi-pikem-nimi");
        provisioner.deprovision("a-tenant-whose-code-is-considerably-longer-than-sixteen");

        IllegalArgumentException notACode = assertThrows(IllegalArgumentException.class,
                () -> provisioner.deprovision("Not A Code"));
        assertTrue(notACode.getMessage().contains("Not A Code"), notACode.getMessage());
    }

}
