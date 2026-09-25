package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.stream.StreamLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant says it has work, and a runner on the stream stops waiting.
 *
 * <p>This is the test the wake-up needs and the facade suites cannot give.
 * They prove a runner cannot tell which carrier it holds — which stays true
 * whether or not a wake-up is ever delivered, because the poll underneath
 * does the work either way. So a wake-up that quietly stopped arriving would
 * pass every other test in this repository and cost only latency nobody
 * measured, which is the exact shape of the defect this whole area came from:
 * a mechanism failing in silence.
 *
 * <p>The separation is therefore arithmetic rather than assertion-by-timing.
 * The runner's poll interval is set far longer than the patience this test
 * has. If the work is done inside that patience, the only thing that can have
 * caused it is the wake-up — and if the wake-up is gone, the test fails by
 * waiting rather than by being flaky.
 *
 * <p>The trigger goes through the tenant's own machinery on purpose. A run
 * written by a {@code Runs} this test built itself would announce to nobody:
 * the seam belongs to the runtime, and wiring it is exactly what could be
 * left undone. So a courier claims a run over its own lane and then releases
 * it, which is the store making a run claimable the way it really does.
 *
 * <p><b>A world of its own, and the substrate is why.</b> A wake-up travels
 * on the stream, and the stream door is opened by giving the runtime a
 * substrate before a tenant is served. That is the runtime's configuration:
 * handing one to the shared runtime would open a door on every tenant
 * serving there, and could not open it on the ones that came up first.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AStreamLaneIsToldItHasWorkIT {

    private static final String TENANT = "wokenhost";
    private static final String STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    /**
     * Long enough that the poll cannot be what did it.
     *
     * <p>The whole proof is the gap between this and {@link #PATIENCE}: a
     * runner that waited out its tick would miss the deadline by an order of
     * magnitude, so a pass cannot be a slow machine getting lucky.
     */
    private static final Duration NEVER_POLLED_IN_TIME = Duration.ofMinutes(10);
    /** How long a wake-up is given to cross the substrate and be acted on. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

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
        dir = Files.createTempDirectory("dbo-woken-lane");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AStreamLaneIsToldItHasWorkIT"),
                postgres.getUsername(), postgres.getPassword());
        substrate = new PGSimpleDataSource();
        substrate.setUrl(SharedPostgres.urlFor("AStreamLaneIsToldItHasWorkIT_substrate"));
        substrate.setUser(postgres.getUsername());
        substrate.setPassword(postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // Before the tenant is served, so the door opens with it: a door that
        // arrived afterwards would have nothing to publish a wake-up on.
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
        courier().introduce(ASSAY);
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
    @Proving(DboPromises.PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES)
    @DisplayName("a run becoming claimable reaches a runner on the stream without it waiting "
            + "out its tick")
    void theStreamCarriesTheWakeUp() throws Exception {
        CountDownLatch performed = new CountDownLatch(1);
        StepService service = new StepService() {
            @Override
            public String step() {
                return STEP;
            }

            @Override
            public Outcome perform(Work work) {
                performed.countDown();
                return Outcome.done(Map.of("assayed", 1L));
            }
        };

        Run waiting = runFor("woken-once");
        // Held before the runner starts, so its first cycle finds nothing to
        // take and it goes to sleep. Without this the run would be picked up
        // by the cycle every runner performs on starting, and the test would
        // pass whether or not a wake-up exists — which is the failure mode a
        // timing test is most likely to have.
        HttpLane courierLane = courier();
        assertTrue(courierLane.claim(waiting, Duration.ofMinutes(10)).isPresent(),
                "the courier could not hold the run, so the runner would find it on its first "
                        + "cycle and this test would prove nothing");

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(10), NEVER_POLLED_IN_TIME);
                StreamLane stream = StreamLane.holding(substrate, TENANT, "analyser",
                        executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            assertTrue(stream.wakeups().isPresent(),
                    "the stream lane offers no wake-ups, so nothing below can be true of it");
            runner.register(service);
            runner.attach(stream);
            runner.start();

            // Asleep: the first cycle is done and the next is ten minutes
            // away. Asserted by the run still being held rather than by the
            // clock, so a slow machine waits longer instead of failing.
            Thread.sleep(2_000);
            assertEquals(1, performed.getCount(),
                    "the runner performed work that was claimed by somebody else");

            // The store making a run claimable, through its own machinery.
            courierLane.released(waiting, "handing it to whoever is listening");

            assertTrue(performed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
                    "a run became claimable and the runner slept through it: the wake-up did "
                            + "not cross the stream, and the only thing left to notice it is a "
                            + "poll " + NEVER_POLLED_IN_TIME.toMinutes() + " minutes away. "
                            + "Nothing is lost when this happens, which is why it needs a test "
                            + "of its own — the work would still be done, eventually, by the "
                            + "fallback nobody was measuring");
        }
    }

    // ------------------------------------------------------------ plumbing

    private static HttpLane courier() {
        return HttpLane.holding(laneUri, () -> token("courier", "courier-secret"), TENANT,
                "courier", executor("courier"), null, null);
    }

    private static Run runFor(String key) {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"specimen\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return runs.of(ASSAY, RunKind.PIPELINE, key, Map.of("specimen", "Basic/" + specimen));
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
            return Extracted.tokenIn(body);
        } catch (Exception unreachable) {
            throw new IllegalStateException("no token for " + clientId, unreachable);
        }
    }
}
