package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.stream.StreamLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same runner, the same work, the same outcome over all three carriers
 * — in-process, HTTP, and the store's own stream — and no way to tell from
 * inside which carried it.
 *
 * <p>The stream is the one a shared fleet holds: the host connects to the
 * durable substrate it already runs on and every tenant's door is a workflow
 * there, so a verb is a message and its answer an event, and no tenant
 * accepts a callback. Driven here through a real tenant runtime with a real
 * token, because a carrier that was built and never mounted is this
 * repository's characteristic failure.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ALaneOverTheStreamIsIndistinguishableIT {

    private static final String TENANT = "streamhost";
    private static final String STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static PGSimpleDataSource substrate;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static ObjectStore engine;
    static Runs runs;
    static KeyPair sealing;
    static KeyPair signing;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-stream-lane");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ALaneOverTheStreamIsIndistinguishableIT"),
                postgres.getUsername(), postgres.getPassword());
        substrate = new PGSimpleDataSource();
        substrate.setUrl(SharedPostgres.urlFor("ALaneOverTheStreamIsIndistinguishableIT_substrate"));
        substrate.setUser(postgres.getUsername());
        substrate.setPassword(postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // The substrate is given before the tenant is served, so the door
        // opens with the tenant rather than after it.
        manager.substrate(substrate);
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);
        manager.authority(TENANT).ensureClient("courier", "courier-secret", List.of("work/" + STEP));
        sealing = KeyWrap.newParticipantKeyPair();
        signing = SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("analyser", "analyser-secret",
                List.of("work/" + STEP), ParticipantKey.of(sealing.getPublic()),
                SigningKey.of(signing.getPublic()));
        HttpLane.to(laneUri, () -> token("courier", "courier-secret"), TENANT, "courier",
                executor("courier")).introduce(ASSAY);
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
    @DisplayName("the same runner and service do the same work to the same outcome over HTTP "
            + "and over the stream — sealed on both, signed on the stream — and every verb "
            + "lands on the tenant's own store")
    @Proving({DboPromises.PROC_A_LANE_OVER_THE_STREAM,
            DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS})
    void theRunnerCannotTellWhichCarriedIt() throws Exception {
        Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        StepService service = new StepService() {
            @Override
            public String step() {
                return STEP;
            }

            @Override
            public Outcome perform(Work work) {
                Map<String, String> inputs = new java.util.TreeMap<>();
                work.inputs().forEach((slot, object) -> inputs.put(slot,
                        new String(object.payload(), StandardCharsets.UTF_8)));
                seen.put(work.run().key(), inputs);
                return Outcome.done(Map.of("assayed", 1L));
            }
        };

        Run overHttp = runFor("over-http");
        Run overStream = runFor("over-stream");
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50));
                StreamLane stream = StreamLane.holding(substrate, TENANT, "analyser-on-the-stream",
                        executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            runner.register(service);
            runner.attach(HttpLane.holding(laneUri, () -> token("analyser", "analyser-secret"),
                    TENANT, "analyser-over-http", executor("analyser"), sealing.getPrivate(),
                    signing.getPrivate()));
            runner.attach(stream);
            long deadline = System.nanoTime() + Eventually.PATIENCE.toNanos();
            while (System.nanoTime() < deadline && (runs.byKey(overHttp.key()).orElseThrow().open()
                    || runs.byKey(overStream.key()).orElseThrow().open())) {
                runner.cycle();
                Thread.sleep(50);
            }
        }

        Run http = runs.byKey(overHttp.key()).orElseThrow();
        Run stream = runs.byKey(overStream.key()).orElseThrow();
        assertEquals(Holder.NOBODY, http.holder(), "over HTTP: " + http);
        assertEquals(Holder.NOBODY, stream.holder(), "over the stream: " + stream);
        assertEquals(http.tally(), stream.tally(), "the same outcome");
        assertEquals(seen.get(overHttp.key()), seen.get(overStream.key()),
                "the same work arrived whole, whichever carried it: " + seen);
        assertTrue(entries(WorkModel.TYPE, stream.id()).stream()
                        .anyMatch(e -> e.contains("\"code\":\"travel\"")),
                "the hop over the stream is on the task's trail like any other");
    }

    @Test
    @DisplayName("full duplex on one channel: sealed work goes out on the stream, and the "
            + "signed opening and the result come home on it")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void workOutAndEventsHomeOnOneChannel() throws Exception {
        Run run = runFor("duplex");
        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "analyser",
                executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            assertEquals(1, lane.inputs(held).size(), "opened here, with the key held here");
            lane.closed(held);
        }
        Run closed = runs.byKey(run.key()).orElseThrow();
        assertEquals(Holder.NOBODY, closed.holder(), "closed on the head the opening left");
        List<String> chain = entries(WorkModel.TYPE, closed.id());
        String specimen = run.inputs().get("specimen").substring("Basic/".length());
        assertTrue(chain.stream().anyMatch(e -> e.contains("\"code\":\"travel\"")), chain.toString());
        assertTrue(entries("Basic", specimen).stream()
                        .anyMatch(e -> e.contains("\"code\":\"access\"")
                                && e.contains("\"by\":\"analyser\"")),
                "the opening came home on the same channel and landed on the document");
    }

    // ------------------------------------------------------------ fixtures

    private static Run runFor(String key) {
        String specimen = engine.put(PutRequest.create("Basic",
                // The same document for every run, so what arrived can be
                // compared across carriers rather than only counted.
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"specimen\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return runs.of(ASSAY, RunKind.PIPELINE, key, Map.of("specimen", "Basic/" + specimen));
    }

    private static List<String> entries(String targetType, String targetId) {
        List<String> out = new ArrayList<>();
        engine.select(Criteria.of("AuditEntry")).forEach(o -> {
            String e = new String(o.payload(), StandardCharsets.UTF_8);
            if (e.contains("\"targetType\":\"" + targetType + "\"")
                    && e.contains("\"targetId\":\"" + targetId + "\"")) {
                out.add(e);
            }
        });
        return out;
    }

    private static Executor executor(String name) {
        return new Executor(name, "1.0", "cloud.jengu.test", Scope.BASELINE);
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
