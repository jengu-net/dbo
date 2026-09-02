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
import cloud.jengu.dbo.work.Participation;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Workflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two layers, owning different failures.
 *
 * <p>The participation contract names no orchestrator, and this is what that
 * buys: a step service may put whatever it likes underneath itself, and dbo
 * neither knows nor cares. DBOS is the reference choice on a server — an edge
 * may have nothing and a workstation has a person — so it is exercised here as
 * an <em>example of the seam</em>, in the test sources, on the far side of
 * {@code StepService}. Nothing in {@code dbo-runner} compiles against it,
 * because a runner that compiled against an orchestrator would be naming one.
 *
 * <p>The claim is the last clause of the definition of done: killing a
 * participant mid-work loses nothing. Both halves have to hold at once, and
 * they are different halves.
 *
 * <ul>
 *   <li><b>dbo above — global truth.</b> The work is still owed. The claim
 *       goes, the run says <em>released</em> rather than done, and somebody
 *       may take it again.</li>
 *   <li><b>DBOS below — local durability.</b> The half-finished work is not
 *       started over. What was checkpointed stays done; only what was in
 *       flight when the process went is done again.</li>
 * </ul>
 *
 * <p>With only DBOS the work would be durable and invisible to everybody
 * else; with only dbo it would be visible and a crashed runner would lose its
 * half of it. The test asserts each half explicitly, because a store that
 * re-ran the finished step would pass a test that only looked at the run.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbosBelowResumesItsOwnHalfFinishedWorkIT {

    private static final String PROCESS = "dbo.lab";
    private static final String STEP = "enrich";
    private static final String KEY = PROCESS + "/" + STEP + "/half-finished";

    /** The expensive half, checkpointed by the local executor once it is done. */
    static final AtomicInteger THE_EXPENSIVE_PART = new AtomicInteger();
    /** The half that was in flight when the process went. */
    static final AtomicInteger THE_INTERRUPTED_PART = new AtomicInteger();
    static final CountDownLatch reachedTheSecondPart = new CountDownLatch(1);
    /**
     * Whether the dying process saw the work reach its second part — recorded
     * there, asserted here.
     *
     * <p>It used to be an {@code assertTrue} inside {@link StepService#perform},
     * which reaches the test only because {@code AssertionError} is an
     * {@code Error} and the runner catches {@code RuntimeException}. That is
     * luck, not design: a runner that ever broadened its catch would turn a
     * failed assertion into a released run and a silently passing test.
     */
    static final java.util.concurrent.atomic.AtomicBoolean gotAsFarAsTheSecondPart =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Held shut while the first process is alive, so its work is never finished. */
    static volatile CountDownLatch mayFinish = new CountDownLatch(1);
    static volatile DBOS local;

    static PGSimpleDataSource ds;
    static String url;
    static PgObjectStore store;
    static Runs runs;
    static Declarations declarations;

    /** The step's own work, expressed as the local executor's durable flow. */
    public interface Enrichment {
        void enrich(String runKey);
    }

    public static final class EnrichmentFlow implements Enrichment {

        @Override
        @Workflow(name = "enrich")
        public void enrich(String runKey) {
            local.runStep(THE_EXPENSIVE_PART::incrementAndGet, "the-expensive-part");
            local.runStep(() -> {
                THE_INTERRUPTED_PART.incrementAndGet();
                reachedTheSecondPart.countDown();
                try {
                    mayFinish.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }, "the-interrupted-part");
        }
    }

    @BeforeAll
    void up() throws Exception {
        url = SharedPostgres.urlFor("DbosBelowResumesItsOwnHalfFinishedWorkIT");
        ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        try (Connection c = DriverManager.getConnection(url,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            // The local executor's own state lives in the TENANT's database
            // (§7.4), so erasure-by-drop covers half-finished work too.
            st.execute("CREATE SCHEMA IF NOT EXISTS dbos");
        }
        store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    @AfterAll
    void down() {
        if (local != null) {
            local.shutdown();
        }
    }

    /** One process's worth of local executor — the thing that "dies" below. */
    private static DBOS aLocalExecutor() {
        // The SAME executor name across both processes: that is what makes the
        // second one the restart of the first rather than a stranger.
        DBOS started = new DBOS(DBOSConfig.defaults("the-enricher")
                .withDatabaseUrl(url)
                .withDbUser(SharedPostgres.get().getUsername())
                .withDbPassword(SharedPostgres.get().getPassword())
                .withDatabaseSchema("dbos")
                .withMigrate(true));
        local = started;
        return started;
    }

    private Lane lane(String participant) {
        return Lane.inProcess("t-dbos", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, participant,
                new Executor(participant, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    /**
     * A transaction on this database with an xid assigned, held open.
     *
     * <p>This is the condition that made the test flaky, made deliberate.
     * {@code PgChangeFeed} delivers an event only when {@code xact_id} is below
     * the local horizon, and while any backend on this database holds an xid
     * the horizon falls back to the cluster's {@code xmin} — so an event
     * committed AFTER this transaction started is withheld until it ends. DBOS
     * keeps connections on this same database, so on a busy machine it produced
     * this state by itself, at random, for a fraction of a second.
     *
     * <p>Opened before the run is written, because the order is the whole
     * mechanism: a transaction younger than the event does not hold it back.
     */
    private static Connection anXidHeldOpen() throws Exception {
        Connection c = DriverManager.getConnection(url,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        c.setAutoCommit(false);
        try (var st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS held_open (n int)");
            c.commit();
            st.execute("INSERT INTO held_open VALUES (1)"); // this is what assigns the xid
        }
        return c;
    }

    /**
     * Cycles until the runner actually takes the work, rather than once.
     *
     * <p>One cycle was a coin flip, and the reason is a deliberate property of
     * the store rather than a bug in it. A lane polls the change feed, and
     * {@code PgChangeFeed} withholds an event while any transaction in the same
     * database still holds an xid — otherwise a writer committing between a
     * reader's snapshot and its liveness scan would have its event skipped for
     * ever. DBOS keeps its own connections in this same database, by design
     * (§7.4: the local executor's state lives in the tenant's database). So the
     * run is written, correct, and not yet offered.
     *
     * <p>The consequence was a test that failed on commits which could not have
     * touched it, reporting {@code expected: <1> but was: <0>} — a count, when
     * the truth was that nothing had been asked to do anything at all. The
     * second half of this test always looped; only the first half asserted
     * after a single cycle.
     */
    private static void cycleUntilTheWorkIsTaken(StepRunner runner, String which)
            throws InterruptedException {
        long deadline = System.nanoTime() + Eventually.PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (runner.cycle() > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(which + " never took the work, so nothing below was ever "
                + "asked to do anything — this is the lane never offering the run, not the "
                + "layer below failing to resume it. The run stands as: " + runs.byKey(KEY));
    }

    @Test
    @DisplayName("the participant dies mid-work: the run is owed again, and the half that was "
            + "finished is not done twice")
    @Proving({DboPromises.PROC_STEP_SERVICE_EMBEDDABLE, DboPromises.PROC_FAILURE_IS_RELEASED})
    void killingItMidWorkLosesNeitherHalf() throws Exception {
        // Older than the run, and held open across the first poll: while it
        // stands, the lane is correctly offered nothing.
        Connection holdingTheFeedBack = anXidHeldOpen();
        runs.pipeline(PROCESS, STEP, KEY, List.of(WorkModel.DOMAIN));

        // ---- the first process: takes the work, gets halfway, and goes ----
        DBOS first = aLocalExecutor();
        Enrichment firstProxy = first.registerProxy(Enrichment.class, new EnrichmentFlow());
        first.launch();
        try (StepRunner dying = new StepRunner(Duration.ofMillis(200), Duration.ofMillis(50))) {
            dying.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Outcome perform(Work work) {
                    // Hands the work to the local executor and does NOT wait:
                    // this process is about to stop existing, which is the
                    // whole scenario.
                    first.startWorkflow(() -> firstProxy.enrich(work.run().key()),
                            new StartWorkflowOptions(work.run().key()));
                    try {
                        gotAsFarAsTheSecondPart.set(
                                reachedTheSecondPart.await(20, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return Outcome.failed("the process is going away mid-work");
                }
            });
            dying.attach(lane("the-dying-one"));
            // The state that used to fail this test, now arranged rather than
            // waited for: the run is on the store and the feed is holding it.
            // A single cycle here does nothing, and a test that asserted after
            // one would report that the layer below re-ran finished work.
            assertEquals(0, dying.cycle(),
                    "an event committed after an open transaction must not be offered yet — "
                            + "if it is, the barrier this test rides out no longer exists and "
                            + "the loop below is asserting nothing");
            holdingTheFeedBack.rollback();
            holdingTheFeedBack.close();

            cycleUntilTheWorkIsTaken(dying, "the dying process");
        }
        assertTrue(gotAsFarAsTheSecondPart.get(),
                "the work never reached its second part before the process went, so what "
                        + "the restart resumes below is not the scenario this test describes");
        assertEquals(1, THE_EXPENSIVE_PART.get(), "the expensive part ran once, on the way down");

        // and the process goes, with its work still in flight below
        first.shutdown();

        // ---- dbo above: the work is owed again ----
        Participation.releaseLapsed(runs);
        Run released = runs.byKey(KEY).orElseThrow();
        assertTrue(released.open(), "released is not done — the work is still owed");
        assertFalse(released.claimed(java.time.Instant.now()), "and nobody holds it");

        // ---- the restart: a new process, the same executor, the same store ----
        mayFinish.countDown();
        mayFinish = new CountDownLatch(0);
        DBOS second = aLocalExecutor();
        Enrichment secondProxy = second.registerProxy(Enrichment.class, new EnrichmentFlow());
        second.launch();   // recovery: the half-finished flow is picked back up

        try (StepRunner restarted = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            restarted.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Outcome perform(Work work) {
                    // The same id the first process used. Whatever the restart
                    // recovered, this waits for the one answer.
                    try {
                        second.startWorkflow(() -> secondProxy.enrich(work.run().key()),
                                        new StartWorkflowOptions(work.run().key()))
                                .getResult();
                    } catch (Exception e) {
                        return Outcome.failed(String.valueOf(e.getMessage()));
                    }
                    return Outcome.done(Map.of("enriched", 1L));
                }
            });
            restarted.attach(lane("the-restarted-one"));

            long deadline = System.nanoTime() + Eventually.PATIENCE.toNanos();
            while (runs.byKey(KEY).orElseThrow().open() && System.nanoTime() < deadline) {
                restarted.cycle();
                Thread.sleep(100);
            }
        }

        // ---- dbo above: the work is done, once ----
        Run after = runs.byKey(KEY).orElseThrow();
        assertFalse(after.open(), "the restarted participant finished the work: " + after.holder());
        assertEquals(1L, after.tally().get("enriched"));

        // ---- DBOS below: what was checkpointed was not done again ----
        assertEquals(1, THE_EXPENSIVE_PART.get(),
                "the finished half was checkpointed and must not be redone — this is the whole "
                        + "of what the layer below is for");
        assertTrue(THE_INTERRUPTED_PART.get() >= 2,
                "and the half that was in flight when the process went IS done again: "
                        + THE_INTERRUPTED_PART.get());
    }
}
