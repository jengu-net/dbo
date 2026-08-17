package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.postgres.PgObjectStore;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#25 (§16.4): processes act IN THE NAME OF practitioners — live
 * delegation via RFC 8693 token exchange (act chains, attenuation), durable
 * delegation via Delegation records that outlive tokens, honour revocation,
 * and never widen with later grants. Every delegated mutation is
 * attributable to both the process and the person.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DelegationIT {

    static final String EID = "https://eesti.ee/isikukood";
    static final String REDIRECT = "http://127.0.0.1/cb";
    static final String ENGINE_SECRET = "engine-salajane-32-taht";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static byte[] kek = new byte[32];
    static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static TenantAuthority sideAuthority;
    static String practitionerId;
    static String personId;
    static String roleId;
    static String humanToken;
    static String delegationId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("DelegationIT");
        new SecureRandom().nextBytes(kek);
        dir = Files.createTempDirectory("dbo-delegation");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("esindus.json"), """
                {"code":"esindus","fhirVersion":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"},
                  {"name":"Encounter","identity":"internal","handling":"operational"}]}""".formatted(EID, EID));
        manager.scanOnce();

        String service = serviceToken();
        practitionerId = idOf(fhirPost("/Practitioner", service, """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"%s","value":"36001010009"}],
                 "name":[{"family":"Volitaja"}]}""".formatted(EID)));
        personId = idOf(fhirPost("/Person", service, """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"36001010009"}],
                 "name":[{"family":"Volitaja"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(EID, practitionerId)));
        roleId = idOf(fhirPost("/PractitionerRole", service, """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:jengu:role","code":"doctor"}]}]}"""
                .formatted(practitionerId)));

        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        String jdbcBase = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        ds.setUrl(jdbcBase + "tenant_esindus");
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        sideAuthority = new TenantAuthority(new PgObjectStore(ds, IdentityModel.registrations()),
                base() + "/oidc", new KeyProtector(kek));
        sideAuthority.ensureRoleGrant("doctor", List.of("user/*.read", "user/Encounter.write"));
        sideAuthority.ensureLocalCredential("volitaja", "salakala8", personId);
        sideAuthority.ensureClient("webapp", null, List.of("user/*.read", "user/*.write"),
                "public-pkce", List.of(REDIRECT));
        sideAuthority.ensureClient("engine", ENGINE_SECRET, List.of());

        humanToken = loginForToken();
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    private String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/esindus";
    }

    private String serviceToken() throws Exception {
        return tokenField(post(base() + "/oidc/token",
                "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                        + URLEncoder.encode(provisioner.bootstrapClientSecret("esindus"),
                                StandardCharsets.UTF_8)), "access_token");
    }

    private String loginForToken() throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpResponse<String> login = post(base() + "/oidc/authorize/login",
                "client_id=webapp&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                        + "&code_challenge=" + challenge + "&login=volitaja&password=salakala8");
        String code = login.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*code=([^&]+).*", "$1");
        return tokenField(post(base() + "/oidc/token",
                "grant_type=authorization_code&client_id=webapp&code=" + code
                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier), "access_token");
    }

    private HttpResponse<String> post(String url, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> fhirPost(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String tokenField(HttpResponse<String> response, String field) {
        assertEquals(200, response.statusCode(), response.body());
        return response.body().replaceAll(".*\"" + field + "\":\"([^\"]+)\".*", "$1");
    }

    private static String idOf(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        return created.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
    }

    private static String claimsOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    /** RFC 8693: the exchanged token chains the actor and attenuates the scopes. */
    @Test
    @Order(1)
    void liveExchangeAttenuatesAndChainsTheActor() throws Exception {
        HttpResponse<String> exchanged = post(base() + "/oidc/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange",
                        StandardCharsets.UTF_8)
                        + "&client_id=engine&client_secret=" + ENGINE_SECRET
                        + "&subject_token=" + humanToken
                        + "&scope=" + URLEncoder.encode("user/Encounter.write", StandardCharsets.UTF_8));
        String actToken = tokenField(exchanged, "access_token");
        String claims = claimsOf(actToken);
        assertTrue(claims.contains("\"sub\":\"" + personId + "\"")
                && claims.contains("\"act\":{\"sub\":\"engine\"}"), claims);
        assertTrue(claims.contains("\"fhirUser\":\"Practitioner/" + practitionerId + "\""),
                "sub is the human and fhirUser is the capacity they act in — SMART's own split, "
                        + "and the reason a delegated token can name three parties without "
                        + "conflating any of them: " + claims);
        assertTrue(claims.contains("user/Encounter.write") && !claims.contains("user/*.read"),
                "scopes attenuate to the request ∩ the subject's");

        // the delegated token can write Encounters but not read (attenuated away)
        assertEquals(201, fhirPost("/Encounter", actToken,
                "{\"resourceType\":\"Encounter\",\"status\":\"planned\",\"class\":{\"code\":\"AMB\"}}")
                .statusCode());
        assertEquals(403, http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/Patient?_summary=count"))
                        .header("Authorization", "Bearer " + actToken).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        // the audit trail names BOTH: the engine, on behalf of the human
        String trail = http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir/AuditEvent?action=C"))
                        .header("Authorization", "Bearer " + serviceToken()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(trail.contains("\"value\":\"engine\"")
                        && trail.contains("\"reference\":\"Practitioner/" + practitionerId + "\""),
                "both identities must be in the rendered AuditEvent");
    }

    /** A scope the human does not hold cannot be delegated. */
    @Test
    @Order(2)
    void exchangeCannotExceedTheHuman() throws Exception {
        HttpResponse<String> refused = post(base() + "/oidc/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange",
                        StandardCharsets.UTF_8)
                        + "&client_id=engine&client_secret=" + ENGINE_SECRET
                        + "&subject_token=" + humanToken
                        + "&scope=" + URLEncoder.encode("user/Patient.write", StandardCharsets.UTF_8));
        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("access_denied"), refused.body());
    }

    /** Durable delegation: exchanges without any subject token; capped by the RECORD even when grants widen. */
    @Test
    @Order(3)
    void durableDelegationOutlivesTokensAndNeverWidens() throws Exception {
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/oidc/delegation"))
                        .header("Authorization", "Bearer " + humanToken)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=engine&process_ref=workflow-42"
                                        + "&scope=" + URLEncoder.encode("user/Encounter.write", StandardCharsets.UTF_8)
                                        + "&valid_until=" + (System.currentTimeMillis() / 1000 + 3600))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        delegationId = created.body().replaceAll(".*\"delegation_id\":\"([^\"]+)\".*", "$1");

        // NO subject token in this exchange — the record authorizes
        String actToken = tokenField(post(base() + "/oidc/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange",
                        StandardCharsets.UTF_8)
                        + "&client_id=engine&client_secret=" + ENGINE_SECRET
                        + "&delegation_id=" + delegationId), "access_token");
        assertTrue(claimsOf(actToken).contains("\"act\":{\"sub\":\"engine\"}"));
        assertEquals(201, fhirPost("/Encounter", actToken,
                "{\"resourceType\":\"Encounter\",\"status\":\"planned\",\"class\":{\"code\":\"AMB\"}}")
                .statusCode());

        // widen the human's grant — the delegation must NOT widen with it
        sideAuthority.ensureRoleGrant("doctor",
                List.of("user/*.read", "user/Encounter.write", "user/Patient.write"));
        String widened = tokenField(post(base() + "/oidc/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange",
                        StandardCharsets.UTF_8)
                        + "&client_id=engine&client_secret=" + ENGINE_SECRET
                        + "&delegation_id=" + delegationId), "access_token");
        assertFalse(claimsOf(widened).contains("user/Patient.write"),
                "the recorded delegation caps the scope even after the grant widened");
    }

    /** Revocation both ways: ending the delegation, and ending the human's role. */
    @Test
    @Order(4)
    void revocationIsHonouredAfterTheFact() throws Exception {
        assertEquals(200, http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/oidc/delegation/" + delegationId))
                        .header("Authorization", "Bearer " + humanToken)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> refused = post(base() + "/oidc/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange",
                        StandardCharsets.UTF_8)
                        + "&client_id=engine&client_secret=" + ENGINE_SECRET
                        + "&delegation_id=" + delegationId);
        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("invalid_grant"), refused.body());

        // a fresh delegation dies with the human's role
        String fresh = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/delegation"))
                        .header("Authorization", "Bearer " + humanToken)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=engine&scope=" + URLEncoder.encode("user/Encounter.write",
                                        StandardCharsets.UTF_8)
                                        + "&valid_until=" + (System.currentTimeMillis() / 1000 + 3600))).build(),
                HttpResponse.BodyHandlers.ofString()).body().replaceAll(".*\"delegation_id\":\"([^\"]+)\".*", "$1");
        assertEquals(200, http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/PractitionerRole/" + roleId))
                        .header("Authorization", "Bearer " + serviceToken())
                        .header("Content-Type", "application/fhir+json")
                        .header("If-Match", "W/\"1\"")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"PractitionerRole","id":"%s",
                                 "practitioner":{"reference":"Practitioner/%s"},
                                 "code":[{"coding":[{"system":"urn:jengu:role","code":"doctor"}]}],
                                 "period":{"end":"%s"}}""".formatted(roleId, practitionerId,
                                java.time.LocalDate.now().minusDays(1)))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> denied = post(base() + "/oidc/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:token-exchange",
                        StandardCharsets.UTF_8)
                        + "&client_id=engine&client_secret=" + ENGINE_SECRET
                        + "&delegation_id=" + fresh);
        assertEquals(400, denied.statusCode());
        assertTrue(denied.body().contains("access_denied"),
                "the delegation cannot outlive the human's grants: " + denied.body());
    }
}
