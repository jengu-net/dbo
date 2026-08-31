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
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One runner, two tenants, and nothing of one reaches the other (#160).
 *
 * <p>A runner is stateless over tenants by design — a lane is the unit of
 * everything it does — but its counters were keyed by step alone, so a runner
 * serving several tenants kept one tally across all of them and wrote it into
 * each of their stores. The numbers were wrong for whoever read them, and
 * {@code lastError} carried one tenant's failure text into the others'.
 *
 * <p>Nothing caught it because no test had ever attached one runner to two
 * lanes. This is that test, and it is deliberately about the general rule
 * rather than about counters: <b>no state in a runner may span lanes.</b>
 *
 * <p>Two databases, because two tenants are two stores. A declaration is keyed
 * by process, step, scope and name — not by tenant — so one store could not
 * hold both tenants' declarations without them being the same record, which is
 * precisely the confusion under test.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARunnerKeepsNoStateAcrossLanesIT {

    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "validate";
    private static final Executor RUNNER =
            new Executor("shared-runner", "1.0", "cloud.jengu.test", Scope.BASELINE);

    static Tenant busy;
    static Tenant quiet;

    /** One tenant's half of the world: its own database, store, runs and declarations. */
    private record Tenant(String code, Runs runs, Declarations declarations, Lane lane) {

        static Tenant of(String code) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(SharedPostgres.urlFor("ARunnerKeepsNoStateAcrossLanesIT" + code));
            ds.setUser(SharedPostgres.get().getUsername());
            ds.setPassword(SharedPostgres.get().getPassword());
            PgObjectStore store = new PgObjectStore(ds, WorkModel.registrations());
            Runs runs = new Runs(store);
            PgChangeFeed feed = new PgChangeFeed(ds, WorkModel.DOMAIN);
            Declarations declarations =
                    new Declarations(store, feed, Duration.ofSeconds(30));
            return new Tenant(code, runs, declarations,
                    Lane.inProcess(code, runs, feed, declarations, RUNNER.name(), RUNNER));
        }

        /** The vitals this tenant's store holds for the runner, as they stand. */
        Map<String, String> vitals() {
            return declarations.forStep(PROCESS, STEP).stream()
                    .filter(d -> d.name().equals(RUNNER.name()))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            code + " holds no declaration for the runner"))
                    .metadata();
        }
    }

    @BeforeAll
    void up() {
        busy = Tenant.of("busy");
        quiet = Tenant.of("quiet");
    }

    @Test
    @DisplayName("work performed for one tenant is counted only for that tenant, and a "
            + "failure's text never reaches the other's store")
    @Proving(DboPromises.PROC_RUNNER_SIGNS_ITS_VITALS)
    void oneTenantsWorkIsNotAnothersAccounting() {
        // Both runs exist, and only the busy tenant's is ever taken: the quiet
        // tenant is a tenant with a lane and nothing to do, which is the
        // ordinary case and the one that was being contaminated.
        busy.runs().pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/first",
                List.of(WorkModel.DOMAIN));

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {
                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Outcome perform(Work work) {
                    throw new IllegalStateException("reagent lot 55123 is exhausted");
                }
            });
            runner.attach(busy.lane()).attach(quiet.lane());

            assertTrue(cycleUntil(runner,
                    () -> "1".equals(busy.vitals().get("failed"))),
                    "the busy tenant never recorded the failure: " + busy.vitals());

            Map<String, String> untouched = quiet.vitals();
            assertEquals("0", untouched.get("performed"),
                    "the quiet tenant counted somebody else's work: " + untouched);
            assertEquals("0", untouched.get("failed"),
                    "and somebody else's failure: " + untouched);
            assertFalse(untouched.containsKey("lastError"),
                    "a failure message from another tenant's run reached this store — the "
                            + "text is arbitrary and about work this tenant cannot see: "
                            + untouched);

            // The busy tenant's own record is the control: the fix is scoping,
            // not silence, so its numbers and its message are still there.
            assertTrue(busy.vitals().get("lastError").contains("55123"),
                    "the tenant whose run failed still gets the reason: " + busy.vitals());
        }
    }

    @Test
    @DisplayName("a lane going away takes its accounting with it, so a tenant re-attached "
            + "later does not inherit counts from before")
    @Proving(DboPromises.PROC_RUNNER_SIGNS_ITS_VITALS)
    void detachingDropsThatLanesAccounting() {
        busy.runs().pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/second",
                List.of(WorkModel.DOMAIN));

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {
                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Outcome perform(Work work) {
                    return Outcome.done(Map.of("records", 1L));
                }
            });
            runner.attach(busy.lane());
            assertTrue(cycleUntil(runner, () -> !"0".equals(busy.vitals().get("performed"))),
                    "nothing was performed: " + busy.vitals());

            runner.detach(busy.code());
            runner.attach(busy.lane());
            runner.cycle();

            assertEquals("0", busy.vitals().get("performed"),
                    "a re-attached lane inherited the accounting of the one before it, which "
                            + "would read as work this attachment never did: " + busy.vitals());
        }
    }

    /** Cycles until the condition holds; a cycle is one poll, so arrival is eventual. */
    private static boolean cycleUntil(StepRunner runner, BooleanSupplier done) {
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                System.nanoTime() < deadline; ) {
            runner.cycle();
            if (done.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return done.getAsBoolean();
            }
        }
        return done.getAsBoolean();
    }
}
