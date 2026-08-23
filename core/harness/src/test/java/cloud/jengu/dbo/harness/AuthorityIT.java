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
        UntilServed.scan(manager, "yks", "kaks");
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
        return token(code, scope, null);
    }

    private String token(String code, String scope, String purpose) throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap"
                + "&client_secret=" + URLEncoder.encode(
                        provisioner.bootstrapClientSecret(code), StandardCharsets.UTF_8)
                + (scope == null ? "" : "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8))
                + (purpose == null ? ""
                        : "&purpose_of_use=" + URLEncoder.encode(purpose, StandardCharsets.UTF_8));
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
    void aPdiTenantDisclosesOnlyWhatTheTokenAsksFor() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("kolm.json"), """
                {"code":"kolm","fhirVersion":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],"handling":"operational"}]}""".formatted(
                "https://eesti.ee/isikukood"));
        UntilServed.scan(manager, "kolm");
        String token = token("kolm", null);
        HttpResponse<String> created = post(fhir("kolm") + "/Patient", token, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://eesti.ee/isikukood","value":"49001010062"}],
                 "name":[{"family":"Peidetud"}]}""");
        assertEquals(201, created.statusCode(), created.body());
        // authorized read: fully reassembled (id from the Location header)
        String location = created.headers().firstValue("Location").orElseThrow();
        String id = location.replaceAll(".*/Patient/([^/]+).*", "$1");
        // This token states no purpose, so it gets what any caller who says
        // nothing gets: the resource without its identity (#114). The default
        // is the strict one and a surface cannot leak by inaction.
        String read = get(fhir("kolm") + "/Patient/" + id, token).body();
        assertFalse(read.contains("Peidetud") || read.contains("49001010062"),
                "a caller that stated no purpose is not handed an identity: " + read);
        assertFalse(read.contains("__pdiEnc"),
                "nor the ciphertext block, which it cannot read: " + read);

        // A token that DOES state one discloses, and nothing new reached the
        // wire to say so — the purpose rides the token, as IUA carries it
        // (#117). The scopes are unchanged: this widens what is disclosed, not
        // what may be read.
        String forTreatment = get(fhir("kolm") + "/Patient/" + id,
                token("kolm", null, "TREAT")).body();
        assertTrue(forTreatment.contains("Peidetud") && forTreatment.contains("49001010062"),
                "a stated purpose and the same scopes gets the person: " + forTreatment);
        assertFalse(forTreatment.contains("__pdiEnc"), forTreatment);
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
        UntilServed.scan(manager, "neli");
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
        assertTrue(auditedBySomebody("neli", "\"actor\":\"tenant-bootstrap\"",
                        "\"interaction\":\"create\""),
                "the create must be audited with the token's client as actor");
    }

    /**
     * Whether ANY audit entry this tenant holds says all of these things.
     *
     * <p>Drained rather than sampled. This read the first ten entries and
     * asserted over those, which passed only because bring-up happened to
     * write fewer than ten before the interesting one — publishing four more
     * definitions moved the entry out of the window and the test failed
     * without anything about auditing having changed (#91). A trail is
     * append-only and a test that cares whether something is IN it must look
     * at all of it.
     */
    private boolean auditedBySomebody(String tenant, String... phrases) throws Exception {
        cloud.jengu.dbo.postgres.PgChangeFeed feed = new cloud.jengu.dbo.postgres.PgChangeFeed(
                provisioner.provision(cloud.jengu.dbo.tenant.TenantSpec.parse(
                        java.nio.file.Files.readString(dir.resolve(tenant + ".json")))).dataSource(),
                cloud.jengu.dbo.policy.AuditModel.DOMAIN);
        String cursor = null;
        for (var chunk = feed.read(null, 200); !chunk.items().isEmpty();
                chunk = feed.read(cursor, 200)) {
            for (var item : chunk.items()) {
                String entry = new String(item.payload(), java.nio.charset.StandardCharsets.UTF_8);
                if (java.util.Arrays.stream(phrases).allMatch(entry::contains)) {
                    return true;
                }
            }
            if (chunk.nextCursor() == null || chunk.nextCursor().equals(cursor)) {
                return false;
            }
            cursor = chunk.nextCursor();
        }
        return false;
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
                 "source":{"site":"neli","observer":{"display":"a-consumer"}},
                 "entity":[{"what":{"reference":"DocumentReference/doc-9"}}],
                 "extension":[{"url":"urn:example:received-at",
                               "valueInstant":"2026-01-01T00:00:00Z"}]}""");
        assertEquals(201, posted.statusCode(), posted.body());
        assertTrue(posted.body().contains("\"code\":\"report-released\"")
                && posted.body().contains("tenant-bootstrap")
                && posted.body().contains("DocumentReference/doc-9"), posted.body());
        assertFalse(posted.body().contains("evil-impostor"), "claimed agent must be ignored");
        assertFalse(posted.body().contains("1999-01-01"), "claimed time must be ignored");

        // An r4 face answers an r4 client in the client's own words (#90): what
        // was posted comes back, minus only the two facts the container owns.
        // Before this, the trail answered in dbo's vocabulary — urn:dbo:audit,
        // observer "dbo" — and everything else the poster said was discarded,
        // which made a consumer learn a second API to read its own events.
        assertTrue(posted.body().contains("urn:example"),
                "the poster's own coding system survives: " + posted.body());
        assertFalse(posted.body().contains("urn:dbo:audit"),
                "dbo does not rename what a domain said: " + posted.body());
        assertTrue(posted.body().contains("\"site\":\"neli\"")
                        && posted.body().contains("a-consumer"),
                "source is the poster's, not dbo's: " + posted.body());
        assertTrue(posted.body().contains("urn:example:received-at"),
                "extensions survive the round trip: " + posted.body());

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

        // The statement advertises what the trail actually filters on, and a
        // parameter it cannot honour is REFUSED rather than ignored. Before
        // this, the statement listed every AuditEvent parameter the version
        // defines and the surface honoured four — so ?type=X answered 200
        // with the whole trail, which a caller cannot tell from a real result
        // (REQ-DBO-SRCH-HONEST-CAPABILITY).
        String capability = get(fhir("neli") + "/metadata", null).body();
        String auditEntry = capability.substring(capability.indexOf("\"AuditEvent\""));
        auditEntry = auditEntry.substring(0, auditEntry.indexOf("]}") + 2);
        assertTrue(auditEntry.contains("\"agent\"") && auditEntry.contains("\"action\""),
                "the statement names what the trail filters on: " + auditEntry);
        assertFalse(auditEntry.contains("\"subtype\"") || auditEntry.contains("\"purpose\""),
                "and names nothing it does not: " + auditEntry);
        assertEquals(200, get(fhir("neli") + "/AuditEvent?action=C", token).statusCode(),
                "an advertised parameter filters");
        HttpResponse<String> unhonoured = get(fhir("neli") + "/AuditEvent?subtype=x", token);
        assertEquals(400, unhonoured.statusCode(),
                "a parameter the trail cannot honour is refused, not ignored: "
                        + unhonoured.body());

        // A bounded, ordered read is the query an audit page IS, and the
        // surface always did both internally — it just could not be asked.
        // Refusing a RESULT parameter was the wrong half of refuse-don't-
        // ignore: an ignored filter returns rows nobody asked for, while an
        // ignored bound returns more rows, never wrong ones (#92).
        assertTrue(capability.contains("\"_count\"") && capability.contains("\"_sort\""),
                "the statement declares the result parameters ONCE, at the server, because "
                        + "that is what they are: " + capability);
        String newestFirst = get(fhir("neli") + "/AuditEvent?_count=1&_sort=-date", token).body();
        assertEquals(1, newestFirst.split("\"resourceType\":\"AuditEvent\"", -1).length - 1,
                "_count bounds the page: " + newestFirst);
        assertTrue(newestFirst.contains("report-released"),
                "-date is newest first, and the contributed event is the newest: " + newestFirst);
        assertTrue(get(fhir("neli") + "/AuditEvent?_count=1&_sort=date", token).body()
                        .contains("\"action\":\"C\""),
                "and ascending is the other end of the same trail");

        // an ordering the trail cannot give is still refused, rather than
        // silently answered with the one it can
        assertEquals(400, get(fhir("neli") + "/AuditEvent?_sort=agent", token).statusCode());
        assertEquals(400, get(fhir("neli") + "/AuditEvent?_count=lots", token).statusCode());

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
