package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.telemetry.Label;
import cloud.jengu.dbo.telemetry.Labels;
import cloud.jengu.dbo.telemetry.Telemetry;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a node reports about work, and what it refuses to.
 *
 * <p>The reason a telemetry seam needs a test at all is that its failure mode
 * is silent and delayed: a field nobody meant to send arrives at a collector
 * shared across tenants, and it is discovered by somebody searching that
 * collector months later. So the assertion is not "something was emitted" but
 * <b>what could not have been</b> — the step's own words about a failure, and
 * every identifier a caller chose.
 *
 * <p>The counterpart assertion matters as much: the tenant whose work failed
 * still has the reason, on the run, in its own store. The rule is about where
 * text goes, not about losing it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumbersLeaveWithoutTheWordsIT {

    private static final String TENANT = "reporting";
    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "validate";
    /** Text a step failed with, distinctive enough to search a whole emission for. */
    private static final String WORDS = "reagent lot 55123 belongs to Ms Weasley";

    static Runs runs;
    static Lane lane;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("NumbersLeaveWithoutTheWordsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        PgObjectStore store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
        PgChangeFeed feed = new PgChangeFeed(ds, WorkModel.DOMAIN);
        Executor identity = new Executor("bench", "1.0", "cloud.jengu.test", Scope.BASELINE);
        lane = Lane.inProcess(TENANT, runs, feed,
                new Declarations(store, feed, Duration.ofSeconds(30)), "bench", identity);
    }

    @Test
    @DisplayName("a failed run is reported as a labelled measurement, and the step's own "
            + "words about it go to the tenant's store rather than to a collector")
    @Proving(DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT)
    void theWordsStayWithTheTenant() {
        runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/words",
                List.of(WorkModel.DOMAIN));
        Recording recorded = new Recording();

        try (StepRunner runner = new StepRunner(
                Duration.ofMinutes(5), Duration.ofMillis(50), recorded)) {
            runner.register(service(() -> {
                throw new IllegalStateException(WORDS);
            })).attach(lane);
            Eventually.cycling(runner, "something was reported at all",
                    () -> !recorded.measurements.isEmpty());
        }

        assertTrue(recorded.measurements.stream()
                        .anyMatch(m -> "dbo.run.reported".equals(m.name())),
                "the run's outcome was not reported: " + recorded.measurements);
        assertEquals(Set.of("threw"), recorded.valuesOf(Label.OUTCOME),
                "an outcome is a word from a fixed set, which is what makes it aggregatable");

        // The claim this test exists for. Every emitted value, searched.
        assertFalse(recorded.everyValue().stream().anyMatch(v -> v.contains("55123")),
                "the step's own words left the node: " + recorded.everyValue());
        assertFalse(recorded.everyValue().stream().anyMatch(v -> v.contains("Weasley")),
                "and they were the kind of words that are exactly why this rule exists: "
                        + recorded.everyValue());

        // Not lost, only kept where it belongs: the tenant whose work failed
        // reads the reason on its own run.
        assertTrue(runs.byKey(PROCESS + "/" + STEP + "/words").orElseThrow()
                        .toString().contains("55123"),
                "the reason should still be on the run, in this tenant's own store");
    }

    @Test
    @DisplayName("nothing is labelled with anything outside the closed set — there is no "
            + "call that could attach a run's key, its parent or a foreign correlation")
    @Proving(DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT)
    void onlyTheClosedSetTravels() {
        runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/closed-set",
                List.of(WorkModel.DOMAIN));
        Recording recorded = new Recording();

        try (StepRunner runner = new StepRunner(
                Duration.ofMinutes(5), Duration.ofMillis(50), recorded)) {
            runner.register(service(() -> Outcome.done(Map.of("records", 1L)))).attach(lane);
            Eventually.cycling(runner, "the run closed",
                    () -> recorded.valuesOf(Label.OUTCOME).contains("closed"));
        }

        // Vacuously true of the type — Labels takes a Label and there is no
        // string overload — so what this really pins is that the enum has not
        // grown a member somebody added for one call site.
        assertEquals(Set.of("TENANT", "PROCESS", "STEP", "KIND", "HOLDER", "SCOPE",
                        "EXECUTOR", "OUTCOME"),
                java.util.Arrays.stream(Label.values()).map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()),
                "a label was added: it travels to a collector shared across tenants, so it "
                        + "needs the argument the envelope's own fields needed");
    }

    @Test
    @DisplayName("a node with nothing collecting still runs the emitting path, and an "
            + "exporter that cannot be loaded leaves it serving rather than failing")
    @Proving(DboPromises.PROC_REPORTING_RUNS_WHERE_NOTHING_COLLECTS)
    void nothingCollectingIsTheOrdinaryCase() {
        runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/quiet",
                List.of(WorkModel.DOMAIN));

        // No telemetry handed in at all: the constructor every embedder uses,
        // which resolves whatever this runtime has — here, nothing.
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(service(() -> Outcome.done(Map.of()))).attach(lane);
            Eventually.cycling(runner,
                    "the work happened with nothing collecting — emission is the same path "
                            + "with and without a collector",
                    () -> runs.byKey(PROCESS + "/" + STEP + "/quiet")
                            .map(r -> r.assignment() != null).orElse(false));
        }

        assertTrue(Telemetry.installed() instanceof Telemetry.Discarding,
                "a runtime with no exporter installed resolves to the discarding one rather "
                        + "than to null or an error");
    }

    private static StepService service(java.util.function.Supplier<Outcome> body) {
        return new StepService() {
            @Override
            public String step() {
                return PROCESS + "." + STEP;
            }

            @Override
            public Outcome perform(Work work) {
                return body.get();
            }
        };
    }


    /** Everything the runner tried to report, kept whole so it can be searched. */
    private static final class Recording implements Telemetry {

        private record Measurement(String name, Labels labels) {}

        private final List<Measurement> measurements = new CopyOnWriteArrayList<>();

        @Override
        public void counted(String name, long delta, Labels labels) {
            measurements.add(new Measurement(name, labels));
        }

        @Override
        public void observed(String name, Duration took, Labels labels) {
            measurements.add(new Measurement(name, labels));
        }

        @Override
        public void level(String name, long value, Labels labels) {
            measurements.add(new Measurement(name, labels));
        }

        Set<String> valuesOf(Label label) {
            Set<String> found = new java.util.LinkedHashSet<>();
            measurements.forEach(m -> {
                String value = m.labels().asMap().get(label);
                if (value != null) {
                    found.add(value);
                }
            });
            return found;
        }

        /** Every label value emitted, and the metric names — the whole surface. */
        List<String> everyValue() {
            List<String> all = new ArrayList<>();
            measurements.forEach(m -> {
                all.add(m.name());
                all.addAll(m.labels().asMap().values());
            });
            return all;
        }
    }
}
