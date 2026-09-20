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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking what a change would do, without doing it.
 *
 * <p>The classification existed and was correct, and ran inside the sweep that
 * applies what it classifies in the same pass. So the only way to learn that a
 * change was a rebuild was to cause the rebuild — which takes the tenant's
 * surface down for the length of it — and the only way to learn that a change
 * was refused outright was to read the refusal afterwards, as the record of an
 * attempt. The answer was worst exactly where somebody most needed it before
 * committing.
 *
 * <p><b>A named path rather than a flag</b>, and that is the load-bearing
 * choice. A parameter that switches applying off is a parameter whose typo
 * applies: the caller who misspells it was the one specifically trying not to
 * cause a change. A misspelt path is a refusal.
 *
 * <p>Which matters more than it reads, because the door is mounted on a
 * context and a context matches by prefix — so before this, every spelling of
 * every sub-path reached the applying handler and applied.
 *
 * <p><b>A world of its own, and the management tenant is why.</b> Nothing
 * here changes a tenant — a preview is the whole subject — so the tenant
 * could be shared. What cannot is the runtime: this asks the control plane,
 * and a runtime has a management tenant because it was told to manage one.
 * Registering one on the shared runtime would mount a control plane over
 * every other class's tenants, which is a deployment somebody else is
 * running.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AChangeCanBeAskedAboutBeforeItIsMadeIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String management;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-preview");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AChangeCanBeAskedAboutBeforeItIsMadeIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        Path managementSpec = Files.createTempDirectory("dbo-registry").resolve("registry.json");
        Files.writeString(managementSpec, spec("register", "r4", false, "Observation"));
        management = manager.manages(managementSpec);

        Files.writeString(dir.resolve("klinik.json"), spec("klinik", "r4", false, "Observation"));
        UntilServed.scan(manager, "klinik");
        manager.authority(management).ensureClient("an-operator", "operator-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
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
    @DisplayName("a rebuild says it is a rebuild and names the fields that make it one, and "
            + "the tenant goes on serving what it was serving")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void itSaysWhatARebuildWouldBe() throws Exception {
        String answered = preview(spec("klinik", "r4", false, "Observation", "Patient"));

        assertTrue(answered.contains("\"kind\":\"rewire\""),
                "adding a type is a rebuild in place and was not classified as one: "
                        + answered);
        assertTrue(answered.contains("types"), "and the field that makes it one: " + answered);
        assertTrue(answered.contains("\"applied\":0"), answered);

        assertFalse(servesType("klinik", "Patient"),
                "the preview built the type it was asked about, which is the outage this "
                        + "exists to let somebody avoid");
    }

    @Test
    @Order(2)
    @DisplayName("a cold change is refused by name before it is committed, rather than as the "
            + "record of an attempt afterwards")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void itSaysWhatCannotBeHadAtAll() throws Exception {
        String answered = preview(spec("klinik", "r5", false, "Observation"));

        assertTrue(answered.contains("\"kind\":\"cold\""), answered);
        assertTrue(answered.contains("face"), answered);
        assertTrue(answered.contains("retracted"),
                "and what it would take, which is the half somebody is asking for: "
                        + answered);
    }

    @Test
    @Order(3)
    @DisplayName("a declaration identical to what is served says so, because 'nothing would "
            + "happen' is the answer somebody polling a repository most often needs")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void itSaysWhenNothingWouldHappen() throws Exception {
        assertTrue(preview(spec("klinik", "r4", false, "Observation"))
                        .contains("\"kind\":\"unchanged\""),
                "an identical declaration read as a change");
    }

    @Test
    @Order(4)
    @DisplayName("a tenant this deployment does not serve would be built rather than changed, "
            + "and says that instead of comparing against nothing")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void itSaysWhenThereIsNothingToChange() throws Exception {
        String answered = preview(spec("uus-klinik", "r4", false, "Observation"));
        assertTrue(answered.contains("\"kind\":\"new\""), answered);

        assertFalse(manager.runtime("uus-klinik").isPresent(),
                "asking about a tenant that does not exist created it");
    }

    @Test
    @Order(5)
    @DisplayName("a misspelt path is refused, because the near misses of 'preview' must not "
            + "be the applying door")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void aMisspeltPathIsNotTheApplyingDoor() throws Exception {
        HttpResponse<String> refused = post("/preveiw",
                body(spec("klinik", "r4", false, "Observation", "Encounter")));

        assertEquals(404, refused.statusCode(),
                "a sub-path nobody serves reached a door that applies: " + refused.body());
        assertFalse(servesType("klinik", "Encounter"),
                "and it applied the change somebody was trying to look at, which is the "
                        + "whole failure this path was named to avoid");
    }

    @Test
    @Order(6)
    @DisplayName("nothing was recorded by any of it: a preview does not happen to the tenant, "
            + "so it is not in the history of what happened to the tenant")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void askingLeavesNoTrace() throws Exception {
        // Five previews have run above, one of them of a change that would be
        // refused outright. None of them is a run, and none of them left the
        // tenant declared differently from what it serves.
        assertTrue(manager.redeclarations().isEmpty(),
                "a preview was taken for a redeclaration the sweep had noticed: "
                        + manager.redeclarations());
        assertTrue(servesType("klinik", "Observation"),
                "the tenant stopped serving what it was declared with");
        assertFalse(servesType("klinik", "Patient"), "and gained nothing it was asked about");
    }

    // ------------------------------------------------------------ plumbing

    private static String spec(String code, String face, boolean pdi, String... types) {
        StringBuilder declared = new StringBuilder();
        for (String type : types) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"internal\",\"handling\":\"operational\"}");
        }
        return "{\"code\":\"" + code + "\",\"face\":\"" + face + "\",\"pdi\":" + pdi
                + ",\"types\":[" + declared + "]}";
    }

    private static String body(String tenantSpec) {
        return "{\"declarations\":[{\"type\":\"TenantDeclaration\",\"name\":\"proposed\","
                + "\"payload\":" + tenantSpec + "}]}";
    }

    private static String preview(String tenantSpec) throws Exception {
        HttpResponse<String> answered = post("/preview", body(tenantSpec));
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }

    private static HttpResponse<String> post(String verb, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(management).replace("/fhir", "/configuration") + verb))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static boolean servesType(String code, String type) {
        return manager.runtime(code)
                .map(runtime -> runtime.spec().types().stream()
                        .anyMatch(declared -> type.equals(declared.typeName())))
                .orElse(false);
    }

    private static String token() throws Exception {
        String form = "grant_type=client_credentials&client_id=an-operator"
                + "&client_secret=" + URLEncoder.encode("operator-secret", StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(cloud.jengu.dbo.auth.Scopes.CONFIGURATION,
                        StandardCharsets.UTF_8);
        return Extracted.tokenIn(HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(management).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body());
    }
}
