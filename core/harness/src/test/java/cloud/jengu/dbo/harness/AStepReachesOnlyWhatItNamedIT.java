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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run answers for what it was given, and a credential that may act in work
 * cannot use it to read anything else.
 *
 * <p>The documentation has claimed for some time that access is granted to a
 * step and that there is no way to reach the data without performing the work
 * that needed it. It was not true: a credential holding the broad read scope
 * reads any record with no step anywhere in the picture. This is the first
 * surface where the claim holds.
 *
 * <p>The boundary is <b>what the run names</b>, and nothing is traversed from
 * it. So the interesting assertions are the refusals: a patient of a declared
 * type that this run was not given, and a type the step never declared, are
 * both as absent as a record that does not exist — not forbidden, because an
 * answer that distinguishes the two tells whoever asks that the thing is
 * there.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AStepReachesOnlyWhatItNamedIT {

    private static final String CLINIC = "stepreach";
    private static final String NID = "https://stepreach.example/nid";
    private static final String STEP = "clinic.admission.admit";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String base;
    static String given;
    static String withheld;
    static String observation;
    static String runContext;
    static String worker;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-stepreach");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AStepReachesOnlyWhatItNamedIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r5","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"%s","slots":{"patient":"Patient"}}]}"""
                .formatted(CLINIC, NID, STEP));
        UntilServed.scan(manager, CLINIC);
        base = manager.baseUrl(CLINIC);

        // Two credentials, because the point is that they are not the same
        // thing: one may read the tenant, one may act in work.
        manager.authority(CLINIC).ensureClient("the-clinic", "clinic-secret",
                java.util.List.of("system/*.read", "system/*.write"));
        manager.authority(CLINIC).ensureClient("a-worker", "worker-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.WORK));
        worker = token("a-worker", "worker-secret");

        given = created("RL-GIVEN");
        withheld = created("RL-WITHHELD");
        observation = idOf(post(base + "/Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"a reading"}}"""));
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
    @DisplayName("a run is started over the document it is given, and the context it returns "
            + "answers for that document")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
    void theRunAnswersForWhatItWasGiven() throws Exception {
        HttpResponse<String> started = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base.replace("/fhir", "/step/" + STEP)))
                        .header("Authorization", "Bearer " + worker)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"inputs\":{\"patient\":\"Patient/" + given + "\"}}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, started.statusCode(), started.body());
        // A path rather than an absolute url: what the node is bound to is not
        // what a caller reached it by, and a context that guessed would hand
        // out links that work nowhere. The caller resolves it against the
        // origin it already used.
        String path = started.body().replaceAll("(?s).*\"context\":\"([^\"]+)\".*", "$1");
        assertTrue(path.startsWith("/t/" + CLINIC + "/run/"), started.body());
        URI origin = URI.create(base);
        runContext = origin.getScheme() + "://" + origin.getAuthority() + path;

        HttpResponse<String> read = inContext("Patient/" + given);
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("RL-GIVEN"), read.body());
    }

    @Test
    @Order(2)
    @DisplayName("a patient of the very type the step declared, which this run was not given, "
            + "is as absent as one that does not exist")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
    void whatTheRunWasNotGivenIsNotThere() throws Exception {
        HttpResponse<String> other = inContext("Patient/" + withheld);
        assertEquals(404, other.statusCode(),
                "a run reached a patient it was never given, which is the whole of what this "
                        + "surface exists to prevent: " + other.body());

        // The property is not that the answer says little — the asker already
        // knows the id it asked for. It is that the answer is the SAME for a
        // record that exists and one that never did, so the boundary cannot be
        // used to discover what the tenant holds.
        String invented = "01a00000-0000-7000-8000-00000000beef";
        HttpResponse<String> nothing = inContext("Patient/" + invented);
        assertEquals(404, nothing.statusCode(), nothing.body());
        assertEquals(other.body().replace(withheld, invented), nothing.body(),
                "a withheld record and an absent one answer differently, so asking is a way "
                        + "to find out which records exist");

        // And a type the step never named at all is refused the same way.
        assertEquals(404, inContext("Observation/" + observation).statusCode());
    }

    @Test
    @Order(3)
    @DisplayName("the context says what it answers for, and names only the step's own types")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
    void theContextDeclaresWhatItServes() throws Exception {
        HttpResponse<String> metadata = inContext("metadata");
        assertEquals(200, metadata.statusCode(), metadata.body());
        assertTrue(metadata.body().contains("\"Patient\""), metadata.body());
        assertTrue(!metadata.body().contains("\"Observation\""),
                "the context advertises a type the step never declared: " + metadata.body());
    }

    @Test
    @Order(4)
    @DisplayName("the credential that may act in work cannot read the tenant's records "
            + "directly, which is what makes the run context worth having")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
    void theWorkCredentialIsNotAReadCredential() throws Exception {
        HttpResponse<String> direct = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base + "/Patient/" + given))
                        .header("Authorization", "Bearer " + worker).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(direct.statusCode() == 401 || direct.statusCode() == 403,
                "the work credential read a record through the general surface, so entering "
                        + "through a run bought nothing: " + direct.statusCode());
    }

    // ------------------------------------------------------------ plumbing

    private static HttpResponse<String> inContext(String relative) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(runContext + "/" + relative))
                        .header("Authorization", "Bearer " + worker).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String created(String value) throws Exception {
        return idOf(post(base + "/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Reach"}]}""".formatted(NID, value)));
    }

    private static String idOf(HttpResponse<String> response) {
        return response.body().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + token("the-clinic", "clinic-secret"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return response;
    }

    private static String token(String client, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client + "&client_secret="
                + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                        base.replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
