package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.stream.StepConsumer;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One bean, work from two tenants, and it names neither.
 *
 * <p>This is the step where the design's claim becomes visible: an
 * application performing a step for a fleet holds ONE consumer rather than a
 * lane per tenant, and is handed items that happen to name different tenants.
 * A bean that had to be told which tenants exist would need redeploying
 * whenever one joined, which is most of what this exists to stop.
 *
 * <p>It also proves the two ends agree without having been introduced: the
 * joiner writes items with a {@code DBOSClient} that registers nothing, and
 * the consumer picks them up because both name one class and one queue.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OneBeanPerformsForEveryTenantIT {

    private static final String ONE = "joinone";
    private static final String TWO = "jointwo";
    /**
     * ITS OWN CODE, not shared with the class beside it. A step's substrate is
     * a DATABASE named from the step code, and these tests run against one
     * Postgres instance — so two classes naming one step would perform each
     * other's work and count each other's items. A deployment owns its
     * instance, which is the same assumption a tenant's database already
     * makes; a test suite does not.
     */
    private static final String STEP = "fleet.tidying.sweep";
    /** A second step on the SAME substrate, which is what placement is for. */
    private static final String BESIDE_IT = "fleet.tidying.expire";

    private static final StepDeclaration EXPIRE =
            StepDeclaration.of(BESIDE_IT, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration SWEEP =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    StepConsumer consumer;
    final ConcurrentLinkedQueue<String> performed = new ConcurrentLinkedQueue<>();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-onebean");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("OneBeanPerformsForEveryTenantIT"),
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
                   {"code":"%s","slots":{"record":"Reference(Basic)"},"opens":["record"],
                    "substrate":"tidying"},
                   {"code":"%s","slots":{"record":"Reference(Basic)"},"opens":["record"],
                    "substrate":"tidying"}]}"""
                .formatted(STEP, BESIDE_IT));
        manager.manages(managementSpec);

        for (String tenant : List.of(ONE, TWO)) {
            Files.writeString(dir.resolve(tenant + ".json"), """
                    {"code":"%s","face":"r4","types":[
                       {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                    .formatted(tenant));
        }
        UntilServed.scan(manager, ONE, TWO);
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
    @DisplayName("one bean performs work authored in two tenants, naming neither")
    @Proving(DboPromises.PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT)
    void oneBeanTwoTenants() throws Exception {
        authorRunIn(ONE);
        authorRunIn(TWO);
        assertTrue(manager.stepJoiner().orElseThrow().joinOnce(100) >= 2,
                "the joiner offered fewer than the two runs authored, so this proves nothing "
                        + "about a consumer");

        // The application's side, and all of it: one consumer over the step's
        // substrate, one bean, and no tenant named anywhere in either.
        // BOTH STEPS, because a consumer polls the queues it was built with
        // and registering a bean later does not add one. Which is also the
        // placement claim: two steps sharing a substrate are served by one
        // consumer, one listener and one pool.
        consumer = new StepConsumer(manager.stepSubstrates().get(STEP),
                Set.of(STEP, BESIDE_IT), claims());
        consumer.performing(cloud.jengu.dbo.runner.StepService.performing(STEP, work -> {
            performed.add(work.tenant() + "/" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));

        assertTrue(until(() -> performed.size() >= 2),
                "one bean over one queue did not perform both tenants' work: " + performed);

        assertEquals(Set.of(ONE, TWO),
                performed.stream().map(done -> done.split("/")[0])
                        .collect(java.util.stream.Collectors.toSet()),
                "the two items performed were not one from each tenant, so a queue carrying "
                        + "a fleet's work is carrying one tenant's: " + performed);
    }

    @Test
    @Order(2)
    @DisplayName("a consumer restarted keeps nothing, and work authored while it was gone is "
            + "performed when it comes back")
    @Proving(DboPromises.PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT)
    void itKeepsNothingBetweenAsks() throws Exception {
        consumer.close();
        consumer = null;
        performed.clear();

        authorRunIn(ONE);
        manager.stepJoiner().orElseThrow().joinOnce(100);

        consumer = new StepConsumer(manager.stepSubstrates().get(STEP),
                Set.of(STEP, BESIDE_IT), claims());
        consumer.performing(cloud.jengu.dbo.runner.StepService.performing(STEP, work -> {
            performed.add(work.tenant() + "/" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));

        assertTrue(until(() -> !performed.isEmpty()),
                "work offered while the consumer was down was not performed when it came "
                        + "back, so a restart loses whatever was in flight");
    }

    /**
     * Where a report would go. This class is about the consumer side and not
     * about the writeback, so the beans here report nothing — but a consumer
     * cannot be built without one, and handing it a real one keeps this test
     * honest about what an application actually assembles.
     */
    private cloud.jengu.dbo.stream.FleetPerformer.Claims claims() {
        return new cloud.jengu.dbo.stream.LaneClaims(
                (tenant, step) -> manager.fleetLane(tenant, step,
                        new cloud.jengu.dbo.work.Executor("fleet-test", "1",
                                "cloud.jengu.test", cloud.jengu.dbo.work.Scope.BASELINE)),
                (tenant, runKey) -> new Runs(manager.runtime(tenant).orElseThrow().engine())
                        .byKey(runKey));
    }

    @Test
    @Order(3)
    @DisplayName("one consumer serves every step on the substrate it was given, and takes work "
            + "that arrives while it is already running")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void oneConsumerServesTheSubstratesQueues() throws Exception {
        // WHAT PLACEMENT IS FOR. Two steps naming one substrate share a
        // database and a pool, and that trade is only real if one consumer can
        // serve both their queues — otherwise a deployment pays a listener and
        // a pool per STEP however it places them, and naming a substrate buys
        // nothing.
        //
        // And it takes work that arrives while it is up, which is the other
        // half: a consumer that drained only what existed when it launched
        // would make the whole lane a startup activity.
        performed.clear();
        assertEquals(manager.stepSubstrates().get(STEP),
                manager.stepSubstrates().get(BESIDE_IT),
                "the two steps named one substrate and got two, so this proves nothing about "
                        + "serving both from one consumer");

        // BOTH beans recording the same way, so what is counted is which STEP
        // performed rather than which of two spellings a bean happened to use.
        consumer.performing(cloud.jengu.dbo.runner.StepService.performing(STEP, work -> {
            performed.add(STEP + "|" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));
        consumer.performing(cloud.jengu.dbo.runner.StepService.performing(BESIDE_IT, work -> {
            performed.add(BESIDE_IT + "|" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));

        authorRunIn(ONE, SWEEP);
        authorRunIn(ONE, EXPIRE);
        manager.stepJoiner().orElseThrow().joinOnce(200);

        assertTrue(until(() -> performed.size() >= 2),
                "one consumer did not perform both steps' work from the substrate they share: "
                        + performed);
        assertEquals(Set.of(STEP, BESIDE_IT),
                performed.stream().map(done -> done.split("\\|")[0])
                        .collect(java.util.stream.Collectors.toSet()),
                "the two items performed were not one of each step: " + performed);
    }

    @Test
    @Order(4)
    @DisplayName("two consumers on one substrate each take only their own step's work, and "
            + "neither drains the other's")
    @Proving(DboPromises.PROC_A_CONSUMER_TAKES_ONLY_ITS_OWN_STEPS)
    void aConsumerTakesOnlyItsOwnSteps() throws Exception {
        consumer.close();
        performed.clear();
        java.util.concurrent.ConcurrentLinkedQueue<String> other =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        // TWO PROCESSES' WORTH, on one substrate: the shape a deployment takes
        // when it scales one step and leaves the other alone. Each registers
        // its own step, and a queue registered by either is visible to both in
        // the system database — which is exactly the trap.
        try (StepConsumer mine = new StepConsumer(manager.stepSubstrates().get(STEP),
                        Set.of(STEP), claims());
                StepConsumer theirs = new StepConsumer(manager.stepSubstrates().get(BESIDE_IT),
                        Set.of(BESIDE_IT), claims())) {
            mine.performing(cloud.jengu.dbo.runner.StepService.performing(STEP, work -> {
                performed.add(STEP);
                return cloud.jengu.dbo.runner.Outcome.done();
            }));
            theirs.performing(cloud.jengu.dbo.runner.StepService.performing(BESIDE_IT, work -> {
                other.add(BESIDE_IT);
                return cloud.jengu.dbo.runner.Outcome.done();
            }));

            assertThrows(IllegalArgumentException.class,
                    () -> mine.performing(cloud.jengu.dbo.runner.StepService.performing(BESIDE_IT,
                            work -> cloud.jengu.dbo.runner.Outcome.done())),
                    "a bean was accepted for a step this consumer does not poll, so it would "
                            + "sit there correct and never be called");

            authorRunIn(ONE, SWEEP);
            authorRunIn(ONE, EXPIRE);
            manager.stepJoiner().orElseThrow().joinOnce(200);

            assertTrue(until(() -> !performed.isEmpty() && !other.isEmpty()),
                    "one of the two steps' work never arrived: mine=" + performed
                            + " theirs=" + other);
            assertEquals(List.of(STEP), List.copyOf(performed),
                    "a consumer performed work of a step it does not serve: " + performed);
            assertEquals(List.of(BESIDE_IT), List.copyOf(other),
                    "a consumer performed work of a step it does not serve: " + other);
        }
    }

    private void authorRunIn(String tenant) {
        authorRunIn(tenant, SWEEP);
    }

    private void authorRunIn(String tenant, StepDeclaration step) {
        var engine = manager.runtime(tenant).orElseThrow().engine();
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        new Runs(engine).of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
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
