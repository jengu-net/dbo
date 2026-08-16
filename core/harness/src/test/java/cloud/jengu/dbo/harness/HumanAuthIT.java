package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.pdi.PdiObjectStore;
import cloud.jengu.dbo.pdi.PdiSetup;
import cloud.jengu.dbo.pdi.PdiSpec;
import cloud.jengu.dbo.pdi.PersonVault;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#24 (§16): the org model is the auth model — a human's access derives
 * from Practitioner + active PractitionerRole + RoleGrant records; the flow
 * is authorization-code + PKCE against the tenant's own authority; tokens
 * are pseudonymous; revocation is ending a period on a clinical record.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HumanAuthIT {

    static final String EID = "https://eesti.ee/isikukood";
    static final String REDIRECT = "http://127.0.0.1/cb";

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
    static String roleId;
    static String verifier;
    static String accessToken;
    static String refreshToken;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("HumanAuthIT");
        new SecureRandom().nextBytes(kek);
        dir = Files.createTempDirectory("dbo-human");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        provisioner.rpRedirectUris(List.of(REDIRECT));
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("arst.json"), """
                {"code":"arst","fhirVersion":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Patient","identity":"internal"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"]},
                  {"name":"PractitionerRole","identity":"internal"},
                  {"name":"Encounter","identity":"internal"}]}""".formatted(EID));
        manager.scanOnce();

        // seed the clinical side over REST with the service token
        String service = serviceToken("arst");
        HttpResponse<String> practitioner = post("arst", "/Practitioner", service, """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"%s","value":"37001010021"}],
                 "name":[{"family":"Albus"}]}""".formatted(EID));
        assertEquals(201, practitioner.statusCode());
        practitionerId = idOf(practitioner);
        HttpResponse<String> role = post("arst", "/PractitionerRole", service, """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:jengu:role","code":"doctor"}]}]}"""
                .formatted(practitionerId));
        assertEquals(201, role.statusCode(), role.body());
        roleId = idOf(role);

        // identity-side records via a side authority over the same database
        org.postgresql.ds.PGSimpleDataSource ds = tenantDs("arst");
        sideAuthority = new TenantAuthority(
                new PgObjectStore(ds, IdentityModel.registrations()),
                "http://127.0.0.1:" + manager.port() + "/t/arst/oidc", new KeyProtector(kek));
        sideAuthority.ensureRoleGrant("doctor", List.of("user/*.read", "user/Encounter.write"));
        sideAuthority.ensureLocalCredential("albus", "kaljuke9", practitionerId);
        sideAuthority.ensureClient("webapp", null, List.of("user/*.read", "user/*.write"),
                "public-pkce", List.of(REDIRECT));
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    private static org.postgresql.ds.PGSimpleDataSource tenantDs(String code) throws Exception {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        ds.setUrl(base + "tenant_" + code);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private String base(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code;
    }

    private String serviceToken(String code) throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(provisioner.bootstrapClientSecret(code), StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(URI.create(base(code) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private HttpResponse<String> post(String code, String path, String token, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(code) + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(HttpResponse<String> created) {
        return created.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
    }

    private static String claimsOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    /** The whole front door: form → code → PKCE exchange → pseudonymous tokens. */
    @Test
    @Order(1)
    void authorizationCodeFlowMintsPseudonymousUserTokens() throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        HttpResponse<String> form = http.send(HttpRequest.newBuilder(URI.create(base("arst")
                        + "/oidc/authorize?response_type=code&client_id=webapp&state=xyz"
                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                        + "&code_challenge=" + challenge + "&code_challenge_method=S256")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, form.statusCode());
        assertTrue(form.body().contains("authorize/login"));

        HttpResponse<String> login = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/authorize/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=webapp&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&state=xyz&code_challenge=" + challenge
                                        + "&login=albus&password=kaljuke9")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), login.body());
        String location = login.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith(REDIRECT) && location.contains("state=xyz"), location);
        String code = location.replaceAll(".*code=([^&]+).*", "$1");

        HttpResponse<String> tokens = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code&client_id=webapp&code=" + code
                                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&code_verifier=" + verifier)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, tokens.statusCode(), tokens.body());
        accessToken = tokens.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        refreshToken = tokens.body().replaceAll(".*\"refresh_token\":\"([^\"]+)\".*", "$1");

        String claims = claimsOf(accessToken);
        assertTrue(claims.contains("\"fhirUser\":\"Practitioner/" + practitionerId + "\""), claims);
        assertTrue(claims.contains("\"roles\":[\"doctor\"]"), claims);
        assertTrue(claims.contains("user/*.read") && claims.contains("user/Encounter.write"), claims);
        // pseudonymous: no name, no national code, anywhere in the token
        assertFalse(claims.contains("Albus") || claims.contains("37001010021"), claims);
    }

    /** The grant governs the surface; the audit trail names the human. */
    @Test
    @Order(2)
    void userScopesGovernTheSurfaceAndAuditNamesTheHuman() throws Exception {
        assertEquals(200, http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/fhir/Patient?_summary=count"))
                        .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(201, post("arst", "/Encounter", accessToken,
                "{\"resourceType\":\"Encounter\",\"status\":\"planned\",\"class\":{\"code\":\"AMB\"}}")
                .statusCode());
        assertEquals(403, post("arst", "/Patient", accessToken,
                "{\"resourceType\":\"Patient\"}").statusCode(),
                "doctor grant has no Patient.write");

        String trail = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/fhir/AuditEvent?action=C"))
                        .header("Authorization", "Bearer " + serviceToken("arst")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(trail.contains("Practitioner/" + practitionerId),
                "the audit actor is the human's pseudonym");
    }

    /** Wrong PKCE verifier and unregistered redirect targets are refused. */
    @Test
    @Order(3)
    void pkceAndRedirectValidationHold() throws Exception {
        HttpResponse<String> badRedirect = http.send(HttpRequest.newBuilder(URI.create(base("arst")
                        + "/oidc/authorize?response_type=code&client_id=webapp"
                        + "&redirect_uri=" + URLEncoder.encode("http://evil.example/cb", StandardCharsets.UTF_8)
                        + "&code_challenge=x&code_challenge_method=S256")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, badRedirect.statusCode());
        assertTrue(badRedirect.headers().firstValue("Location").isEmpty(), "never redirect to evil");

        // fresh code, wrong verifier
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/authorize/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=webapp&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&code_challenge=" + Base64.getUrlEncoder().withoutPadding()
                                                .encodeToString(MessageDigest.getInstance("SHA-256")
                                                        .digest("other".getBytes(StandardCharsets.US_ASCII)))
                                        + "&login=albus&password=kaljuke9")).build(),
                HttpResponse.BodyHandlers.ofString());
        String code = login.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*code=([^&]+).*", "$1");
        HttpResponse<String> exchange = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code&client_id=webapp&code=" + code
                                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&code_verifier=not-the-verifier")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, exchange.statusCode());
        assertTrue(exchange.body().contains("invalid_grant"));
    }

    /** Revocation is ending a period on a clinical record — refresh honours it. */
    @Test
    @Order(4)
    void endingThePractitionerRolePeriodRevokesAccess() throws Exception {
        String service = serviceToken("arst");
        HttpResponse<String> ended = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/fhir/PractitionerRole/" + roleId))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/fhir+json")
                        .header("If-Match", "W/\"1\"")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"PractitionerRole","id":"%s",
                                 "practitioner":{"reference":"Practitioner/%s"},
                                 "code":[{"coding":[{"system":"urn:jengu:role","code":"doctor"}]}],
                                 "period":{"end":"%s"}}""".formatted(roleId, practitionerId,
                                java.time.LocalDate.now().minusDays(1)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ended.statusCode(), ended.body());

        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=refresh_token&refresh_token=" + refreshToken)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("access_denied"), refused.body());
    }

    /** Slice L: the operator-custody RP client (confidential) completes the code flow. */
    @Test
    @Order(5)
    void theProvisionedRpClientCompletesAConfidentialFlow() throws Exception {
        // fresh grant: the earlier revocation test ended the role — restore access
        String service = serviceToken("arst");
        assertEquals(201, http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/fhir/PractitionerRole"))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"PractitionerRole",
                                 "practitioner":{"reference":"Practitioner/%s"},
                                 "code":[{"coding":[{"system":"urn:jengu:role","code":"doctor"}]}]}"""
                                .formatted(practitionerId))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpResponse<String> login = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/authorize/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=jengu-cloud&redirect_uri="
                                        + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&nonce=n-0xSpr1ng&login=albus&password=kaljuke9")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), login.body());
        String code = login.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*code=([^&]+).*", "$1");
        HttpResponse<String> tokens = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code&client_id=jengu-cloud&code=" + code
                                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&client_secret=" + URLEncoder.encode(
                                                provisioner.rpClientSecret("arst"), StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, tokens.statusCode(), tokens.body());
        String claims = claimsOf(tokens.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1"));
        assertTrue(claims.contains("\"roles\":[\"doctor\"]")
                && claims.contains("\"client_id\":\"jengu-cloud\""), claims);

        // OIDC proper: the id_token is what the RP builds its principal from —
        // audience is the CLIENT, the nonce echoes, roles + fhirUser ride along
        assertTrue(tokens.body().contains("\"id_token\""), tokens.body());
        String idClaims = claimsOf(tokens.body().replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1"));
        assertTrue(idClaims.contains("\"aud\":\"jengu-cloud\"")
                && idClaims.contains("\"nonce\":\"n-0xSpr1ng\"")
                && idClaims.contains("\"roles\":[\"doctor\"]")
                && idClaims.contains("\"fhirUser\":\"Practitioner/" + practitionerId + "\""), idClaims);
    }

    /** #847: the provisioning surface — bootstrap writes grants and credentials over REST. */
    @Test
    @Order(7)
    void theAdminSurfaceProvisionsGrantsAndCredentialsOverRest() throws Exception {
        String service = serviceToken("arst");

        // a brand-new role + a credential for the existing practitioner
        assertEquals(200, http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/admin/role-grants"))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"role\":\"nurse\",\"scopes\":[\"user/*.read\"]}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/admin/credentials"))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"login\":\"poppy\",\"secret\":\"pomfrey8\",\"practitionerId\":\""
                                        + practitionerId + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        // the provisioned credential signs in through the front channel
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/authorize/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=jengu-cloud&redirect_uri="
                                        + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&login=poppy&password=pomfrey8")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), login.body());

        // anonymous 401; a HUMAN token (user plane) is refused 403
        assertEquals(401, http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/admin/role-grants"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"role\":\"x\",\"scopes\":[\"user/*.read\"]}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        String humanToken = codeFlowAccessToken();
        assertEquals(403, http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/admin/role-grants"))
                        .header("Authorization", "Bearer " + humanToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"role\":\"x\",\"scopes\":[\"user/*.read\"]}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private String codeFlowAccessToken() throws Exception {
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(
                        URI.create(base("arst") + "/oidc/authorize/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=jengu-cloud&redirect_uri="
                                        + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&login=albus&password=kaljuke9")).build(),
                HttpResponse.BodyHandlers.ofString());
        String code = login.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*code=([^&]+).*", "$1");
        String body = http.send(HttpRequest.newBuilder(URI.create(base("arst") + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code&client_id=jengu-cloud&code=" + code
                                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&client_secret=" + URLEncoder.encode(
                                                provisioner.rpClientSecret("arst"), StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    /** §16.1: subject resolution works identically under PDI — through the vault. */
    @Test
    @Order(8)
    void nationalIdResolvesThePractitionerUnderPdiAndWithout() throws Exception {
        // plain tenant: envelope identifier
        R4Personality plain = new R4Personality(TenantSpec.parse(
                Files.readString(dir.resolve("arst.json"))).types());
        sideAuthority.attachSubjects(new PgObjectStore(tenantDs("arst"), plain.registrations()));
        assertEquals(practitionerId,
                sideAuthority.resolveByNationalId(EID, "37001010021").orElseThrow());

        // pdi tenant: the vault's HMAC index
        Files.writeString(dir.resolve("arstp.json"), """
                {"code":"arstp","fhirVersion":"r4","pdi":true,"types":[
                  {"name":"Practitioner","identity":"identifier","systems":["%s"]}]}""".formatted(EID));
        manager.scanOnce();
        HttpResponse<String> created = post("arstp", "/Practitioner", serviceToken("arstp"), """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"%s","value":"48001010030"}],
                 "name":[{"family":"Peidetud"}]}""".formatted(EID));
        assertEquals(201, created.statusCode(), created.body());

        PdiSpec pdiSpec = PdiSpec.fhir();
        R4Personality personality = new R4Personality(TenantSpec.parse(
                Files.readString(dir.resolve("arstp.json"))).types());
        org.postgresql.ds.PGSimpleDataSource ds = tenantDs("arstp");
        TenantAuthority pdiSide = new TenantAuthority(
                new PgObjectStore(ds, IdentityModel.registrations()),
                base("arstp") + "/oidc", new KeyProtector(kek));
        pdiSide.attachSubjects(new PdiObjectStore(
                new PgObjectStore(ds, PdiSetup.transform(personality.registrations(), pdiSpec)),
                new PersonVault(ds, kek), pdiSpec));
        assertEquals(idOf(created),
                pdiSide.resolveByNationalId(EID, "48001010030").orElseThrow(),
                "resolution through the vault's HMAC index");
    }
}
