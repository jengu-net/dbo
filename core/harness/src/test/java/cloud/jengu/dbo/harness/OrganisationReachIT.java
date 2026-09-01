package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.postgres.PgObjectStore;
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
 * An organisation is an axis of authorization, not only of rule resolution
 *: a role grant may hold at one organisation, the token carries where
 * the grants were made, and what comes back over the surface is what the
 * caller may reach — a department's clinician does not read another
 * department's work, and cannot learn its ids from the difference between
 * 403 and 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrganisationReachIT {

    private static final String EID = "https://ee.ee/eid";
    private static final String REDIRECT = "https://rp.example/callback";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static byte[] kek = new byte[32];
    static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static TenantAuthority sideAuthority;

    static String rootOrgId;
    static String labAId;
    static String labBId;
    static String encounterAtA;
    static String encounterAtB;
    static String labAToken;
    static String rootToken;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("OrganisationReachIT");
        new SecureRandom().nextBytes(kek);
        dir = Files.createTempDirectory("dbo-orgreach");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        provisioner.rpRedirectUris(List.of(REDIRECT));
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // Encounter is partitioned by its serviceProvider; Patient deliberately
        // is NOT, so the test can prove a shared type stays shared.
        Files.writeString(dir.resolve("osak.json"), """
                {"code":"osak","face":"r4",
                 "organisations":{"perType":{"Encounter":"service_provider"}},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Organization","identity":"internal","handling":"operational"},
                  {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"},
                  {"name":"Encounter","identity":"internal","handling":"operational"}]}"""
                .formatted(EID, EID));
        UntilServed.scan(manager, "osak");

        String service = serviceToken();
        // the tree: a hospital, two labs under it
        rootOrgId = idOf(post("/Organization", service, """
                {"resourceType":"Organization","name":"Haigla",
                 "identifier":[{"system":"urn:osak:org","value":"haigla"}]}"""));
        labAId = idOf(post("/Organization", service, """
                {"resourceType":"Organization","name":"Lab A",
                 "identifier":[{"system":"urn:osak:org","value":"lab-a"}],
                 "partOf":{"reference":"Organization/%s"}}""".formatted(rootOrgId)));
        labBId = idOf(post("/Organization", service, """
                {"resourceType":"Organization","name":"Lab B",
                 "identifier":[{"system":"urn:osak:org","value":"lab-b"}],
                 "partOf":{"reference":"Organization/%s"}}""".formatted(rootOrgId)));

        // one encounter in each lab's world
        encounterAtA = idOf(post("/Encounter", service, """
                {"resourceType":"Encounter","status":"planned","class":{"code":"AMB"},
                 "serviceProvider":{"reference":"Organization/%s"}}""".formatted(labAId)));
        encounterAtB = idOf(post("/Encounter", service, """
                {"resourceType":"Encounter","status":"planned","class":{"code":"AMB"},
                 "serviceProvider":{"reference":"Organization/%s"}}""".formatted(labBId)));

        // liisa is a clinician AT LAB A; peeter holds the same role AT THE
        // HOSPITAL, whose reach covers both labs through the hierarchy
        String liisaPractitioner = idOf(post("/Practitioner", service, """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"%s","value":"47001010033"}]}""".formatted(EID)));
        String liisaPerson = idOf(post("/Person", service, """
                {"resourceType":"Person","identifier":[{"system":"%s","value":"47001010033"}],
                 "link":[{"target":{"reference":"Practitioner/%s"}}]}"""
                .formatted(EID, liisaPractitioner)));
        assertEquals(201, post("/PractitionerRole", service, """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "organization":{"reference":"Organization/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"doctor"}]}]}"""
                .formatted(liisaPractitioner, labAId)).statusCode());

        String peeterPractitioner = idOf(post("/Practitioner", service, """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"%s","value":"37001010044"}]}""".formatted(EID)));
        String peeterPerson = idOf(post("/Person", service, """
                {"resourceType":"Person","identifier":[{"system":"%s","value":"37001010044"}],
                 "link":[{"target":{"reference":"Practitioner/%s"}}]}"""
                .formatted(EID, peeterPractitioner)));
        assertEquals(201, post("/PractitionerRole", service, """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "organization":{"reference":"Organization/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"doctor"}]}]}"""
                .formatted(peeterPractitioner, rootOrgId)).statusCode());

        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "tenant_osak");
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        sideAuthority = new TenantAuthority(new PgObjectStore(ds, IdentityModel.registrations()),
                "http://127.0.0.1:" + manager.port() + "/t/osak/oidc", new KeyProtector(kek));
        // the SAME role code, granted at two places: by the org's declared
        // code at lab A, and at the hospital by ITS code — the two spellings
        // configuration actually uses
        sideAuthority.ensureRoleGrant("doctor", "lab-a", List.of("user/*.read"));
        sideAuthority.ensureRoleGrant("doctor", "haigla", List.of("user/*.read"));
        sideAuthority.ensureLocalCredential("liisa", "salasona1", liisaPerson);
        sideAuthority.ensureLocalCredential("peeter", "salasona2", peeterPerson);
        sideAuthority.ensureClient("webapp", null, List.of("user/*.read", "user/*.write"),
                "public-pkce", List.of(REDIRECT));

        labAToken = login("liisa", "salasona1");
        rootToken = login("peeter", "salasona2");
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    /** The token says where the grants were made, so a consumer can tell. */
    @Test
    @Order(1)
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void theTokenCarriesTheReachTheGrantsWereMadeAt() {
        String claims = claimsOf(labAToken);
        assertTrue(claims.contains("\"org\":[\"" + labAId + "\"]"), claims);
        String rootClaims = claimsOf(rootToken);
        assertTrue(rootClaims.contains(rootOrgId) && rootClaims.contains(labAId)
                        && rootClaims.contains(labBId),
                "a grant at the hospital reaches its departments: " + rootClaims);
    }

    /** A department's clinician reads their department's work and no other's. */
    @Test
    @Order(2)
    void aSearchAnswersOnlyInsideTheReach() throws Exception {
        String bundle = get("/Encounter", labAToken).body();
        assertTrue(bundle.contains(encounterAtA), bundle);
        assertFalse(bundle.contains(encounterAtB),
                "lab B's encounter must not appear in lab A's world: " + bundle);
    }

    /**
     * Absent, not forbidden: a 403 for another organisation's id would
     * confirm the id exists, which is what the compartment exists to hide.
     */
    @Test
    @Order(3)
    void anotherOrganisationsRecordIsAbsentNotForbidden() throws Exception {
        assertEquals(200, get("/Encounter/" + encounterAtA, labAToken).statusCode());
        assertEquals(404, get("/Encounter/" + encounterAtB, labAToken).statusCode());
    }

    /** A grant at the parent covers the departments under it. */
    @Test
    @Order(4)
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void aGrantAtTheParentReachesItsDepartments() throws Exception {
        String bundle = get("/Encounter", rootToken).body();
        assertTrue(bundle.contains(encounterAtA) && bundle.contains(encounterAtB), bundle);
        assertEquals(200, get("/Encounter/" + encounterAtB, rootToken).statusCode());
    }

    /** A type with no declared organisation path is shared, not invisible. */
    @Test
    @Order(5)
    void anUnpartitionedTypeStaysShared() throws Exception {
        assertEquals(200, get("/Patient?_summary=count", labAToken).statusCode());
    }

    /** The organisation tree itself narrows to what the caller may reach. */
    @Test
    @Order(6)
    void theOrganisationListNarrowsToTheReach() throws Exception {
        String orgs = get("/Organization", labAToken).body();
        assertTrue(orgs.contains(labAId), orgs);
        assertFalse(orgs.contains("\"id\":\"" + labBId + "\""),
                "lab B is not lab A's to enumerate: " + orgs);
    }

    // ------------------------------------------------------------- plumbing

    private String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/osak";
    }

    private String serviceToken() throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(provisioner.bootstrapClientSecret("osak"), StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String login(String login, String password) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpResponse<String> redirect = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/oidc/authorize/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "client_id=webapp&redirect_uri="
                                        + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&state=s&code_challenge=" + challenge
                                        + "&login=" + login + "&password=" + password)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, redirect.statusCode(), redirect.body());
        String code = redirect.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*code=([^&]+).*", "$1");
        String body = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=authorization_code&client_id=webapp&code=" + code
                                        + "&redirect_uri="
                                        + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                        + "&code_verifier=" + verifier)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String token = body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        assertFalse(token.isEmpty() || token.equals(body), "no access token in: " + body);
        return token;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Content-Type", "application/fhir+json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        return created.body().replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }

    private static String claimsOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
    }
}
