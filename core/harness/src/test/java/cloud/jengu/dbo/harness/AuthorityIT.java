package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13: every tenant is its own OIDC authority — discovery, JWKS,
 * client_credentials tokens; the store surface accepts ONLY that tenant's
 * tokens; scopes use the SMART system grammar; rotation keeps old tokens
 * verifying; identity records are unreachable through the FHIR surface.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorityIT {

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static byte[] kek = new byte[32];
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("AuthorityIT");
        new SecureRandom().nextBytes(kek);
        dir = Files.createTempDirectory("dbo-authority");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("yks.json"), """
                {"code":"yks","fhirVersion":"r4","types":[{"name":"Patient","identity":"internal","handling":"operational"}]}""");
        Files.writeString(dir.resolve("kaks.json"), """
                {"code":"kaks","fhirVersion":"r4","types":[{"name":"Patient","identity":"internal","handling":"operational"}]}""");
        manager.scanOnce();
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    private String oidc(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code + "/oidc";
    }

    private String fhir(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code + "/fhir";
    }

    private HttpResponse<String> get(String url, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String code, String scope) throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap"
                + "&client_secret=" + URLEncoder.encode(
                        provisioner.bootstrapClientSecret(code), StandardCharsets.UTF_8)
                + (scope == null ? "" : "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(oidc(code) + "/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(m.find());
        return m.group(1);
    }

    /** Each tenant's discovery names ITS OWN issuer and serves its own keys. */
    @Test
    @Order(1)
    void eachTenantIsItsOwnIssuer() throws Exception {
        HttpResponse<String> yks = get(oidc("yks") + "/.well-known/openid-configuration", null);
        assertEquals(200, yks.statusCode());
        assertTrue(yks.body().contains("\"issuer\":\"http://127.0.0.1:" + manager.port() + "/t/yks/oidc\""));
        HttpResponse<String> jwks = get(oidc("yks") + "/.well-known/jwks.json", null);
        assertTrue(jwks.body().contains("\"kty\":\"RSA\""));
        // the two tenants publish DIFFERENT keys
        assertNotEquals(jwks.body(), get(oidc("kaks") + "/.well-known/jwks.json", null).body());
    }

    /** Anonymous metadata declares the auth mode; everything else requires a bearer token. */
    @Test
    @Order(2)
    void theStoreSurfaceRequiresThisTenantsToken() throws Exception {
        HttpResponse<String> metadata = get(fhir("yks") + "/metadata", null);
        assertEquals(200, metadata.statusCode());
        assertTrue(metadata.body().contains("\"security\""), "capability must declare security");

        HttpResponse<String> denied = get(fhir("yks") + "/Patient?_summary=count", null);
        assertEquals(401, denied.statusCode());
        assertEquals("Bearer", denied.headers().firstValue("WWW-Authenticate").orElse(null));

        assertEquals(200, get(fhir("yks") + "/Patient?_summary=count", token("yks", null)).statusCode());
    }

    /** §13's core claim: a cross-tenant token dies at signature verification. */
    @Test
    @Order(3)
    void aCrossTenantTokenFailsAtVerification() throws Exception {
        String yksToken = token("yks", null);
        HttpResponse<String> denied = get(fhir("kaks") + "/Patient?_summary=count", yksToken);
        assertEquals(401, denied.statusCode());
        assertTrue(denied.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_token"));
    }

    /** SMART system scopes govern read vs write, down to the type. */
    @Test
    @Order(4)
    void scopesGovernReadAndWrite() throws Exception {
        String readOnly = token("yks", "system/*.read");
        assertEquals(200, get(fhir("yks") + "/Patient?_summary=count", readOnly).statusCode());
        HttpResponse<String> refused = post(fhir("yks") + "/Patient", readOnly,
                "{\"resourceType\":\"Patient\"}");
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("insufficient scope"));

        assertEquals(201, post(fhir("yks") + "/Patient", token("yks", null),
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Volitatud\"}]}").statusCode());
    }

    /** Rotation: old tokens verify until the old key is retired from publication. */
    @Test
    @Order(5)
    void rotationKeepsOldTokensVerifying() throws Exception {
        String before = token("yks", null);
        TenantAuthority side = new TenantAuthority(
                new PgObjectStore(provisioner.provision(TenantSpec.parse(
                        Files.readString(dir.resolve("yks.json")))).dataSource(),
                        IdentityModel.registrations()),
                oidc("yks"), new KeyProtector(kek));
        side.rotateSigningKey();
        assertEquals(200, get(fhir("yks") + "/Patient?_summary=count", before).statusCode(),
                "pre-rotation token must verify against the retired key");
        String after = token("yks", null);
        assertEquals(200, get(fhir("yks") + "/Patient?_summary=count", after).statusCode(),
                "post-rotation token must verify via unknown-kid refresh");
    }

    /** §14 wired end-to-end: a pdi tenant serves reassembled resources over REST
     *  while the stored payload is ciphertext. */
    @Test
    @Order(7)
    void aPdiTenantServesReassembledResourcesOverCiphertextStorage() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("kolm.json"), """
                {"code":"kolm","fhirVersion":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],"handling":"operational"}]}""".formatted(
                "https://eesti.ee/isikukood"));
        manager.scanOnce();
        String token = token("kolm", null);
        HttpResponse<String> created = post(fhir("kolm") + "/Patient", token, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://eesti.ee/isikukood","value":"49001010062"}],
                 "name":[{"family":"Peidetud"}]}""");
        assertEquals(201, created.statusCode(), created.body());
        // authorized read: fully reassembled (id from the Location header)
        String location = created.headers().firstValue("Location").orElseThrow();
        String id = location.replaceAll(".*/Patient/([^/]+).*", "$1");
        String read = get(fhir("kolm") + "/Patient/" + id, token).body();
        assertTrue(read.contains("Peidetud") && read.contains("49001010062"));
        assertFalse(read.contains("__pdiEnc"));
        // storage: ciphertext only
        try (java.sql.Connection c = provisioner.provision(cloud.jengu.dbo.tenant.TenantSpec.parse(
                        java.nio.file.Files.readString(dir.resolve("kolm.json")))).dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state.r4_data WHERE convert_from(payload,'UTF8') LIKE ?")) {
            ps.setString(1, "%Peidetud%");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "plaintext name must not reach storage");
            }
        }
    }

    /** §15 over REST: an append-only tenant answers DELETE with a policy-naming
     *  OperationOutcome; the capability statement names the declared posture. */
    @Test
    @Order(8)
    void appendOnlyTenantRefusesDeleteOverRest() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("neli.json"), """
                {"code":"neli","fhirVersion":"r4",
                 "audit":{"level":"writes"},
                 "writeDiscipline":{"default":"append-only"},
                 "types":[{"name":"Patient","identity":"internal","handling":"operational"}]}""");
        manager.scanOnce();
        String token = token("neli", null);
        HttpResponse<String> created = post(fhir("neli") + "/Patient", token,
                "{\"resourceType\":\"Patient\"}");
        assertEquals(201, created.statusCode());
        String id = created.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/Patient/([^/]+).*", "$1");

        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create(fhir("neli") + "/Patient/" + id))
                        .header("Authorization", "Bearer " + token)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, refused.statusCode());
        assertTrue(refused.body().contains("append-only"), refused.body());

        assertTrue(get(fhir("neli") + "/metadata", null).body()
                .contains("writeDiscipline=append-only"), "capability declares the posture");

        // and the delete ATTEMPT's create was audited with the token's client
        // (audit domain rides the same store; check via the engine service? REST
        // doesn't serve AuditEntry — assert through the feed instead)
        assertTrue(new cloud.jengu.dbo.postgres.PgChangeFeed(
                        provisioner.provision(cloud.jengu.dbo.tenant.TenantSpec.parse(
                                java.nio.file.Files.readString(dir.resolve("neli.json")))).dataSource(),
                        cloud.jengu.dbo.policy.AuditModel.DOMAIN)
                .read(null, 10).items().stream()
                .map(i -> new String(i.payload(), java.nio.charset.StandardCharsets.UTF_8))
                .anyMatch(e -> e.contains("\"actor\":\"tenant-bootstrap\"")
                        && e.contains("\"interaction\":\"create\"")),
                "the create must be audited with the token's client as actor");
    }

    /** The trail over REST, as AuditEvent — readable, contributable,
     *  impersonation-proof, and never deletable. */
    @Test
    @Order(9)
    void theTrailIsServedAsAuditEventAndCannotBeForged() throws Exception {
        String token = token("neli", null);
        // the create from the append-only test is in the trail as action C
        String bundle = get(fhir("neli") + "/AuditEvent", token).body();
        assertTrue(bundle.contains("\"resourceType\":\"AuditEvent\"")
                && bundle.contains("\"action\":\"C\"")
                && bundle.contains("tenant-bootstrap"), bundle);

        // a posted event claiming another agent and an old recorded time is
        // re-stamped by the machinery — enrichable, never impersonable
        HttpResponse<String> posted = post(fhir("neli") + "/AuditEvent", token, """
                {"resourceType":"AuditEvent",
                 "type":{"system":"urn:example","code":"report-released"},
                 "recorded":"1999-01-01T00:00:00Z",
                 "agent":[{"who":{"display":"evil-impostor"},"requestor":true}],
                 "entity":[{"what":{"reference":"DocumentReference/doc-9"}}]}""");
        assertEquals(201, posted.statusCode(), posted.body());
        assertTrue(posted.body().contains("\"code\":\"report-released\"")
                && posted.body().contains("tenant-bootstrap")
                && posted.body().contains("DocumentReference/doc-9"), posted.body());
        assertFalse(posted.body().contains("evil-impostor"), "claimed agent must be ignored");
        assertFalse(posted.body().contains("1999-01-01"), "claimed time must be ignored");

        // the trail cannot be deleted, under any discipline
        String id = posted.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertEquals(200, get(fhir("neli") + "/AuditEvent/" + id, token).statusCode());
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create(fhir("neli") + "/AuditEvent/" + id))
                        .header("Authorization", "Bearer " + token)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, refused.statusCode());
        assertTrue(refused.body().contains("append-only"), refused.body());

        // scope gating: read-only tokens read but cannot contribute
        String readOnly = token("neli", "system/*.read");
        assertEquals(200, get(fhir("neli") + "/AuditEvent", readOnly).statusCode());
        assertEquals(403, post(fhir("neli") + "/AuditEvent", readOnly,
                "{\"resourceType\":\"AuditEvent\"}").statusCode());
    }

    /** Identity artifacts are records, not FHIR surface types — unreachable via REST. */
    @Test
    @Order(6)
    void identityRecordsAreNotOnTheFhirSurface() throws Exception {
        HttpResponse<String> response = get(fhir("yks") + "/ClientApplication", token("yks", null));
        assertNotEquals(200, response.statusCode(), "identity records must not be served");
    }
}
