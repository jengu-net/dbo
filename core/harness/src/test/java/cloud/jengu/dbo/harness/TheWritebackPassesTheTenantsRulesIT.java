package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.stream.LaneClaims;
import cloud.jengu.dbo.stream.StepConsumer;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work the deployment performed comes home through the tenant's own rules.
 *
 * <p>The closing property of this design, and the one it would be easiest to
 * get wrong: a fleet consumer is inside the deployment, so nothing stops it
 * writing to a tenant's store directly. If it did, a tenant's rules about its
 * own work would hold for everybody except the party doing most of it.
 *
 * <p>So the writeback is an ordinary lane, and this asserts both halves of
 * what that buys — a run closing in the tenant that authored it and naming the
 * performer, and a report that breaks one of that tenant's rules refused
 * exactly as it would be on a lane.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheWritebackPassesTheTenantsRulesIT {

    /**
     * A NAME OF THIS CLASS'S OWN, asserted on below.
     *
     * <p>The tenant is shared and a participant name is claimed by identity: a
     * neighbour enrolling "performing-bean" WITH a key made this class's inputs
     * travel sealed, and the refusal it asserts on came back as a sealing
     * complaint rather than the action the step never declared. Sharing a world
     * is what turned that from a coincidence into a rule.
     */
    private static final String PERFORMER = "writeback-bean";

    private static final String STEP = SharedTenants.Fleet.WRITTEN_BACK.code();
    /** Declares OPEN and not CLOSE, so closing it is a rule to break. */
    private static final String JUDGED = SharedTenants.Fleet.WRITTEN_BACK_JUDGED.code();

    private static final StepDeclaration SWEEP =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration REVIEW =
            StepDeclaration.of(JUDGED, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record")
                    .containing("open");

    static SharedTenants.Tenant tenant;
    static String TENANT;
    StepConsumer consumer;

    @BeforeAll
    void up() {
        // The shared deployment declares both steps; this class needs a tenant
        // to author runs in and nothing else of its own.
        SharedTenants.deploymentPerforms();
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        TENANT = tenant.code();

        SharedTenants.manager().fleetLane(TENANT, STEP, performer()).orElseThrow().introduce(SWEEP);
        SharedTenants.manager().fleetLane(TENANT, JUDGED, performer()).orElseThrow().introduce(REVIEW);
    }

    /** What an application performing these steps calls itself. */
    private static Executor performer() {
        return new Executor(PERFORMER, "1", "cloud.jengu.test", Scope.BASELINE);
    }

    @AfterAll
    void down() {
        // The consumer only. The runtime is shared and outlives this class —
        // closing it would pull the floor out from under whatever is running
        // beside it.
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("a run closes in the tenant that authored it, naming the performer the "
            + "application gave and not the deployment")
    @Proving(DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES)
    void itClosesNamingTheExecutor() throws Exception {
        Run authored = authorRun(SWEEP);
        SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(100);

        // BOTH steps on one consumer, which is only possible because they
        // name one substrate: a queue lives on the substrate its step named,
        // and a consumer reaches the queues on the database it was given.
        consumer = new StepConsumer(SharedTenants.manager().stepSubstrates().get(STEP), Set.of(STEP, JUDGED),
                claims());
        consumer.performing(cloud.jengu.dbo.runner.StepService.performing(STEP,
                work -> cloud.jengu.dbo.runner.Outcome.done(Map.of("swept", 1L))));

        assertTrue(until(() -> runs().byKey(authored.key())
                .map(run -> run.holder() == Holder.NOBODY).orElse(false)),
                "the run never closed in the tenant that authored it, so work the deployment "
                        + "performed is not on that tenant's record: "
                        + runs().byKey(authored.key()));

        Run closed = runs().byKey(authored.key()).orElseThrow();
        assertEquals(Map.of("swept", 1L), closed.tally(),
                "what the performer counted did not come home with the closure");
        assertTrue(entriesFor(closed).stream().anyMatch(e -> e.contains(PERFORMER)),
                "the run does not name the performer the application gave, so a deployment "
                        + "has stamped its own name on work a bean did: " + entriesFor(closed));
    }

    @Test
    @Order(2)
    @DisplayName("a report the step does not declare is refused exactly as it would be on a "
            + "lane, naming the action")
    @Proving(DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES)
    void aReportBreakingARuleIsRefused() throws Exception {
        Run authored = authorRun(REVIEW);

        // THROUGH THE WRITEBACK AND NOT THROUGH THE QUEUE, deliberately. What
        // is being proven is that an outcome from a fleet consumer meets the
        // tenant's rules; that an item reaches a consumer at all is step
        // five's claim and is proven there. Going through the queue here would
        // make this test fail for the other reason as well as this one, and a
        // test that can fail two ways proves neither.
        cloud.jengu.dbo.stream.FleetPerformer.Held held = claims()
                .held(TENANT, JUDGED, authored.key(), java.time.Duration.ofMinutes(5))
                .orElseThrow(() -> new AssertionError(
                        "no lane into the tenant for a run it authored, so nothing could be "
                                + "reported and nothing refused"));

        // A service that says it is done, over a step whose declaration does
        // not admit closing. The refusal is the LANE's, and what comes back is
        // the run released with its words — the same thing a runner does with a
        // report the tenant will not take, because both go through one mapping.
        cloud.jengu.dbo.runner.Outcome said = cloud.jengu.dbo.runner.Performing.performed(
                held.lane(), held.run(),
                cloud.jengu.dbo.runner.StepService.performing(JUDGED,
                        work -> cloud.jengu.dbo.runner.Outcome.done(Map.of("reviewed", 1L))),
                java.time.Duration.ofMinutes(5));

        assertTrue(said instanceof cloud.jengu.dbo.runner.Outcome.Failed,
                "a step declaring 'open' and not 'close' was closed by a fleet consumer, so a "
                        + "tenant's rules hold for everybody except the party doing most of "
                        + "its work: " + said);
        RuntimeException refusal = new IllegalStateException(
                ((cloud.jengu.dbo.runner.Outcome.Failed) said).reason());

        assertTrue(String.valueOf(refusal.getMessage()).contains("close"),
                "the refusal does not name the action the step never declared, which is the "
                        + "one thing somebody has to add: " + refusal.getMessage());
        assertTrue(runs().byKey(authored.key())
                        .map(run -> run.holder() != Holder.NOBODY).orElse(false),
                "the run closed anyway, so the refusal was a message rather than a rule");
    }

    private cloud.jengu.dbo.stream.FleetPerformer.Claims claims() {
        return new LaneClaims(
                (tenant, step) -> SharedTenants.manager().fleetLane(tenant, step, performer()),
                (tenant, runKey) -> new Runs(
                        SharedTenants.manager().runtime(tenant).orElseThrow().engine()).byKey(runKey));
    }

    private Runs runs() {
        return new Runs(tenant.engine());
    }

    private Run authorRun(StepDeclaration step) {
        var engine = tenant.engine();
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return new Runs(engine).of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private java.util.List<String> entriesFor(Run run) {
        java.util.List<String> out = new java.util.ArrayList<>();
        tenant.engine()
                .select(cloud.jengu.dbo.core.api.Criteria.of("AuditEntry")).forEach(o -> {
                    String e = new String(o.payload(), StandardCharsets.UTF_8);
                    if (e.contains(run.id())) {
                        out.add(e);
                    }
                });
        return out;
    }

    private static boolean until(java.util.function.BooleanSupplier done) throws Exception {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < giveUp) {
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
