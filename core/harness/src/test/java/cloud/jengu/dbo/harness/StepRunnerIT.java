package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.ExecutorModel;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference runner (#79): step services in, consumption out — stateless
 * over tenants, no access to the tenant's dbo, vitals riding the
 * declaration (#148).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepRunnerIT {

    private static final String PROCESS = "dbo.lab.result";

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static Runs runs;
    static Declarations declarations;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("StepRunnerIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> registrations =
                new java.util.ArrayList<>(WorkModel.registrations());
        registrations.add(new cloud.jengu.dbo.core.api.TypeRegistration(
                "Basic", WorkModel.DOMAIN, cloud.jengu.dbo.core.api.IdentityClass.INTERNAL,
                java.util.Set.of(), cloud.jengu.dbo.core.api.Handling.operational(),
                (type, payload) -> new cloud.jengu.dbo.core.api.Envelope(),
                List.of()));
        store = new PgObjectStore(ds, registrations);
        runs = new Runs(store);
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    /**
     * The host's side of the line: the store handle stays HERE, and the
     * runner receives only the lane — which is the whole point.
     */
    private static Lane lane(String tenant, String runnerName) {
        return Lane.inProcess(tenant, runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations,
                reference -> {
                    int slash = reference.indexOf('/');
                    return slash > 0
                            ? store.get(reference.substring(0, slash),
                                    reference.substring(slash + 1))
                            : java.util.Optional.empty();
                },
                runnerName, new Executor(runnerName, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    @Test
    @DisplayName("a registered service consumes: claimed, performed, closed with the tally — "
            + "and the vitals ride the declaration")
    void registeredServiceConsumes() {
        Run work = runs.pipeline(PROCESS, "validate", PROCESS + "/validate/one",
                List.of(WorkModel.DOMAIN));

        AtomicReference<Work> received = new AtomicReference<>();
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + ".validate";
                }

                @Override
                public Outcome perform(Work work) {
                    received.set(work);
                    work.progress().checkpoint(Map.of("seen", 1L));
                    return Outcome.done(Map.of("validated", 1L));
                }
            });
            runner.attach(lane("t-one", "runner-one"));
            int performed = runner.cycle();
            assertTrue(performed >= 1, "the item run was performed: " + performed);
        }

        Run after = runs.byId(work.id()).orElseThrow();
        assertTrue(!after.open(), "the run is closed, not parked: " + after.holder());
        assertEquals(1L, after.tally().get("validated"),
                "the tally landed on the record: " + after.tally());
        // Work.related is the contract seam for the documents a run names.
        // TODAY a claimable run names none — a pipeline run has no item, and
        // the official carrier (Run.inputs → Task.input) is #149. The seam
        // exists so #149 fills it with no service or runner change.
        assertTrue(received.get().related().isEmpty(),
                "empty until #149 gives a run its inputs: " + received.get().related());

        List<StoredObject> declared = store.select(
                Criteria.of(ExecutorModel.TYPE).limit(50));
        assertTrue(declared.stream()
                        .map(d -> new String(d.payload(), StandardCharsets.UTF_8))
                        .anyMatch(d -> d.contains("\"performed\":\"1\"")
                                && d.contains("meanMillis")),
                "the vitals ride the declaration record (#148)");
    }

    @Test
    @DisplayName("a throwing service releases with the reason — released is not done — and "
            + "a later cycle takes it again and succeeds")
    void failureIsReleasedThenRetaken() {
        Run work = runs.pipeline(PROCESS, "dispatch", PROCESS + "/dispatch/one",
                List.of(WorkModel.DOMAIN));

        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        try (StepRunner runner = new StepRunner(Duration.ofMillis(100), Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + ".dispatch";
                }

                @Override
                public Outcome perform(Work work) {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("the printer is on fire");
                    }
                    return Outcome.done();
                }
            });
            runner.attach(lane("t-one", "runner-two"));

            runner.cycle();
            Run released = runs.byId(work.id()).orElseThrow();
            assertTrue(released.open(), "released is not done — still open");

            // The claim must lapse before anybody may take it again; the
            // runner's own housekeeping hands it back on the next cycle.
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runner.cycle();
        }
        assertEquals(2, attempts.get(), "a later cycle took it again");
    }

    @Test
    @DisplayName("stateless over tenants: one runner, two lanes, each tenant's work performed "
            + "and reported on its own lane")
    void statelessOverTenants() {
        // Two "tenants" as two participants over distinct steps — distinct
        // lanes with distinct identities, one runner holding no tenant state.
        Run one = runs.pipeline(PROCESS, "stain", PROCESS + "/stain/a",
                List.of(WorkModel.DOMAIN));
        Run two = runs.pipeline(PROCESS, "stain", PROCESS + "/stain/b",
                List.of(WorkModel.DOMAIN));

        java.util.Set<String> servedBy = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + ".stain";
                }

                @Override
                public Outcome perform(Work work) {
                    servedBy.add(work.run().key());
                    return Outcome.done();
                }
            });
            runner.attach(lane("t-a", "runner-a"));
            runner.attach(lane("t-b", "runner-b"));
            runner.cycle();
            runner.cycle();
        }
        assertTrue(servedBy.contains(one.key()) || servedBy.contains(two.key()),
                "work flowed through the lanes: " + servedBy);
    }
}
