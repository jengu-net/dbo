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

/** Spec files become live tenants; retract ≠ erase; deprovision drops. */
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
        provisioner.close();
    }

    private static String specA() {
        return """
                {"code":"aiakas","fhirVersion":"r4","types":[
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

    /** The platform's code contract: DNS-label-shaped up to 63 chars — a
     * long hyphenated code (the story-e2e shape) becomes a live tenant with
     * a Postgres-safe, deterministically hash-suffixed database name. */
    @Test
    @Order(9)
    void aLongHyphenatedCodeBecomesALiveTenant() throws Exception {
        String code = "e2e-us-xapi-distributor-onboards-customer-20260815-233454-8knshjjg";
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""".formatted(code));
        assertTrue(UntilServed.scan(manager, code).contains(code));
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
                  {"name":"SubscriptionTopic","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        // Scanned until served, not once: scanOnce answers with what is BEING
        // SERVED, and a pass brings up what it can in whatever order the
        // filesystem hands back. The runtime promises the wait ends, not that
        // one pass ends it -- so asserting on a single pass asserts something
        // stronger than the promise, and it failed on CI exactly that way,
        // with teine absent from an otherwise correct set.
        assertEquals(java.util.Set.of("aiakas", "teine"),
                UntilServed.scan(manager, "aiakas", "teine"));

        String teine = manager.baseUrl("teine");
        assertEquals(201, post(teine + "/SubscriptionTopic", """
                {"resourceType":"SubscriptionTopic","status":"active",
                 "url":"https://dbo.test/topics/tenant"}""").statusCode());
        assertTrue(get(teine + "/metadata").body().contains("\"5.0.0\""));

        // isolation: aiakas's patient is invisible in teine's world
        assertEquals(0, get(teine + "/Patient?_summary=count").body().contains("\"total\":0") ? 0 : 1);
        assertTrue(get(manager.baseUrl("aiakas") + "/metadata").body().contains("\"4.0.1\""));
    }

    /**
     * REQ-DBO-TERM-EVERY-TENANT-ANSWERS — a tenant answers terminology from its
     * own store, whichever FHIR version it speaks.
     *
     * <p>Both halves of the gap are here. The runtime used to pass no facade at
     * all, so the operations 404'd; and a CodeSystem posted the ordinary way is
     * stored whole and answers nothing, which is worse than a 404 because the
     * resource is plainly there. Posting through the endpoint and then asking
     * the endpoint is the only arrangement that proves neither is true any more.
     */
    @Test
    @Order(8)
    void everyTenantAnswersTerminologyFromItsOwnStore() throws Exception {
        Files.writeString(dir.resolve("terms4.json"), """
                {"code":"terms4","fhirVersion":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}""");
        Files.writeString(dir.resolve("terms5.json"), """
                {"code":"terms5","fhirVersion":"r5","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}""");
        manager.scanOnce();

        for (String code : java.util.List.of("terms4", "terms5")) {
            String base = manager.baseUrl(code);
            String system = "https://terms.dbo.test/" + code;

            assertEquals(201, post(base + "/CodeSystem", """
                    {"resourceType":"CodeSystem","url":"%s","version":"1","status":"active",
                     "content":"complete","concept":[
                       {"code":"a","display":"Alpha"},
                       {"code":"b","display":"Beta"}]}""".formatted(system)).statusCode(),
                    code + " refused the CodeSystem");
            assertEquals(201, post(base + "/ValueSet", """
                    {"resourceType":"ValueSet","url":"%s/vs","version":"1","status":"active",
                     "compose":{"include":[{"system":"%s"}]}}"""
                    .formatted(system, system)).statusCode(), code + " refused the ValueSet");

            String lookup = get(base + "/CodeSystem/$lookup?system=" + system + "&code=a").body();
            assertTrue(lookup.contains("Alpha"),
                    code + " cannot look up a code it was given: " + lookup);

            String expansion = get(base + "/ValueSet/$expand?url=" + system + "/vs").body();
            assertTrue(expansion.contains("\"total\":2"),
                    code + " expanded to something other than the two concepts: " + expansion);
            assertTrue(expansion.contains("Beta"), expansion);
        }

        // and the concepts are the tenant's own: terms5 knows nothing of terms4's
        String foreign = get(manager.baseUrl("terms5")
                + "/CodeSystem/$lookup?system=https://terms.dbo.test/terms4&code=a").body();
        assertFalse(foreign.contains("Alpha"),
                "one tenant answered from another tenant's concepts: " + foreign);

        // this test brought its own tenants; the ones after it count what is
        // served, so it takes them away again
        Files.delete(dir.resolve("terms4.json"));
        Files.delete(dir.resolve("terms5.json"));
        manager.scanOnce();
    }

    /** Provisioned databases carry the liveness timeouts. */
    @Test
    @Order(2)
    void provisionedDatabasesCarryTimeouts() throws Exception {
        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
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
     * A version is served because a face is installed, not because a validator
     * was taught to accept the string (R6).
     *
     * <p>The spec no longer refuses an unknown version — which is what let it
     * reject R6 by the name of the requirement asking for it — so the answer
     * moves to where it is known. A tenant declaring a face this container does
     * not have simply does not come up, and the one beside it does, which is
     * how the test tells "refused" apart from "the scan did not get to it".
     */
    @Test
    @Order(5)
    void aTenantOnAnUninstalledFaceDoesNotComeUp() throws Exception {
        Files.writeString(dir.resolve("olemas.json"), """
                {"code":"olemas","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        Files.writeString(dir.resolve("puudub.json"), """
                {"code":"puudub","fhirVersion":"kuues","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");

        UntilServed.scan(manager, up -> up.contains("olemas"));

        assertTrue(manager.runtime("olemas").isPresent(),
                "a tenant on an installed face must come up");
        assertTrue(manager.runtime("puudub").isEmpty(),
                "a tenant on a face nothing provides must not be served by another one");
        // and nothing was created for it: the version is resolved before the
        // database is provisioned, so a refusal leaves nothing to clean up
        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname = 'tenant_puudub'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getLong(1),
                    "a refused tenant must not leave a provisioned database behind");
        }

        Files.delete(dir.resolve("olemas.json"));
        Files.delete(dir.resolve("puudub.json"));
        manager.scanOnce();
        provisioner.deprovision("olemas");
    }
}
