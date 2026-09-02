package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.StoreUnreachableException;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A host that is not the container holds a lane, and the runner cannot tell
 *.
 *
 * <p>{@code Lane.inProcess} needs {@code Runs} and a {@code ChangeFeed}, which
 * an appliance running dbo in-JVM has and a cloud does not — its dbo is a
 * separate deployment precisely so the application never holds
 * {@code CREATE DATABASE}. That left the side which <em>serves</em> work as
 * the side that could not obtain a lane at all. The answer is this: the
 * tenant serves the participation verbs on its own private surface, and a
 * host reaches them with {@link HttpLane}.
 *
 * <p>So the test drives the real {@link StepRunner} against a real tenant
 * runtime over real HTTP, with a real token. Deliberately not a stub of the
 * grant seam: the failure this migration keeps producing is a surface that
 * was built and never mounted, and only a token going through the tenant's
 * own authority can tell a mounted lane from a class that compiles.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ALaneOverHttpIsIndistinguishableIT {

    // module.process, so that process + "." + step is a whole StepId.
    private static final String PROCESS = "dbo.lab";
    private static final String STEP = "validate-over-http";
    private static final String TENANT = "lanehost";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static Runs runs;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-lane-http");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ALaneOverHttpIsIndistinguishableIT"),
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
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        runs = new Runs(manager.runtime(TENANT).orElseThrow().engine());
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
    @DisplayName("a runner driving a lane it holds over HTTP does the work, and every verb "
            + "lands on the tenant's own store")
    @Proving({DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
            DboPromises.PROC_STEP_SERVICE_EMBEDDABLE})
    void theRunnerCannotTellItIsOverHttp() throws Exception {
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/over-http",
                List.of(WorkModel.DOMAIN));
        AtomicReference<Work> received = new AtomicReference<>();
        Lane lane = hostLane("runner-over-http", bootstrapToken());

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Optional<StepDeclaration> declaration() {
                    // Nothing installed this step, so it reaches the catalogue
                    // only by crossing as an introduction.
                    return Optional.of(StepDeclaration.of(PROCESS + "." + STEP, "1.0",
                            WorkModel.DOMAIN));
                }

                @Override
                public Outcome perform(Work handed) {
                    received.set(handed);
                    handed.progress().milestone("validated", Map.of("read", 2L));
                    return Outcome.done(Map.of("validated", 1L));
                }
            });
            runner.attach(lane);

            Eventually.cycling(runner, "the service was handed work over the surface",
                    () -> received.get() != null);
        }

        assertTrue(received.get() != null, "the service was handed work over the surface");
        Run after = runs.byId(work.id()).orElseThrow();
        assertFalse(after.open(), "the run is closed, not parked: " + after.holder());
        assertEquals(1L, after.tally().get("validated"),
                "the tally landed on the tenant's record: " + after.tally());
        // The verb that must never be inherited: a lane that degraded it to a
        // bare checkpoint would pass everything above and drop the one thing
        // the step said about itself.
        assertEquals("validated", after.milestone().name(),
                "and the milestone survived the wire: " + after.milestone());
    }

    @Test
    @DisplayName("the entitlement is the credential's: a bounded participant is refused with "
            + "the lane's own reason, not with an empty queue")
    @Proving({DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
            DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED})
    void theCredentialDecidesTheReach() throws Exception {
        // Bounded at issue, not at hand-out: the credential covers one step,
        // and it is not this one.
        String token = participant("bench-elsewhere", "work/dbo.lab.something-else");
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/refused",
                List.of(WorkModel.DOMAIN));
        Lane bounded = hostLane("bench-elsewhere", token);

        assertTrue(bounded.poll(java.util.Set.of(STEP), 10).isEmpty(),
                "poll offers nothing outside the entitlement");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bounded.claim(work, Duration.ofMinutes(5)));
        assertTrue(refused.getMessage().contains("not entitled"),
                "the lane's own reason crossed intact rather than becoming an empty "
                        + "answer: " + refused.getMessage());
    }

    @Test
    @DisplayName("a credential bounded to steps may work only as itself")
    @Proving(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS)
    void aBoundedCredentialCannotWorkAsSomebodyElse() throws Exception {
        String token = participant("bench-itself", "work/" + PROCESS + "." + STEP);
        // The executor identity is what a claim is recorded under and what
        // `inputs` checks against, so a bounded credential free to spell any
        // name could read the inputs of runs it never claimed.
        Lane impersonating = HttpLane.to(laneUri, () -> token, TENANT, "bench-itself",
                new Executor("someone-else", "1.0", "cloud.jengu.test", Scope.BASELINE));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> impersonating.poll(java.util.Set.of(STEP), 10));
        assertTrue(refused.getMessage().contains("may work as itself"),
                "refused by name: " + refused.getMessage());
    }

    @Test
    @DisplayName("a credential with no participation scope reaches no lane, and is told so")
    @Proving(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS)
    void aStoreCredentialIsNotAParticipationCredential() throws Exception {
        String token = participant("reads-only", "system/*.read");
        Lane none = hostLane("reads-only", token);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> none.poll(java.util.Set.of(STEP), 10));
        // 403 and not 404: the surface is mounted and guarded, which is the
        // distinction "booting is not serving" exists to make.
        assertTrue(refused.getMessage().contains("403"),
                "the lane is there and refused this credential: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("participation scope"),
                "and said which grant is missing: " + refused.getMessage());
    }

    @Test
    @DisplayName("a host serving a lane for somebody else may narrow it to what that "
            + "participant was granted, and may not widen it past its own credential")
    @Proving({DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
            DboPromises.PROC_CLAIM_IS_THE_INTERSECTION})
    void aHostMayNarrowTheLaneItServesButNeverWidenIt() throws Exception {
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/narrowed",
                List.of(WorkModel.DOMAIN));

        // The tenant's own credential, serving a lane on behalf of a bench
        // that was granted some OTHER step at enrolment. The reach that
        // belongs on the lane is the bench's, not the host's.
        String host = bootstrapToken();
        Lane narrowed = HttpLane.boundedTo(laneUri, () -> host, TENANT, "bench-narrowed",
                new Executor("bench-narrowed", "1.0", "cloud.jengu.test", Scope.BASELINE),
                java.util.Set.of("dbo.lab.a-different-step"));
        assertTrue(narrowed.poll(java.util.Set.of(STEP), 10).isEmpty(),
                "the narrowing took effect at the store, not in the asker's own head");
        assertThrows(IllegalStateException.class,
                () -> narrowed.claim(work, Duration.ofMinutes(5)),
                "and a claim outside it is refused");

        // The other direction: a credential bounded to one step cannot ask
        // itself into another. What is asked for is intersected with what the
        // credential covers, never substituted for it.
        String token = participant("bench-asking-for-more", "work/dbo.lab.something-else");
        Lane widened = HttpLane.boundedTo(laneUri, () -> token, TENANT, "bench-asking-for-more",
                new Executor("bench-asking-for-more", "1.0", "cloud.jengu.test", Scope.BASELINE),
                java.util.Set.of(PROCESS + "." + STEP));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> widened.claim(work, Duration.ofMinutes(5)));
        assertTrue(refused.getMessage().contains("not entitled"),
                "asking for a step the credential does not cover grants nothing: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("a refusal and a store that did not answer are different exceptions, "
            + "because a bench must stop asking for one and keep asking for the other")
    @Proving(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED)
    void aRefusalIsNotAnUnansweredCall() throws Exception {
        // Settled: this credential holds no participation scope and never will.
        // Backing off is the right answer, so it must NOT read as transient.
        String token = participant("reads-only-too", "system/*.read");
        Lane refused = hostLane("reads-only-too", token);
        IllegalStateException settled = assertThrows(IllegalStateException.class,
                () -> refused.poll(java.util.Set.of(STEP), 10));
        assertFalse(settled instanceof StoreUnreachableException,
                "a decision about the caller is settled: " + settled.getMessage());

        // Unanswered: nothing is listening. The verb is retryable, and a bench
        // that read this as a refusal would stop taking work it is entitled to
        // — and lose the claim it holds when the deadline passes.
        int dead;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            dead = free.getLocalPort();
        }
        Lane unreachable = HttpLane.to(
                URI.create("http://127.0.0.1:" + dead + "/t/" + TENANT + "/work"),
                () -> token, TENANT, "bench-offline",
                new Executor("bench-offline", "1.0", "cloud.jengu.test", Scope.BASELINE));
        assertThrows(StoreUnreachableException.class,
                () -> unreachable.poll(java.util.Set.of(STEP), 10),
                "a store that never spoke is not a decision about anybody");
    }

    private static Lane hostLane(String participant, String token) {
        return HttpLane.to(laneUri, () -> token, TENANT, participant,
                new Executor(participant, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    /** A participation credential minted for one bench, bounded at issue. */
    private static String participant(String clientId, String... scopes) throws Exception {
        String secret = clientId + "-secret";
        manager.authority(TENANT).ensureClient(clientId, secret, List.of(scopes));
        return token(clientId, secret);
    }

    private static String bootstrapToken() throws Exception {
        return token("tenant-bootstrap", provisioner.bootstrapClientSecret(TENANT));
    }

    private static String token(String clientId, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + TENANT + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
