package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Introductions;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A participant introduces the step it performs, and the catalogue learns it
 *.
 *
 * <p>The groundwork left this door open on purpose: a step id is opaque and
 * globally stable, fixed with the record rather than the catalogue, so a
 * component attached only over the participation link brings its capability
 * as an addition, never a migration. One collision rule across both
 * contributor kinds, and nothing granted by walking through either door.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepsArriveByIntroductionIT {

    /** What the linked participant brings: a full declaration, actions included. */
    private static final StepDeclaration BROUGHT =
            StepDeclaration.of("ee-lab.result.sign", "2.0", WorkModel.DOMAIN)
                    .containing("open")
                    .reaching("checked", "signed");

    /** What is installed in this container, beside the introductions. */
    private static final StepDeclaration INSTALLED =
            StepDeclaration.of("lab.result.validate", "1.0", WorkModel.DOMAIN);

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static Runs runs;
    static Declarations declarations;
    static Introductions introductions;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("StepsArriveByIntroductionIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, new java.util.ArrayList<>(WorkModel.registrations()));
        introductions = new Introductions(store, Steps.of(INSTALLED));
        // Every consumer reads the COMPOSED catalogue: installed and
        // introduced through one view.
        runs = new Runs(store, introductions.composedWith());
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    private Lane lane(String name) {
        return Lane.inProcess("t-intro", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, name,
                new Executor(name, "2.0", "ee.lab", Scope.BASELINE), store, introductions);
    }

    @Test
    @DisplayName("the runner introduces the step its service brings, beside its candidacy, "
            + "with the introducer recorded")
    @Proving(DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION)
    void theRunnerIntroducesWhatItsServiceBrings() {
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {
                @Override
                public String step() {
                    return BROUGHT.id().toString();
                }

                @Override
                public Optional<StepDeclaration> declaration() {
                    return Optional.of(BROUGHT);
                }

                @Override
                public Outcome perform(Work work) {
                    return Outcome.done();
                }
            });
            runner.attach(lane("ee-lab-connector"));
            runner.cycle();
        }

        Steps catalogue = introductions.composedWith();
        StepDeclaration learned = catalogue.require(BROUGHT.id().toString());
        assertEquals(List.of("checked", "signed"), learned.milestones(),
                "the catalogue learned the WHOLE declaration, milestones and all");
        assertTrue(catalogue.ids().contains(INSTALLED.id().toString()),
                "one view, both doors: " + catalogue.ids());
        String provenance = introductions.all().stream()
                .filter(i -> i.step().id().equals(BROUGHT.id())).findFirst()
                .orElseThrow().introducer();
        assertTrue(provenance.startsWith("ee-lab-connector"),
                "provenance says which participant introduced it — whichever member of "
                        + "the fleet got there first: " + provenance);
        assertTrue(declarations.all().stream().anyMatch(d ->
                        d.name().equals("ee-lab-connector")),
                "the candidacy was declared beside it — resolution sees the step the "
                        + "moment presence does");
    }

    @Test
    @DisplayName("one id, one definition: a fleet of parallel runners co-introduces without "
            + "refusal, and only a conflicting definition collides")
    @Proving(DboPromises.PROC_ONE_ID_ONE_DEFINITION)
    void oneIdOneDefinition() throws Exception {
        // Scaling is parallel runners — DBOS runs parallel consumers, and a
        // replica set comes up racing itself. Eight introducers, one
        // barrier, identical declaration: no refusal, no thrown race, one
        // record. A race that happens to serialise would prove nothing, so
        // the barrier matters.
        int fleet = 8;
        java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(fleet);
        List<java.util.concurrent.Future<Introductions.Introduced>> raced;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(fleet)) {
            raced = new java.util.ArrayList<>();
            for (int replica = 0; replica < fleet; replica++) {
                String name = "ee-lab-connector-" + replica;
                raced.add(pool.submit(() -> {
                    barrier.await();
                    return introductions.introduce(BROUGHT, name);
                }));
            }
            for (var outcome : raced) {
                outcome.get(); // a refused or thrown replica fails the test here
            }
        }
        assertEquals(1, introductions.all().stream()
                        .filter(i -> i.step().id().equals(BROUGHT.id())).count(),
                "a fleet is not a conflict: one record, however many hands");

        // a restart or upgrade by the SAME participant replaces
        String provenance = introductions.all().stream()
                .filter(i -> i.step().id().equals(BROUGHT.id())).findFirst()
                .orElseThrow().introducer();
        introductions.introduce(BROUGHT.producing("http://example.test/report"), provenance);
        assertEquals(Optional.of("http://example.test/report"),
                introductions.all().stream()
                        .filter(i -> i.step().id().equals(BROUGHT.id())).findFirst()
                        .orElseThrow().step().produces(),
                "re-introduction by the recorded participant replaces");

        // what collides is a DIFFERENT definition for the id, whoever brings it
        Introductions.Collision rival = assertThrows(Introductions.Collision.class,
                () -> introductions.introduce(BROUGHT.reaching("done"), "somebody-else"));
        assertTrue(rival.getMessage().contains("somebody-else"),
                "refused naming both sides: " + rival.getMessage());

        assertThrows(Introductions.Collision.class,
                () -> introductions.introduce(
                        StepDeclaration.of(INSTALLED.id().toString(), "9.9", WorkModel.DOMAIN),
                        "ee-lab-connector"),
                "an id a module already contributes is a collision, not an override");

        // leave the canonical definition behind for whichever test runs next
        introductions.introduce(BROUGHT, provenance);
    }

    @Test
    @DisplayName("an introduced step grants its introducer nothing — its own declaration "
            + "binds it like anybody")
    @Proving(DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING)
    void introductionGrantsNothing() {
        introductions.introduce(BROUGHT, "ee-lab-connector");
        Run run = runs.of(BROUGHT, RunKind.PIPELINE, "bound-by-its-own-words");

        // BROUGHT declares only the 'open' action — so the introducer's own
        // report of done is refused by the very declaration it introduced.
        Runs.NotAnAction refused = assertThrows(Runs.NotAnAction.class,
                () -> runs.closed(run));
        assertTrue(refused.getMessage().contains("ee-lab.result.sign"),
                "the introduced declaration binds the introducer exactly as it binds "
                        + "anybody: " + refused.getMessage());

        // and its milestones narrow it too, position derived from ITS order
        Run reported = runs.milestone(run, "checked", Map.of(),
                java.time.Instant.now().plusSeconds(60));
        assertEquals(new Run.Milestone("checked", 1, 2), reported.milestone());
        assertThrows(Runs.NotAMilestone.class,
                () -> runs.milestone(run, "shipped", Map.of(),
                        java.time.Instant.now().plusSeconds(60)));
    }
}
