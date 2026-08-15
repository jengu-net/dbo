package cloud.jengu.dbo.harness;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dbo#17: spec files become live tenants; retract ≠ erase; deprovision drops. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantRuntimeIT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        dir = Files.createTempDirectory("dbo-tenants");
        provisioner = new LocalDatabasePerTenantProvisioner(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
        postgres.stop();
    }

    private static String specA() {
        return """
                {"code":"aiakas","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"]},
                  {"name":"Observation","identity":"internal"}]}""".formatted(EID);
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
        assertEquals(java.util.Set.of("aiakas"), manager.scanOnce());

        String base = manager.baseUrl("aiakas");
        HttpResponse<String> created = post(base + "/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38001010001"}],
                 "name":[{"family":"Aiakas"}]}""".formatted(EID));
        assertEquals(201, created.statusCode());
        assertTrue(get(base + "/Patient?family=aiakas").body().contains("Aiakas"));
    }

    /** The platform's code contract: DNS-label-shaped up to 63 chars — a
     * long hyphenated code (the story-e2e shape) becomes a live tenant with
     * a Postgres-safe, deterministically hash-suffixed database name. */
    @Test
    @Order(9)
    void aLongHyphenatedCodeBecomesALiveTenant() throws Exception {
        String code = "e2e-us-xapi-distributor-onboards-customer-20260815-233454-8knsh";
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal"}]}""".formatted(code));
        assertTrue(manager.scanOnce().contains(code));
        assertEquals(200, get(manager.baseUrl(code) + "/metadata").statusCode());
        assertTrue(cloud.jengu.dbo.tenant.TenantSpec.databaseName(code).length() <= 63);
        // deterministic: same code, same name, every derivation
        assertEquals(cloud.jengu.dbo.tenant.TenantSpec.databaseName(code),
                cloud.jengu.dbo.tenant.TenantSpec.databaseName(code));
    }

    /** Two tenants, two FHIR versions, one port — fully isolated. */
    @Test
    @Order(2)
    void twoTenantsServeConcurrentlyIsolated() throws Exception {
        Files.writeString(dir.resolve("teine.json"), """
                {"code":"teine","fhirVersion":"r5","types":[
                  {"name":"SubscriptionTopic","identity":"canonical"},
                  {"name":"Patient","identity":"internal"}]}""");
        assertEquals(java.util.Set.of("aiakas", "teine"), manager.scanOnce());

        String teine = manager.baseUrl("teine");
        assertEquals(201, post(teine + "/SubscriptionTopic", """
                {"resourceType":"SubscriptionTopic","status":"active",
                 "url":"https://dbo.test/topics/tenant"}""").statusCode());
        assertTrue(get(teine + "/metadata").body().contains("\"5.0.0\""));

        // isolation: aiakas's patient is invisible in teine's world
        assertEquals(0, get(teine + "/Patient?_summary=count").body().contains("\"total\":0") ? 0 : 1);
        assertTrue(get(manager.baseUrl("aiakas") + "/metadata").body().contains("\"4.0.1\""));
    }

    /** dbo#18 R3: provisioned databases carry the liveness timeouts. */
    @Test
    @Order(2)
    void provisionedDatabasesCarryTimeouts() throws Exception {
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement("""
                     SELECT s.setconfig FROM pg_db_role_setting s
                     JOIN pg_database d ON d.oid = s.setdatabase
                     WHERE d.datname = 'tenant_aiakas' AND s.setrole = 0""");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "per-database settings expected");
            String config = java.util.Arrays.toString((String[]) rs.getArray(1).getArray());
            assertTrue(config.contains("idle_in_transaction_session_timeout=60s"), config);
            assertTrue(config.contains("transaction_timeout=300s"), config);
        }
    }

    /** Retract ≠ erase: spec removal takes the endpoint down; re-adding finds the data intact. */
    @Test
    @Order(3)
    void retractIsNotErase() throws Exception {
        Files.delete(dir.resolve("aiakas.json"));
        assertEquals(java.util.Set.of("teine"), manager.scanOnce());
        assertEquals(404, get(manager.baseUrl("aiakas") + "/metadata").statusCode());
        // teine unaffected
        assertEquals(200, get(manager.baseUrl("teine") + "/metadata").statusCode());

        Files.writeString(dir.resolve("aiakas.json"), specA());
        manager.scanOnce();
        String found = get(manager.baseUrl("aiakas") + "/Patient?identifier="
                + java.net.URLEncoder.encode(EID + "|38001010001",
                        java.nio.charset.StandardCharsets.UTF_8)).body();
        assertTrue(found.contains("Aiakas"), "data must survive retract + reattach");
    }

    /** Erasure is only the explicit deprovision: the database is dropped. */
    @Test
    @Order(4)
    void deprovisionDropsTheDatabase() throws Exception {
        Files.delete(dir.resolve("aiakas.json"));
        manager.scanOnce();
        provisioner.deprovision("aiakas");

        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname = 'tenant_aiakas'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getLong(1), "erasure-by-drop must remove the database");
        }
        assertFalse(manager.codes().contains("aiakas"));
    }
}
