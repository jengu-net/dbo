package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.FleetWork;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application writes a bean. The container writes everything else.
 *
 * <p>Every other class about the fleet builds a {@code StepConsumer} by hand,
 * which is honest about what a consumer does and dishonest about what an
 * application does: a bean handed to a consumer somebody constructed is a bean
 * that could only ever be reached by the code that constructed it. This is the
 * class that asks the reachability question — who builds the consumer in a
 * deployment nobody wrote a test harness for — and the answer has to be the
 * deployment.
 *
 * <p>So nothing here constructs a consumer, a durable layer or a pool. A bean
 * is registered, and the proof is that work arrives at it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ABeanIsFoundRatherThanWiredIT {

    private static final String TENANT = "foundbean";
    /** Its own code, because a step's substrate is a database named from it. */
    private static final String STEP = "fleet.found.sweep";
    /** Placed on the same substrate, to prove one consumer comes of two steps. */
    private static final String BESIDE_IT = "fleet.found.expire";
    /** Declared by no deployment anywhere, which is the point of it. */
    private static final String NEVER_DECLARED = "fleet.found.nothing";

    private static final StepDeclaration SWEEP =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration EXPIRE =
            StepDeclaration.of(BESIDE_IT, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    private static final Executor AS =
            new Executor("found-bean", "1", "cloud.jengu.test", Scope.BASELINE);

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Runs runs;
    final ConcurrentLinkedQueue<String> swept = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> expired = new ConcurrentLinkedQueue<>();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-foundbean");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ABeanIsFoundRatherThanWiredIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        // THE BEANS FIRST, before the deployment has read a declaration. This
        // is the order an assembly actually produces — the application's beans
        // are constructed while the container is still coming up — and a
        // registration refused here would make the whole arrangement work only
        // in a test that happened to go the other way round.
        manager.performing(bean(STEP, swept), AS);
        manager.performing(bean(BESIDE_IT, expired), AS);
        manager.performing(bean(NEVER_DECLARED, new ConcurrentLinkedQueue<>()), AS);

        Path managementSpec = Files.createTempDirectory("dbo-management").resolve("reg.json");
        Files.writeString(managementSpec, """
                {"code":"reg","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "fleetSteps":[
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],
                    "substrate":"found"},
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],
                    "substrate":"found"}]}"""
                .formatted(STEP, BESIDE_IT));
        manager.manages(managementSpec);

        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        runs = new Runs(manager.runtime(TENANT).orElseThrow().engine());
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
    @DisplayName("a bean registered before the declaration was read is performing work after "
            + "it, and is handed the object the run referred to")
    @Proving({DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED,
            DboPromises.PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS})
    void heldThenTakenUp() throws Exception {
        authorRunOf(SWEEP);
        assertTrue(manager.stepJoiner().orElseThrow().joinOnce(100) >= 1,
                "the joiner offered nothing, so this proves nothing about the bean");

        assertTrue(until(() -> !swept.isEmpty()),
                "the bean registered before the deployment declared its steps was never "
                        + "called, so an application whose beans come up first performs "
                        + "nothing — which is the ordinary order under an assembly");

        // AND IT WAS HANDED THE OBJECT. Being called is not the claim: a
        // performer runs outside the store with no route into the tenant, so a
        // bean called with an empty map has been given the fact that there is
        // work and nothing to do it with — which looks identical from here
        // unless the slot is asserted.
        assertTrue(swept.stream().anyMatch(done -> done.endsWith("[record]")),
                "the bean was called with no slots, so the run's inputs were never resolved "
                        + "and the reference it was authored with reached a process that "
                        + "cannot resolve one: " + swept);
    }

    @Test
    @Order(2)
    @DisplayName("two steps placed on one substrate are served without a second consumer, and "
            + "without the application knowing either was placed")
    @Proving(DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED)
    void oneConsumerForTheSubstrate() throws Exception {
        // A consumer's queues are fixed when it launches. Registering the
        // second bean therefore only works if the first registration built a
        // consumer over EVERY step its substrate carries — which is a decision
        // the application cannot make, because placement is the deployment's.
        authorRunOf(EXPIRE);
        manager.stepJoiner().orElseThrow().joinOnce(100);

        assertTrue(until(() -> !expired.isEmpty()),
                "the step placed beside the first was never performed, so a deployment that "
                        + "places two steps together gets one of them performed");
    }

    @Test
    @Order(3)
    @DisplayName("a bean whose step nothing declares is named rather than left quiet")
    @Proving(DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED)
    void whatWillNeverBeCalledIsNamed() {
        assertEquals(Set.of(NEVER_DECLARED), manager.awaitingDeclaration(),
                "a bean naming a step this deployment does not declare is indistinguishable "
                        + "from a step with nothing to do, and the deployment is the only "
                        + "thing that can tell the difference");
    }

    /** A bean that records what it was handed and reports nothing. */
    private FleetWork.Performer bean(String step, ConcurrentLinkedQueue<String> into) {
        return new FleetWork.Performer() {
            @Override
            public String step() {
                return step;
            }

            @Override
            public void perform(String tenant, String code, String runId, String runKey,
                    java.util.Map<String, cloud.jengu.dbo.core.api.StoredObject> inputs,
                    FleetWork.Reporting reporting) {
                // THE SLOT, not just the fact of being called. A bean handed
                // an empty map would look exactly like a bean handed its work,
                // and the whole point of reaching it is that it can do
                // something — so what is recorded is that the object arrived.
                into.add(tenant + "/" + runKey + "/" + inputs.keySet());
            }
        };
    }

    private void authorRunOf(StepDeclaration step) {
        String record = manager.runtime(TENANT).orElseThrow().engine()
                .put(PutRequest.create("Basic",
                        "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                                .getBytes(StandardCharsets.UTF_8))).id();
        runs.of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private static boolean until(java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return done.getAsBoolean();
    }
}
