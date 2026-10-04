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
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference runner: step services in, consumption out — stateless
 * over tenants, no access to the tenant's dbo, vitals riding the
 * declaration.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepRunnerIT {

    private static final String PROCESS = "dbo.lab.result";

    /** A process whose dispatch step is declared, as {@code <module>.<process>}. */
    private static final String DISPATCHING = "lab.result";

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
        // The dispatch step says a printer that does not answer will answer
        // later, which is what lets a failure of it go back to automation.
        runs = new Runs(store, cloud.jengu.dbo.core.process.Steps.of(
                cloud.jengu.dbo.core.process.StepDeclaration.of(DISPATCHING + ".dispatch", "1",
                                WorkModel.DOMAIN)
                        .retrying(new cloud.jengu.dbo.core.process.RetryPolicy(
                                List.of("unreachable"), "PT0S", 3)),
                // The route step says a lapse will pass, so a claim
                // housekeeping hands back is there for another runner to take.
                cloud.jengu.dbo.core.process.StepDeclaration.of(DISPATCHING + ".route", "1",
                                WorkModel.DOMAIN)
                        .retrying(new cloud.jengu.dbo.core.process.RetryPolicy(
                                List.of("lapsed"), "PT0S", 3))));
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }


    /**
     * The host's side of the line: the store handle stays HERE, and the
     * runner receives only the lane — which is the whole point.
     */
    private static Lane lane(String tenant, String runnerName) {
        return Lane.inProcess(tenant, runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, runnerName,
                new Executor(runnerName, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    @Test
    @DisplayName("a registered service consumes: claimed, performed, closed with the tally — "
            + "and the declaration it was offered the work under carries no counts")
    @Proving({DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
            DboPromises.PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT})
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
            // Cycle until the work is performed rather than asserting on one
            // pass. A cycle is a poll, and a poll that arrives before the
            // feed has the run yields nothing — which is ordinary on a loaded
            // machine and not a defect. Asserting on the first cycle made
            // this test a load gauge: it went red twice on a CI runner
            // carrying two suites at once while passing everywhere else.
            Eventually.cycling(runner, "the item run reached the service", 
                    () -> received.get() != null);
        }

        Run after = runs.byId(work.id()).orElseThrow();
        assertTrue(!after.open(), "the run is closed, not parked: " + after.status());
        assertEquals(1L, after.tally().get("validated"),
                "the tally landed on the record: " + after.tally());
        // Work.inputs is the seam the step's declared API fills via
        // the run's slots. This run's step declares none, so none
        // is what arrives — and there is no verb a service or runner could
        // ask for more with, which is the security boundary.
        assertTrue(received.get().inputs().isEmpty(),
                "a step without slots delivers exactly nothing: " + received.get().inputs());

        List<StoredObject> declared = store.select(
                Criteria.of(ExecutorModel.TYPE).limit(50));
        // Counts travel in the heartbeat, which writes nothing: a declaration
        // re-written with them each cycle was a write per step per tick.
        List<String> mine = declared.stream()
                .map(d -> new String(d.payload(), StandardCharsets.UTF_8))
                .filter(d -> d.contains("\"runner-one\"")).toList();
        assertFalse(mine.isEmpty(), "the runner did not declare itself");
        assertTrue(mine.stream().noneMatch(d -> d.contains("performed")),
                "counts were written into the declaration record: " + mine);
    }

    @Test
    @DisplayName("a service throwing a fault its step declared will pass releases with the "
            + "reason — released is not done — and a later cycle takes it again and succeeds")
    @Proving({DboPromises.PROC_FAILURE_IS_RELEASED,
            DboPromises.PROC_A_CLAIM_IS_LOST_TO_SOMEBODY_NOT_TO_THE_CLOCK})
    void failureIsReleasedThenRetaken() {
        Run work = runs.pipeline(DISPATCHING, "dispatch", DISPATCHING + "/dispatch/one",
                List.of(WorkModel.DOMAIN));

        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        // Every claim this runner takes has passed its deadline before the
        // runner reads the work it claimed — which is what a loaded machine
        // did to the hundred milliseconds this held for, now and then. Made
        // the ordinary case here, so whether a holder slower than its own
        // deadline is still handed its work is asked on every run rather than
        // on the runs that happen to be slow.
        try (StepRunner runner = new StepRunner(Duration.ZERO, Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return DISPATCHING + ".dispatch";
                }

                @Override
                public Outcome perform(Work work) {
                    if (attempts.incrementAndGet() == 1) {
                        throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                                "the printer is not answering");
                    }
                    return Outcome.done();
                }
            });
            runner.attach(lane("t-one", "runner-two"));

            runner.cycle();
            Run released = runs.byId(work.id()).orElseThrow();
            assertTrue(released.open(), "released is not done — still open");

            // The release holds the run back until its not-before, which this
            // step declares as now, and the runner's own housekeeping readies
            // it on the next cycle — a version, and so a feed entry the lane's
            // poll reads. Cycle until the run closes rather than counting
            // cycles: delivery is at-least-once, so a third take is not a
            // failure.
            //
            // A LIVENESS wait, not a budget. The feed holds an event back only
            // while a write in THIS database is in flight when it is read, and
            // the only writer here is the runner, which never reads while it
            // writes — so neighbouring classes on the shared server cannot
            // hold the release off the feed. What load still stretches is how
            // long each cycle takes, which is why this waits on the run and not
            // on a count.
            long deadline = System.nanoTime() + Eventually.PATIENCE.toNanos();
            while (runs.byId(work.id()).orElseThrow().open()
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                runner.cycle();
            }
        }
        // Which of the two went wrong, said apart. A run nobody ever performed
        // a second time was never offered back; one performed and still open
        // is the release-and-retake this test is actually about.
        assertTrue(!runs.byId(work.id()).orElseThrow().open(),
                attempts.get() < 2
                        ? "the service was handed the run " + attempts.get() + " time(s), "
                                + "so nothing here exercised the retake: "
                                + runs.byId(work.id()).orElseThrow().status() + ", "
                                + (runs.byId(work.id()).orElseThrow().automation()
                                        ? "open to automation" : "open to people alone")
                                + ", because: "
                                + runs.byId(work.id()).orElseThrow().statusReason()
                        : "the run was performed again and is still open, so a retake does "
                                + "not close it: attempts=" + attempts.get());
        assertTrue(attempts.get() >= 2, "the retake actually performed: " + attempts.get());
    }

    @Test
    @DisplayName("a holder slower than its own deadline is still handed the work it claimed, "
            + "and loses it only when somebody acts on the run — here, the housekeeping that "
            + "hands it back")
    @Proving(DboPromises.PROC_A_CLAIM_IS_LOST_TO_SOMEBODY_NOT_TO_THE_CLOCK)
    void aHolderSlowerThanItsDeadlineKeepsItsClaim() {
        Run work = runs.pipeline(DISPATCHING, "dispatch", DISPATCHING + "/dispatch/slow",
                List.of(WorkModel.DOMAIN));
        Lane lane = lane("t-slow", "runner-slow");

        // A deadline that has passed before the holder reads anything: the
        // claim lands, and by the time the holder asks for what it claimed the
        // clock is past it. Nobody else has done anything to the run.
        Run held = lane.claim(work, Duration.ZERO).orElseThrow();
        assertEquals(Map.of(), lane.inputs(held),
                "the holder was refused the work it claimed though nobody had taken it from it");

        // The deadline is what lets somebody else notice. Once somebody has —
        // the tenant's housekeeping, handing the run back — the holder is
        // fenced off, and the read it was given a moment ago is refused.
        lane.releaseLapsed();
        assertTrue(runs.byId(work.id()).orElseThrow().assignment().executor() == null,
                "housekeeping did not hand the lapsed claim back: "
                        + runs.byId(work.id()).orElseThrow().assignment());
        IllegalStateException fenced = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> lane.inputs(held),
                "a holder whose run was handed back was still given its inputs");
        assertTrue(fenced.getMessage().contains("not claimed by runner-slow"),
                "refused, but not for holding nothing: " + fenced.getMessage());
    }

    @Test
    @DisplayName("a holder whose claim was handed back and taken by another runner is refused "
            + "its release, checkpoint, milestone and close, and the other runner's claim "
            + "stands")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aFormerHolderCannotUndoTheNextHoldersClaim() {
        Run work = runs.pipeline(DISPATCHING, "route", DISPATCHING + "/route/raced",
                List.of(WorkModel.DOMAIN));
        Lane first = lane("t-raced", "runner-first");
        Lane second = lane("t-raced", "runner-second");

        Run heldByFirst = first.claim(work, Duration.ZERO).orElseThrow();
        first.releaseLapsed();
        Run heldBySecond = second.claim(runs.byId(work.id()).orElseThrow(),
                Duration.ofMinutes(5)).orElseThrow();
        Run before = runs.byId(work.id()).orElseThrow();

        List<org.junit.jupiter.api.function.Executable> verbs = List.of(
                () -> first.released(heldByFirst, "the service threw: not claimed",
                        cloud.jengu.dbo.work.Failure.UNKNOWN),
                () -> first.checkpoint(heldByFirst, Map.of("routed", 1L), Duration.ofMinutes(5)),
                () -> first.milestone(heldByFirst, "routed", Map.of(), Duration.ofMinutes(5)),
                () -> first.closed(heldByFirst));
        for (org.junit.jupiter.api.function.Executable verb : verbs) {
            org.junit.jupiter.api.Assertions.assertThrows(Runs.NotHeld.class, verb,
                    "a runner whose claim was taken over still acted on the run");
        }

        Run after = runs.byId(work.id()).orElseThrow();
        assertEquals(before.versionId(), after.versionId(),
                "a refused verb still wrote a version: " + after);
        assertTrue(after.heldBy(second.identity()),
                "the second runner's claim did not survive the first's late verbs: "
                        + after.assignment());
        assertTrue(after.automation(), "the late release sent the run to people: " + after);
        second.closed(heldBySecond);
        assertTrue(!runs.byId(work.id()).orElseThrow().open(),
                "the runner holding the run could not close it");
    }

    @Test
    @DisplayName("stateless over tenants: one runner, two lanes, each tenant's work performed "
            + "and reported on its own lane")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
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
            Eventually.cycling(runner, "work flowed through one of the two lanes",
                    () -> servedBy.contains(one.key()) || servedBy.contains(two.key()));
        }
        assertTrue(servedBy.contains(one.key()) || servedBy.contains(two.key()),
                "work flowed through the lanes: " + servedBy);
    }

    @Test
    @DisplayName("a runner told which steps it is about to hold asks for no work until it "
            + "holds them all, so its first poll does not pass the work of the last to arrive")
    @Proving(DboPromises.PROC_A_RUNNER_ASKS_ONCE_IT_HOLDS_ITS_STEPS)
    void aRunnerWaitsForTheStepsItWasToldOf() throws InterruptedException {
        PgChangeFeed feed = new PgChangeFeed(ds, WorkModel.DOMAIN);
        Run waiting = runs.pipeline(PROCESS, "label", PROCESS + "/label/awaited",
                List.of(WorkModel.DOMAIN));

        AtomicReference<String> performed = new AtomicReference<>();
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))
                .awaiting(List.of(PROCESS + ".seal", PROCESS + ".label"))) {
            // The lane and one of the two services first, and the loop
            // running — the order a container that opens its lanes as it
            // starts hands them over in.
            runner.attach(lane("t-awaited", "runner-awaited"));
            runner.register(service("seal", performed));
            runner.start();
            // Forty of its ticks: a runner that was going to poll has.
            for (int tick = 0; tick < 20; tick++) {
                Thread.sleep(100);
                assertEquals(null, feed.cursorOf("runner-awaited"),
                        "the runner asked for work holding one of the two steps it was told of, "
                                + "and its cursor moved past the work of the other");
            }

            runner.register(service("label", performed));
            Eventually.until("the run waiting at the second step was performed and closed",
                    () -> { }, () -> !runs.byId(waiting.id()).orElseThrow().open());
        }
        assertEquals(waiting.key(), performed.get(), "the service that came for it performed it");
    }

    private static StepService service(String step, AtomicReference<String> performed) {
        return new StepService() {

            @Override
            public String step() {
                return PROCESS + "." + step;
            }

            @Override
            public Outcome perform(Work work) {
                performed.set(work.run().key());
                return Outcome.done();
            }
        };
    }
}
