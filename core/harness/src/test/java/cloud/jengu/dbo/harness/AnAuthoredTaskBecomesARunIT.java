package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Task posted on the tenant's surface becomes a run, and reads back as the
 * same Task as the run advances.
 *
 * <p>Authoring is not participation. A participant holds a lane and takes
 * work; whoever authors work holds the tenant's store credential and states
 * an obligation. So the posting goes to the store surface under the tenant's
 * ordinary write authority — a participation credential is refused there —
 * and the run it creates is one a lane can then claim, exactly as a run
 * minted inside the container. The document is read back through the face's
 * own reverse seam, so the engine learns nothing about Task.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnAuthoredTaskBecomesARunIT {

    private static final String TENANT = "authorhost";
    private static final String STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static ObjectStore engine;
    static Runs runs;
    static HttpLane bench;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-authored");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnAuthoredTaskBecomesARunIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);
        manager.authority(TENANT).ensureClient("bench", "bench-secret", List.of("work/" + STEP));
        bench = HttpLane.to(URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work"),
                () -> token("bench", "bench-secret"), TENANT, "bench",
                new Executor("bench", "1.0", "cloud.jengu.test", Scope.BASELINE));
        // The step is declared here by a participant introducing it, which is
        // how a step reaches a tenant nobody installed it in.
        bench.introduce(ASSAY);
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

    @Test
    @DisplayName("posted over HTTP, claimed over a lane, closed, and read back as a completed Task")
    @Proving({DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE, DboPromises.PROC_RUN_HAS_A_RECORD})
    void authoredClaimedClosedAndReadBack() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
        HttpResponse<String> posted = post(task("order-4711", "Basic/" + specimen), bootstrap());
        assertEquals(201, posted.statusCode(), posted.body());
        assertTrue(posted.body().contains("\"resourceType\":\"Task\"")
                        && posted.body().contains("\"status\":\"in-progress\"")
                        && posted.body().contains(STEP + "/order-4711"),
                "the run, rendered as the Task it was posted as: " + posted.body());
        String location = posted.headers().firstValue("Location").orElseThrow();
        String id = location.substring(location.lastIndexOf('/') + 1);

        // The run is one the store minted: a lane claims it like any other.
        Run run = runs.byKey(STEP + "/order-4711").orElseThrow();
        assertEquals(id, run.id(), "the Task's id is the run's id — one record, not two");
        assertEquals("Basic/" + specimen, run.inputs().get("specimen"), "inputs by slot");
        List<Run> offered = bench.poll(Set.of("assay"), 10);
        assertTrue(offered.stream().anyMatch(r -> r.key().equals(run.key())),
                "the authored run is offered to a participant: " + offered);
        Run held = bench.claim(run, Duration.ofMinutes(5)).orElseThrow();
        assertEquals(1, bench.inputs(held).size());
        HttpResponse<String> inProgress = get("/Task/" + id, bootstrap());
        assertEquals(200, inProgress.statusCode(), inProgress.body());
        assertTrue(inProgress.body().contains("\"status\":\"in-progress\"")
                        && inProgress.body().contains("\"value\":\"bench\""),
                "the author sees who holds it while it is held: " + inProgress.body());
        bench.closed(held);

        HttpResponse<String> read = get("/Task/" + id, bootstrap());
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("\"status\":\"completed\"")
                        && read.body().contains("\"code\":\"nobody\""),
                "and sees it advance to done, held by nobody: " + read.body());
    }

    @Test
    @DisplayName("refused by name: an unknown step, an undeclared slot, an unfilled declared "
            + "slot, a name already used — and a participation credential cannot author at all")
    @Proving(DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE)
    void refusedByName() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
        assertEquals(201, post(task("taken", "Basic/" + specimen), bootstrap()).statusCode());

        HttpResponse<String> again = post(task("taken", "Basic/" + specimen), bootstrap());
        assertEquals(409, again.statusCode(), again.body());
        assertTrue(again.body().contains("already exists"), again.body());

        HttpResponse<String> unknown = post(task("dbo.lab", "nowhere", "x", "specimen",
                "Basic/" + specimen), bootstrap());
        assertEquals(422, unknown.statusCode(), unknown.body());
        assertTrue(unknown.body().contains("no step 'dbo.lab.nowhere'"), unknown.body());

        HttpResponse<String> undeclared = post(task("dbo.lab", "assay", "y", "reagent",
                "Basic/" + specimen), bootstrap());
        assertEquals(422, undeclared.statusCode(), undeclared.body());
        assertTrue(undeclared.body().contains("declares no slot 'reagent'"), undeclared.body());

        HttpResponse<String> unfilled = post("{\"resourceType\":\"Task\",\"intent\":\"order\","
                + "\"identifier\":[{\"system\":\"urn:dbo:run\",\"value\":\"z\"}],"
                + "\"code\":{\"coding\":[{\"system\":\"urn:dbo:process\",\"code\":\"dbo.lab\"},"
                + "{\"system\":\"urn:dbo:step\",\"code\":\"assay\"}]}}", bootstrap());
        assertEquals(422, unfilled.statusCode(), unfilled.body());
        assertTrue(unfilled.body().contains("'specimen' is unfilled"), unfilled.body());

        HttpResponse<String> participant = post(task("by-a-bench", "Basic/" + specimen),
                token("bench", "bench-secret"));
        assertEquals(403, participant.statusCode(),
                "a participation credential takes work; it does not author it: "
                        + participant.body());

        HttpResponse<String> edited = http.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(TENANT) + "/Task/"
                                + runs.byKey(STEP + "/taken").orElseThrow().id()))
                        .header("Authorization", "Bearer " + bootstrap())
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, edited.statusCode(), "a run advances through its lane: " + edited.body());
    }

    // ------------------------------------------------------------ fixtures

    private static String task(String scope, String reference) {
        return task("dbo.lab", "assay", scope, "specimen", reference);
    }

    private static String task(String process, String step, String scope, String slot,
            String reference) {
        return "{\"resourceType\":\"Task\",\"intent\":\"order\",\"status\":\"requested\","
                + "\"identifier\":[{\"system\":\"urn:dbo:run\",\"value\":\"" + scope + "\"}],"
                + "\"code\":{\"coding\":[{\"system\":\"urn:dbo:process\",\"code\":\"" + process
                + "\"},{\"system\":\"urn:dbo:step\",\"code\":\"" + step + "\"}]},"
                + "\"input\":[{\"type\":{\"coding\":[{\"system\":\"urn:dbo:run:input\",\"code\":\""
                + slot + "\"}]},\"valueReference\":{\"reference\":\"" + reference + "\"}}]}";
    }

    private static HttpResponse<String> post(String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(TENANT) + "/Task"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(TENANT) + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/fhir+json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String bootstrap() {
        return token("tenant-bootstrap", provisioner.bootstrapClientSecret(TENANT));
    }

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + manager.port()
                                            + "/t/" + TENANT + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
            assertTrue(m.find(), body);
            return m.group(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
