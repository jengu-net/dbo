package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenant's SCIM 2.0 provisioning surface: an identity provider
 * drives the staff directory over RFC 7644, the mapping lands on the
 * tenant's own Person/Practitioner records behind the membrane, and the
 * by-system enumeration a list needs never leaves the store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScimProvisioningIT {

    private static final String SYSTEM = "urn:test:idp:external-id";
    private static final String PERSON_TYPES = """
            [{"name":"Person","identity":"identifier","systems":["urn:test:idp:external-id"],
              "handling":"operational"},
             {"name":"Practitioner","identity":"internal","handling":"operational"}]""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String scimToken;
    static String userId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-scim");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ScimProvisioningIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("staffed.json"), """
                {"code":"staffed","face":"r4","pdi":true,
                 "audit":{"level":"full"},
                 "scim":{"system":"%s"},
                 "types":%s}""".formatted(SYSTEM, PERSON_TYPES));
        // The same shape WITHOUT the block: the endpoints must not exist.
        Files.writeString(dir.resolve("unstaffed.json"), """
                {"code":"unstaffed","face":"r4","pdi":true,
                 "audit":{"level":"full"},"types":%s}""".formatted(PERSON_TYPES));
        UntilServed.scan(manager, up -> up.contains("staffed") && up.contains("unstaffed"));
        var authority = manager.authority("staffed");
        authority.ensureClient("okta-scim", "scim-secret-1", List.of("scim"));
        authority.ensureRoleGrant("clinician", List.of("system/*.read"));
        scimToken = token("staffed", "okta-scim", "scim-secret-1");
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

    @Test
    @Order(1)
    @DisplayName("create: the User lands as a Person claiming the externalId, with a linked "
            + "Practitioner capacity")
    @Proving(DboPromises.SCIM_USER_IS_THE_PERSON)
    void createProvisionsThePerson() throws Exception {
        HttpResponse<String> created = scim("POST", "/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"emp-1001","userName":"pomona@hogwarts.scot",
                 "name":{"familyName":"Sprout","givenName":"Pomona"},"active":true}""");
        assertEquals(201, created.statusCode(), created.body());
        userId = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertTrue(created.body().contains("\"externalId\":\"emp-1001\""), created.body());

        String person = new String(manager.runtime("staffed").orElseThrow().engine()
                .get("Person", userId).orElseThrow().payload(), StandardCharsets.UTF_8);
        assertTrue(person.contains("Practitioner/"),
                "the capacity is ensured and linked on create: " + person);
    }

    @Test
    @Order(2)
    @DisplayName("read, list and both filters answer; the wrong filter is refused")
    @Proving(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE)
    void readsAnswerFromTheVault() throws Exception {
        assertEquals(200, scim("GET", "/Users/" + userId, null).statusCode());

        HttpResponse<String> list = scim("GET", "/Users", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"totalResults\":1"), list.body());
        assertTrue(list.body().contains("Sprout"),
                "a purposed SCIM read serves the disclosed name: " + list.body());

        HttpResponse<String> byExternal = scim("GET",
                "/Users?filter=" + URLEncoder.encode("externalId eq \"emp-1001\"",
                        StandardCharsets.UTF_8), null);
        assertTrue(byExternal.body().contains(userId), byExternal.body());

        HttpResponse<String> byUserName = scim("GET",
                "/Users?filter=" + URLEncoder.encode("userName eq \"pomona@hogwarts.scot\"",
                        StandardCharsets.UTF_8), null);
        assertTrue(byUserName.body().contains(userId), byUserName.body());

        assertEquals(400, scim("GET", "/Users?filter=" + URLEncoder.encode(
                "name.familyName co \"Spr\"", StandardCharsets.UTF_8), null).statusCode(),
                "anything beyond the two exact filters is refused, never half-answered");
    }

    @Test
    @Order(3)
    @DisplayName("a second User claiming the same externalId is a 409 naming the conflict")
    void duplicateExternalIdRefused() throws Exception {
        HttpResponse<String> duplicate = scim("POST", "/Users", """
                {"externalId":"emp-1001","userName":"second@hogwarts.scot"}""");
        assertEquals(409, duplicate.statusCode(), duplicate.body());
    }

    @Test
    @Order(4)
    @DisplayName("replace honours the ETag; a stale one is 412; active=false deactivates "
            + "the capacity too")
    @Proving(DboPromises.SCIM_DEPROVISION_IS_A_STATE)
    void replaceAndDeprovision() throws Exception {
        HttpResponse<String> current = scim("GET", "/Users/" + userId, null);
        String version = current.body().replaceAll(".*\"version\":\"W/\\\\\"(\\d+)\\\\\"\".*", "$1");

        HttpResponse<String> stale = scimWithHeader("PUT", "/Users/" + userId, """
                {"externalId":"emp-1001","userName":"pomona@hogwarts.scot","active":true}""",
                "If-Match", "W/\"99\"");
        assertEquals(412, stale.statusCode(), stale.body());

        HttpResponse<String> replaced = scimWithHeader("PUT", "/Users/" + userId, """
                {"externalId":"emp-1001","userName":"pomona@hogwarts.scot",
                 "name":{"familyName":"Sprout","givenName":"Pomona"},"active":false}""",
                "If-Match", "W/\"" + version + "\"");
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertTrue(replaced.body().contains("\"active\":false"), replaced.body());

        var engine = manager.runtime("staffed").orElseThrow().engine();
        String person = new String(engine.get("Person", userId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        String practitionerId = person.replaceAll(".*Practitioner/([0-9a-f-]+).*", "$1");
        assertTrue(new String(engine.get("Practitioner", practitionerId).orElseThrow()
                        .payload(), StandardCharsets.UTF_8).contains("\"active\":false"),
                "deprovision reaches the capacity");

        assertEquals(405, scim("DELETE", "/Users/" + userId, null).statusCode(),
                "erasure is not a provisioning operation");
    }

    @Test
    @Order(5)
    @DisplayName("the credentials do not cross: a SCIM token is refused by the FHIR surface "
            + "and a store token by SCIM")
    @Proving(DboPromises.SCIM_DIRECTORY_CREDENTIAL)
    void credentialsDoNotCross() throws Exception {
        HttpResponse<String> fhirWithScim = http.send(HttpRequest.newBuilder(
                        URI.create(base("staffed") + "/fhir/Person"))
                .header("Authorization", "Bearer " + scimToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, fhirWithScim.statusCode(),
                "a directory credential is structurally blind to the store");

        String storeToken = token("staffed", "tenant-bootstrap",
                provisioner.bootstrapClientSecret("staffed"));
        HttpResponse<String> scimWithStore = http.send(HttpRequest.newBuilder(
                        URI.create(base("staffed") + "/scim/v2/Users"))
                .header("Authorization", "Bearer " + storeToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, scimWithStore.statusCode(),
                "a store credential is not a directory credential");
    }

    @Test
    @Order(6)
    @DisplayName("the enumeration did not leak: identifier=system| at the front door keeps "
            + "today's refusal")
    @Proving(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE)
    void enumerationStaysInside() throws Exception {
        String storeToken = token("staffed", "tenant-bootstrap",
                provisioner.bootstrapClientSecret("staffed"));
        HttpResponse<String> swept = http.send(HttpRequest.newBuilder(
                        URI.create(base("staffed") + "/fhir/Person?identifier="
                                + URLEncoder.encode(SYSTEM + "|", StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + storeToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(swept.statusCode() >= 400, "an enumeration-shaped search must not answer: "
                + swept.statusCode() + " " + swept.body());
    }

    @Test
    @Order(7)
    @DisplayName("every operation is one recorded SCIM disclosure with its purpose")
    @Proving(DboPromises.SCIM_EVERY_OP_IS_A_DISCLOSURE)
    void operationsLandInTheTrail() throws Exception {
        assertTrue(auditSays("staffed", "\"purpose\":\"SYSADMIN\""),
                "a provisioning read is a disclosure of identifying data and must be "
                        + "answerable later as one");
        assertTrue(auditSays("staffed", "okta-scim"),
                "the trail names the provisioning client as the caller");
    }

    @Test
    @Order(8)
    @DisplayName("Groups render from role grants and refuse writes, permanently")
    @Proving(DboPromises.SCIM_GROUPS_READ_ONLY)
    void groupsAreReadOnly() throws Exception {
        HttpResponse<String> groups = scim("GET", "/Groups", null);
        assertEquals(200, groups.statusCode());
        assertTrue(groups.body().contains("clinician"), groups.body());
        assertEquals(405, scim("POST", "/Groups",
                "{\"displayName\":\"root\"}").statusCode(),
                "who is an admin here does not arrive by provisioning");
    }

    @Test
    @Order(9)
    @DisplayName("no declaration, no endpoints; and scim without pdi never comes up")
    @Proving(DboPromises.SCIM_DECLARED_PER_TENANT)
    void declarationGatesTheDoor() throws Exception {
        String unstaffedToken = token("unstaffed", "tenant-bootstrap",
                provisioner.bootstrapClientSecret("unstaffed"));
        HttpResponse<String> absent = http.send(HttpRequest.newBuilder(
                        URI.create(base("unstaffed") + "/scim/v2/Users"))
                .header("Authorization", "Bearer " + unstaffedToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, absent.statusCode(),
                "absent the block, the endpoints do not exist");

        Files.writeString(dir.resolve("clear-headed.json"), """
                {"code":"clear-headed","face":"r4","pdi":false,
                 "audit":{"level":"writes"},
                 "scim":{"system":"%s"},"types":%s}""".formatted(SYSTEM, PERSON_TYPES));
        manager.scanOnce();
        assertFalse(manager.runtime("clear-headed").isPresent(),
                "a staff directory over identity in the clear is refused, not served");
        assertTrue(String.valueOf(manager.troubles()).contains("scim requires pdi"),
                "and the refusal is answerable from the trouble ledger, not only "
                        + "as an absent endpoint: " + manager.troubles());
    }

    // ---------------------------------------------------------- plumbing

    private static String base(String tenant) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + tenant;
    }

    private static HttpResponse<String> scim(String method, String path, String body)
            throws Exception {
        return scimWithHeader(method, path, body, null, null);
    }

    private static HttpResponse<String> scimWithHeader(String method, String path, String body,
            String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(base("staffed") + "/scim/v2" + path))
                .header("Authorization", "Bearer " + scimToken)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (header != null) {
            request.header(header, value);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String tenant, String clientId, String secret)
            throws Exception {
        String form = "grant_type=client_credentials&client_id=" + clientId
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create(base(tenant) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    /** Whether any audit entry this tenant holds carries the phrase. */
    private static boolean auditSays(String tenant, String phrase) throws Exception {
        cloud.jengu.dbo.postgres.PgChangeFeed feed = new cloud.jengu.dbo.postgres.PgChangeFeed(
                provisioner.provision(cloud.jengu.dbo.tenant.TenantSpec.parse(
                        Files.readString(dir.resolve(tenant + ".json")))).dataSource(),
                cloud.jengu.dbo.policy.AuditModel.DOMAIN);
        String cursor = null;
        for (var chunk = feed.read(null, 200); !chunk.items().isEmpty();
                chunk = feed.read(cursor, 200)) {
            for (var item : chunk.items()) {
                if (new String(item.payload(), StandardCharsets.UTF_8).contains(phrase)) {
                    return true;
                }
            }
            cursor = chunk.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        return false;
    }
}
