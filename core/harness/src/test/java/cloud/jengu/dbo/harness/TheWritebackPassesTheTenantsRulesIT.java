package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.stream.FleetWork;
import cloud.jengu.dbo.stream.LaneWriteback;
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

    private static final String TENANT = "writeback";
    private static final String STEP = "fleet.closing.sweep";
    /** Declares OPEN and not CLOSE, so closing it is a rule to break. */
    private static final String JUDGED = "fleet.judged.review";

    private static final StepDeclaration SWEEP =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration REVIEW =
            StepDeclaration.of(JUDGED, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record")
                    .containing("open");

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    StepConsumer consumer;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-writeback");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheWritebackPassesTheTenantsRulesIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        Path managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        Files.writeString(managementSpec, """
                {"code":"registry","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "fleetSteps":[
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],"substrate":"writeback"},
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],"substrate":"writeback"}]}"""
                .formatted(STEP, JUDGED));
        manager.manages(managementSpec);

        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);

        // THE DECLARATIONS, introduced through a lane the way a participant
        // introduces one. Without them the tenant holds runs of steps it has
        // no declaration for, and a report is narrowed by nothing — which
        // would make the refusal below pass for the wrong reason, or rather
        // fail to happen at all.
        manager.fleetLane(TENANT, STEP, performer()).orElseThrow().introduce(SWEEP);
        manager.fleetLane(TENANT, JUDGED, performer()).orElseThrow().introduce(REVIEW);
    }

    /** What an application performing these steps calls itself. */
    private static Executor performer() {
        return new Executor("performing-bean", "1", "cloud.jengu.test", Scope.BASELINE);
    }

    @AfterAll
    void down() {
        if (consumer != null) {
            consumer.close();
        }
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @Order(1)
    @DisplayName("a run closes in the tenant that authored it, naming the performer the "
            + "application gave and not the deployment")
    @Proving(DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES)
    void itClosesNamingTheExecutor() throws Exception {
        Run authored = authorRun(SWEEP);
        manager.stepJoiner().orElseThrow().joinOnce(100);

        // BOTH steps on one consumer, which is only possible because they
        // name one substrate: a queue lives on the substrate its step named,
        // and a consumer reaches the queues on the database it was given.
        consumer = new StepConsumer(manager.stepSubstrates().get(STEP), Set.of(STEP, JUDGED),
                writeback());
        consumer.performing(STEP, (tenant, step, runId, runKey, reporting) ->
                reporting.closed(Map.of("swept", 1L)));

        assertTrue(until(() -> runs().byKey(authored.key())
                .map(run -> run.holder() == Holder.NOBODY).orElse(false)),
                "the run never closed in the tenant that authored it, so work the deployment "
                        + "performed is not on that tenant's record: "
                        + runs().byKey(authored.key()));

        Run closed = runs().byKey(authored.key()).orElseThrow();
        assertEquals(Map.of("swept", 1L), closed.tally(),
                "what the performer counted did not come home with the closure");
        assertTrue(entriesFor(closed).stream().anyMatch(e -> e.contains("performing-bean")),
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
        FleetWork.Reporting reporting = writeback()
                .reporting(TENANT, JUDGED, authored.key())
                .orElseThrow(() -> new AssertionError(
                        "no lane into the tenant for a run it authored, so nothing could be "
                                + "reported and nothing refused"));

        RuntimeException refusal = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> reporting.closed(Map.of("reviewed", 1L)),
                "a step declaring 'open' and not 'close' was closed by a fleet consumer, so a "
                        + "tenant's rules hold for everybody except the party doing most of "
                        + "its work");

        assertTrue(String.valueOf(refusal.getMessage()).contains("close"),
                "the refusal does not name the action the step never declared, which is the "
                        + "one thing somebody has to add: " + refusal.getMessage());
        assertTrue(runs().byKey(authored.key())
                        .map(run -> run.holder() != Holder.NOBODY).orElse(false),
                "the run closed anyway, so the refusal was a message rather than a rule");
    }

    private cloud.jengu.dbo.stream.FleetWork.Writeback writeback() {
        return new LaneWriteback(
                (tenant, step) -> manager.fleetLane(tenant, step, performer()),
                (tenant, runKey) -> new Runs(
                        manager.runtime(tenant).orElseThrow().engine()).byKey(runKey));
    }

    private Runs runs() {
        return new Runs(manager.runtime(TENANT).orElseThrow().engine());
    }

    private Run authorRun(StepDeclaration step) {
        var engine = manager.runtime(TENANT).orElseThrow().engine();
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return new Runs(engine).of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private java.util.List<String> entriesFor(Run run) {
        java.util.List<String> out = new java.util.ArrayList<>();
        manager.runtime(TENANT).orElseThrow().engine()
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
