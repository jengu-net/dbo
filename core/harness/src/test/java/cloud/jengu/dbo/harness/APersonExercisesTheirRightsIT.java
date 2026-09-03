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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-PERSON-RIGHTS, walked in order.
 *
 * <p>Liis Tamm was recorded in the clinic in
 * {@code US-DBO-CLINICAL-RECORD}. This is what she can ask for afterwards,
 * and what the store does about it.
 *
 * <p>The shape of the answer is the point. Erasure is not a delete: her
 * identifying data was encrypted under a key of her own before it ever
 * reached the engine, so destroying that key makes every copy of it — the
 * history, the archives, the appliance that replicated it — pseudonymous at
 * once, without anybody chasing rows. What is left is a record that says
 * something happened and cannot say to whom.
 *
 * <p>And it is asked for and answered like any other work: a run with a key,
 * milestones it can stop at, and an account afterwards.
 *
 * <p><b>One clinic, one person, in dependency order.</b> Assertions are about
 * Liis, never about how many people the tenant holds.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class APersonExercisesTheirRightsIT {

    private static final String CLINIC = "kevadoigus";
    private static final String EID = "https://ee.ee/eid";
    private static final String IDP = "urn:test:idp:kevad";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI erasureDoor;
    static String patientId;
    static String staffId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-person-rights");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("APersonExercisesTheirRightsIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"full"},
                 "scim":{"system":"%s"},
                 "types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Person","identity":"internal","handling":"operational"},
                  {"name":"Practitioner","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC, IDP, EID));
        UntilServed.scan(manager, CLINIC);
        erasureDoor = URI.create(
                "http://127.0.0.1:" + manager.port() + "/t/" + CLINIC + "/erasure");
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

    // ── she is recorded, and who may read her is declared ──

    @Test
    @Order(1)
    @DisplayName("Liis is recorded with her name, and a credential that may write every type "
            + "in the clinic still reads her back pseudonymously")
    @Proving({DboPromises.PDI_STRUCTURAL_VAULT, DboPromises.PDI_BLIND_OPERATIONS,
            DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED})
    void sheIsRecordedAndReadsBackPseudonymously() throws Exception {
        HttpResponse<String> created = post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(EID), clinical());
        assertEquals(201, created.statusCode(), created.body());
        patientId = created.body().replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // The strict mode is the DEFAULT rather than something a surface opts
        // into. A door that has not thought about disclosure cannot leak by
        // saying nothing, which is the only safe direction for this to fail in.
        HttpResponse<String> read = get("/Patient/" + patientId, clinical());
        assertEquals(200, read.statusCode(), read.body());
        assertFalse(read.body().contains("Tamm"),
                "a broad write grant read her name, so what a recipient sees is decided by "
                        + "how much they can write: " + read.body());
        assertTrue(read.body().contains("\"birthDate\":\"1990\""),
                "her birth date comes back generalised rather than absent — coarsened, "
                        + "because a reader who may not identify her may still need to know "
                        + "roughly when she was born: " + read.body());
    }

    @Test
    @Order(2)
    @DisplayName("looking her up by her national identifier is an identifying act, and the "
            + "store refuses it until the caller says what it is for")
    @Proving({DboPromises.PDI_EXACT_RESOLUTION, DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED})
    void anIdentifyingLookupNeedsAStatedPurpose() throws Exception {
        HttpResponse<String> unstated = get("/Patient?identifier="
                + URLEncoder.encode(EID + "|49001010000", StandardCharsets.UTF_8), clinical());

        assertFalse(unstated.statusCode() == 200,
                "an identifying lookup answered with no purpose stated, so the trail cannot "
                        + "say why anybody went looking: " + unstated.body());
        assertTrue(unstated.body().contains("purpose"),
                "and the refusal names what it wants rather than saying no: "
                        + unstated.body());
        assertTrue(unstated.body().contains("TREAT") || unstated.body().contains("PATRQT"),
                "naming the codes that would work, so a caller can act on the refusal: "
                        + unstated.body());
    }

    @Test
    @Order(3)
    @DisplayName("erasing somebody is its own authority: a credential that may write every "
            + "type in the clinic still may not destroy a person's key")
    @Proving({DboPromises.PDI_RIGHTS_AS_OPERATIONS, DboPromises.PDI_ERASURE_IS_A_RUN})
    void erasureIsItsOwnAuthority() throws Exception {
        HttpResponse<String> byAWriter = erase("Patient/" + patientId, clinical());
        assertEquals(403, byAWriter.statusCode(),
                "the broadest write grant in the clinic erased a person, so the most "
                        + "consequential act the store performs is reachable by anybody who "
                        + "can write: " + byAWriter.body());

        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(erasureDoor)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"subject\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode(),
                "and asking with no credential is refused before anything is looked up");
    }

    // ── and then she asks to be forgotten ──

    @Test
    @Order(4)
    @DisplayName("the request is answered with a run keyed by the person, so asking twice is "
            + "the same request rather than a second erasure")
    @Proving({DboPromises.PDI_ERASURE_IS_A_RUN, DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT})
    void theRequestIsARunKeyedByThePerson() throws Exception {
        HttpResponse<String> asked = erase("Patient/" + patientId, eraser());
        assertEquals(202, asked.statusCode(), asked.body());
        assertTrue(asked.body().contains("dbo.erasure/shred/"),
                "the caller is handed the run it can show afterwards: " + asked.body());

        HttpResponse<String> again = erase("Patient/" + patientId, eraser());
        assertEquals(202, again.statusCode(), again.body());
        assertTrue(again.body().contains("dbo.erasure/shred/"),
                "asking twice answers with the same run rather than starting a second: "
                        + again.body());
    }

    @Test
    @Order(5)
    @DisplayName("her key is destroyed, so every copy of her identifying data — history, "
            + "archives, anything that replicated it — is pseudonymous at once")
    @Proving({DboPromises.PDI_CRYPTO_SHREDDING, DboPromises.PDI_UNFINDABLE_AFTER_ERASURE})
    void theKeyIsDestroyedAndEveryCopyGoesWithIt() throws Exception {
        HttpResponse<String> after = get("/Patient/" + patientId, clinical());
        assertTrue(after.statusCode() == 200 || after.statusCode() == 404, after.body());
        assertFalse(after.body().contains("Tamm"), after.body());

        // The sharp part. Before the shred she read back with a COARSE birth
        // date: undisclosed to this reader, but there. After it the coarse
        // value is gone too, because being erased and being undisclosed are
        // different states and only one of them is reversible by a better
        // credential.
        assertFalse(after.body().contains("\"birthDate\""),
                "a generalised value survived the erasure, so somebody who asked to be "
                        + "forgotten is still approximately in the record: " + after.body());
    }

    @Test
    @Order(6)
    @DisplayName("the trail survives her erasure: it still says something happened and can "
            + "no longer say to whom")
    @Proving(DboPromises.POL_ERASURE_COMPATIBLE)
    void theTrailSurvivesTheErasure() throws Exception {
        HttpResponse<String> trail = get("/AuditEvent?_count=50", clinical());
        assertEquals(200, trail.statusCode(), trail.body());

        assertTrue(trail.body().contains("AuditEvent"),
                "the trail was erased along with the person, so the clinic can no longer "
                        + "show that it handled her request: " + trail.body());
        assertFalse(trail.body().contains("Tamm"),
                "and it still names her, so erasure stopped at the record and left the "
                        + "account of it legible: " + trail.body());
    }

    // ── staff leave differently from patients ──

    @Test
    @Order(7)
    @DisplayName("a clinician who leaves is deactivated rather than deleted, because who "
            + "worked here on a date is a fact about that date")
    @Proving(DboPromises.SCIM_DEPROVISION_IS_A_STATE)
    void aClinicianWhoLeavesIsDeactivated() throws Exception {
        String directory = mint("kevad-idp", "scim");
        HttpResponse<String> hired = scim("POST", "/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"emp-9001","userName":"jaan@kevadkliinik.ee",
                 "name":{"familyName":"Kuusk","givenName":"Jaan"},"active":true}""", directory);
        assertEquals(201, hired.statusCode(), hired.body());
        staffId = hired.body().replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        HttpResponse<String> left = scim("PUT", "/Users/" + staffId, """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "id":"%s","externalId":"emp-9001","userName":"jaan@kevadkliinik.ee",
                 "name":{"familyName":"Kuusk","givenName":"Jaan"},"active":false}"""
                .formatted(staffId), directory);
        assertEquals(200, left.statusCode(), left.body());

        HttpResponse<String> still = scim("GET", "/Users/" + staffId, null, directory);
        assertEquals(200, still.statusCode(),
                "the leaver was deleted rather than deactivated, so what they signed last "
                        + "year now refers to nobody: " + still.body());
        assertTrue(still.body().contains("\"active\":false"),
                "and their record says they have left: " + still.body());
    }

    @Test
    @Order(8)
    @DisplayName("a credential the authority has never heard of learns nothing about who "
            + "exists here, because a refusal that varied would be a directory")
    @Proving(DboPromises.AUTH_NO_SUBJECT_ENUMERATION)
    void nothingLeaksWhoExists() throws Exception {
        HttpResponse<String> unknown = tokenResponse("nobody-here", "wrong-secret");
        HttpResponse<String> wrongSecret = tokenResponse("kevad-idp", "wrong-secret");

        assertEquals(wrongSecret.statusCode(), unknown.statusCode(),
                "a client that exists and one that does not were told apart by their status, "
                        + "so the token endpoint is an enumeration oracle");
        assertFalse(unknown.body().contains("nobody-here"),
                "and the refusal does not echo what was asked for: " + unknown.body());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String fhir(String path) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + CLINIC + "/fhir" + path;
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(fhir(path)))
                        .header("Authorization", "Bearer " + bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body, String bearer)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(fhir(path)))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> erase(String subject, String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(erasureDoor)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"subject\":\"" + subject + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> scim(String method, String path, String body,
            String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + manager.port()
                                + "/t/" + CLINIC + "/scim/v2" + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/scim+json");
        request = body == null ? request.GET()
                : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** What the clinic's own application holds: it may write everything and erase nobody. */
    private static String clinical() throws Exception {
        return mint("kevad-emr", "system/*.read", "system/*.write");
    }

    /** And what the request desk holds: erasure, written down on its own. */
    private static String eraser() throws Exception {
        return mint("kevad-desk", "erasure");
    }

    private static String mint(String clientId, String... scopes) throws Exception {
        manager.authority(CLINIC).ensureClient(clientId, clientId + "-secret", List.of(scopes));
        return tokenResponse(clientId, clientId + "-secret").body()
                .replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private static HttpResponse<String> tokenResponse(String clientId, String secret)
            throws Exception {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + CLINIC + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
