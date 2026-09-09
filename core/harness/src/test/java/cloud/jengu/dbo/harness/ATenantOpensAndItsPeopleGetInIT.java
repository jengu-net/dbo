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
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-TENANT-OPENING, walked in order.
 *
 * <p>Ines builds the platform a clinic group runs on. She does not run a
 * database team, she does not want a second identity system, and she is not
 * going to write a tenant boundary of her own. This is her first hour: the
 * store starts inside her own JVM, a clinic becomes a tenant with a database
 * and an authority of its own, her identity provider fills the staff
 * directory, and a clinician's reach is a record she can read rather than
 * code she has to trust.
 *
 * <p><b>One scene, in dependency order, on one pair of tenants.</b> Each test
 * is a leg of the journey and the leg before it is its setup, which is why
 * they are ordered and why they share a fixture. That is the shape of a story
 * class: the assertions are about what the previous step just did, never about
 * what the tenant happens to contain, so the narrative can grow without the
 * earlier legs becoming fragile.
 *
 * <p>The story constant {@code DboStories.TENANT_OPENING} declares the whole
 * journey's joins, including the legs proven elsewhere — that the core carries
 * no framework, that imports are computed, that a cold start is fast. A story
 * leans on those; it does not re-prove them here.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantOpensAndItsPeopleGetInIT {

    /** The clinic Ines is onboarding. */
    private static final String CLINIC = "kevadkliinik";
    /** A second one, opened later from the same running store. */
    private static final String SECOND = "sugiskliinik";
    /** The identity provider's namespace for staff numbers. */
    private static final String IDP = "urn:test:idp:kevad";

    private static final String STAFF_TYPES = """
            [{"name":"Person","identity":"identifier","systems":["urn:test:idp:kevad"],
              "handling":"operational"},
             {"name":"Practitioner","identity":"internal","handling":"operational"},
             {"name":"Patient","identity":"internal","handling":"operational"}]""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    static String directoryToken;
    static String personId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenant-opening");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantOpensAndItsPeopleGetInIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
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

    // ── the store arrives as a library, and a clinic becomes a tenant ──

    @Test
    @Order(1)
    @DisplayName("a spec file is the whole of opening a clinic: the store is already running "
            + "in Ines's JVM, and the tenant's services appear when its declaration does")
    @Proving({DboPromises.CONT_EMBEDDED_IN_JVM, DboPromises.CONT_DYNAMIC_TENANT_SERVICES,
            DboPromises.TEN_DEDICATED_DATABASE_TIER})
    void aDeclarationIsTheWholeOfOpeningAClinic() throws Exception {
        assertTrue(manager.codes().isEmpty(), "the store is up and serving nobody yet");

        // pdi:true because the staff directory needs it — a tenant that lets
        // an identity provider write people is a tenant holding identifying
        // data, and the store refuses the combination by name rather than
        // serving a directory outside the membrane.
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"full"},
                 "scim":{"system":"%s"},
                 "types":%s}""".formatted(CLINIC, IDP, STAFF_TYPES));

        assertEquals(java.util.Set.of(CLINIC), UntilServed.scan(manager, CLINIC),
                "the scan brought the tenant up from its declaration alone");
        assertTrue(manager.runtime(CLINIC).isPresent(),
                "and its service set is registered, which is what serving means here");
    }

    @Test
    @Order(2)
    @DisplayName("the clinic's data is a database of its own, and this code never held the "
            + "credential that made it")
    @Proving({DboPromises.TEN_DEDICATED_DATABASE_TIER,
            DboPromises.TEN_CREDENTIAL_BLIND_PROVISIONING})
    void theClinicGetsADatabaseNobodyHereHeldTheKeyTo() {
        // The provisioner is the seam: it hands back a DataSource and a
        // bootstrap secret it generated. Nothing on this side chose either,
        // which is what lets the same seam be a Kubernetes operator in a
        // deployment and a local provisioner here.
        String secret = provisioner.bootstrapClientSecret(CLINIC);
        assertNotNull(secret, "the tenant's first credential came from the provisioner");
        assertFalse(secret.isBlank());
        assertTrue(manager.runtime(CLINIC).orElseThrow().engine() != null,
                "and the engine is wired over that database");
    }

    // ── nothing is reachable until the tenant's own authority says so ──

    @Test
    @Order(3)
    @DisplayName("the clinic's surface answers nobody without a credential, and says so as a "
            + "refusal rather than as an absence")
    @Proving(DboPromises.AUTH_DENY_BY_DEFAULT)
    void nothingIsReachableWithoutACredential() throws Exception {
        HttpResponse<String> anonymous = http.send(
                HttpRequest.newBuilder(URI.create(fhir(CLINIC) + "/Patient?_summary=count"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, anonymous.statusCode(),
                "401 and not 404: the surface is mounted and guarded, which is the "
                        + "distinction between booting and serving — " + anonymous.body());
    }

    @Test
    @Order(4)
    @DisplayName("the token comes from the clinic's own issuer and is checked without asking "
            + "anybody, and its scope is the whole of what it reaches")
    @Proving({DboPromises.AUTH_TENANT_SCOPED_ISSUER, DboPromises.AUTH_BEARER_LOCAL_VALIDATION,
            DboPromises.AUTH_SMART_SHAPED_SCOPES})
    void theClinicsOwnAuthorityIssuesAndChecksTheToken() throws Exception {
        manager.authority(CLINIC).ensureClient("kevad-app", "kevad-secret",
                List.of("system/Patient.read"));
        String reads = token(CLINIC, "kevad-app", "kevad-secret");

        assertEquals(200, statusOf(fhir(CLINIC) + "/Patient?_summary=count", reads),
                "the scope it was granted answers");
        assertEquals(403, statusOf(fhir(CLINIC) + "/Practitioner?_summary=count", reads),
                "and a type it was not granted is refused, without a round trip to anybody");
    }

    @Test
    @Order(5)
    @DisplayName("a second clinic opens from the same running store, and the first one's "
            + "credential is not merely refused there — it is unintelligible")
    @Proving({DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS, DboPromises.TEN_STRUCTURAL_SCOPING})
    void aSecondClinicOpensAndTheTenantsCannotSeeEachOther() throws Exception {
        Files.writeString(dir.resolve(SECOND + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"full"},
                 "types":%s}""".formatted(SECOND, STAFF_TYPES));
        UntilServed.scan(manager, CLINIC, SECOND);

        String kevad = token(CLINIC, "kevad-app", "kevad-secret");
        assertEquals(200, statusOf(fhir(CLINIC) + "/Patient?_summary=count", kevad));
        assertEquals(401, statusOf(fhir(SECOND) + "/Patient?_summary=count", kevad),
                "401, not 403: the second clinic's authority has never heard of that issuer, "
                        + "so the token is wrong the way noise is wrong. A tenant is a store, "
                        + "not a filter over a shared one");

        assertNotEquals(manager.authority(CLINIC), manager.authority(SECOND),
                "and each opened with its own ceremony rather than a shared one");
    }

    // ── the identity provider fills the staff directory ──

    @Test
    @Order(6)
    @DisplayName("the identity provider provisions a clinician, and what lands is the person "
            + "with a practitioner capacity linked to them")
    @Proving({DboPromises.SCIM_DECLARED_PER_TENANT, DboPromises.SCIM_DIRECTORY_CREDENTIAL,
            DboPromises.SCIM_USER_IS_THE_PERSON})
    void theDirectoryProvisionsAClinician() throws Exception {
        manager.authority(CLINIC).ensureClient("kevad-idp", "idp-secret", List.of("scim"));
        directoryToken = token(CLINIC, "kevad-idp", "idp-secret");

        HttpResponse<String> created = scim("POST", "/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"emp-4711","userName":"maarja@kevadkliinik.ee",
                 "name":{"familyName":"Kask","givenName":"Maarja"},"active":true}""");
        assertEquals(201, created.statusCode(), created.body());
        personId = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        String person = new String(manager.runtime(CLINIC).orElseThrow().engine()
                .get("Person", personId).orElseThrow().payload(), StandardCharsets.UTF_8);
        assertTrue(person.contains("Practitioner/"),
                "the person is who they are and the practitioner is a capacity they act in, "
                        + "ensured and linked on create: " + person);
    }

    @Test
    @Order(7)
    @DisplayName("the directory enumerates its own users, and that enumeration never becomes "
            + "a capability on the clinical surface")
    @Proving({DboPromises.SCIM_ENUMERATION_STAYS_INSIDE, DboPromises.SCIM_GROUPS_READ_ONLY})
    void enumerationStaysBehindTheDirectoryDoor() throws Exception {
        HttpResponse<String> listed = scim("GET", "/Users", null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains("\"totalResults\":1"), listed.body());

        assertEquals(405, scim("POST", "/Groups", "{}").statusCode(),
                "who works here is the identity provider's to say; who is an admin here is "
                        + "not, so groups read and never write");

        // The directory credential carries scim and nothing else, so the
        // enumeration it needs is not a door onto the clinical record.
        assertEquals(403, statusOf(fhir(CLINIC) + "/Patient?_summary=count", directoryToken),
                "the directory credential reached the clinical surface");
    }

    // ── and what each of them may do is a record, not code ──

    @Test
    @Order(8)
    @DisplayName("a clinician's reach is declared as a grant rather than coded, and the human "
            + "it attaches to is an ordinary record in the clinic's own store")
    @Proving({DboPromises.AUTH_IDENTITY_AS_RECORDS,
            DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL})
    void whatAClinicianMayDoIsDeclaredAndWhoTheyAreIsARecord() {
        // Declaring the same grant twice is the ordinary case, not an error:
        // configuration arrives from wherever the clinic keeps it, and a
        // bring-up that refused a grant it already had would make every
        // redeploy a migration.
        manager.authority(CLINIC).ensureRoleGrant("clinician",
                List.of("system/Patient.read", "system/Patient.write"));
        manager.authority(CLINIC).ensureRoleGrant("clinician",
                List.of("system/Patient.read", "system/Patient.write"));

        // That the grant is stored as a readable record, and that the change
        // lands in the trail, is proven where the identity store is in hand
        // (AGrantIsARecordLikeAnyOtherIT). This leg leans on it rather than
        // building a second identity store to re-assert it: the story
        // declares what it rests on, and the promise carries its own proof.
        assertTrue(manager.runtime(CLINIC).orElseThrow().engine()
                        .get("Person", personId).isPresent(),
                "the human the grant will attach to is an ordinary record in the clinic's "
                        + "own store, carried by its backup and dropped with the tenant");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String fhir(String tenant) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + tenant + "/fhir";
    }

    private static int statusOf(String url, String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static HttpResponse<String> scim(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + manager.port()
                                + "/t/" + CLINIC + "/scim/v2" + path))
                .header("Authorization", "Bearer " + directoryToken)
                .header("Content-Type", "application/scim+json");
        request = body == null ? request.GET()
                : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String tenant, String clientId, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + tenant + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private static void assertNotNull(Object value, String because) {
        assertTrue(value != null, because);
    }
}
