package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lane the runner reaches across a boundary, and cannot tell from the one
 * beside it.
 *
 * <p>The participation doctrine's load-bearing claim is that the same runner
 * embeds in the cloud, on an edge, or in a pod — the lane being the only door
 * either way. Everything proving it so far ran in one object graph, where a
 * verb could quietly depend on something a wire could not carry and nothing
 * would notice.
 *
 * <p>So this drives the real {@link StepRunner} through a lane whose every
 * verb is handed to another thread and answered from there. It is deliberately
 * <b>not</b> a wire format: framing, handshake and tenant authentication
 * belong to whoever operates the socket, and inventing a second encoding here
 * would create exactly the drift the task document warns about. What is proven
 * is the seam — that each verb reduces to a call and an answer, that the work
 * completes identically, and that the two verbs which must never be inherited
 * arrive as themselves.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARemoteLaneIsIndistinguishableIT {

    // module.process, so that process + "." + step is a whole StepId: a
    // service that brings its own declaration needs the id to parse,
    // and a four-part one does not.
    private static final String PROCESS = "dbo.lab";
    private static final String STEP = "validate-remote";

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static Runs runs;
    static Declarations declarations;
    static Introductions introductions;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ARemoteLaneIsIndistinguishableIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
        // Nothing installed: the step this participant performs arrives only
        // by introduction, which is what makes `introduce` have to cross.
        introductions = new Introductions(store, cloud.jengu.dbo.core.process.Steps.of());
    }

    /**
     * Every verb crosses to another thread and its answer comes back — the
     * near side holds no delegate a caller could reach around the boundary to
     * use.
     *
     * <p>A thread is not a network, and the difference does not matter to what
     * is being asked: whether each verb is a call and an answer, or whether one
     * of them secretly needs to be in the same object graph.
     */
    private static final class RelayLane implements Lane {

        private final Lane farSide;
        private final ExecutorService boundary =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "the-far-side"));
        private final ConcurrentLinkedQueue<String> crossed = new ConcurrentLinkedQueue<>();

        RelayLane(Lane farSide) {
            this.farSide = farSide;
        }

        private <T> T across(String verb, Callable<T> call) {
            crossed.add(verb);
            try {
                return boundary.submit(call).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(verb + " was interrupted", interrupted);
            } catch (ExecutionException failed) {
                // A refusal must arrive as a refusal: a lane that turned the
                // far side's exception into a null would make an entitlement
                // check look like an empty queue.
                if (failed.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(verb + " failed", failed.getCause());
            } catch (Exception other) {
                throw new IllegalStateException(verb + " failed", other);
            }
        }

        List<String> crossed() {
            return List.copyOf(crossed);
        }

        void close() {
            boundary.shutdownNow();
        }

        // tenant and identity are settled when the lane is handed over and
        // constant for its life, so asking across for them would be a
        // round-trip for something this side already knows.
        @Override
        public String tenant() {
            return farSide.tenant();
        }

        @Override
        public Executor identity() {
            return farSide.identity();
        }

        @Override
        public List<Run> poll(Set<String> steps, int limit) {
            return across("poll", () -> farSide.poll(steps, limit));
        }

        @Override
        public Optional<Run> claim(Run run, Duration holdFor) {
            return across("claim", () -> farSide.claim(run, holdFor));
        }

        @Override
        public Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor) {
            return across("checkpoint", () -> farSide.checkpoint(run, counts, holdFor));
        }

        @Override
        public Run milestone(Run run, String milestone, Map<String, Long> counts,
                Duration holdFor) {
            // Carried as itself. Degrading this to checkpoint is the drop the
            // interface refuses to let a lane inherit, and it would pass every
            // other assertion in this file.
            return across("milestone", () -> farSide.milestone(run, milestone, counts, holdFor));
        }

        @Override
        public void released(Run run, String reason) {
            across("released", () -> {
                farSide.released(run, reason);
                return null;
            });
        }

        @Override
        public void closed(Run run) {
            across("closed", () -> {
                farSide.closed(run);
                return null;
            });
        }

        @Override
        public void reopen(Run run, String because) {
            across("reopen", () -> {
                farSide.reopen(run, because);
                return null;
            });
        }

        @Override
        public int releaseLapsed() {
            return across("releaseLapsed", farSide::releaseLapsed);
        }

        @Override
        public void declare(Declarations.Declared declared) {
            across("declare", () -> {
                farSide.declare(declared);
                return null;
            });
        }

        @Override
        public void introduce(StepDeclaration step) {
            across("introduce", () -> {
                farSide.introduce(step);
                return null;
            });
        }

        @Override
        public void withdraw(Declarations.Declared declared) {
            across("withdraw", () -> {
                farSide.withdraw(declared);
                return null;
            });
        }

        @Override
        public void routes(java.util.List<cloud.jengu.dbo.work.Trackable> behind) {
            across("routes", () -> {
                farSide.routes(behind);
                return null;
            });
        }

        @Override
        public Map<String, StoredObject> inputs(Run run) {
            // The resolved objects cross; the store that resolved them does
            // not, which is the whole shape of the verb.
            return across("inputs", () -> farSide.inputs(run));
        }

        @Override
        public cloud.jengu.dbo.work.SealedWork sealed(Run run) {
            return across("sealed", () -> farSide.sealed(run));
        }

        @Override
        public cloud.jengu.dbo.work.SealedWork sealed(Run run, List<String> recipients) {
            return across("sealed", () -> farSide.sealed(run, recipients));
        }

        @Override
        public String opened(Run run, String reference, cloud.jengu.dbo.work.RunChain.Link link) {
            return across("opened", () -> farSide.opened(run, reference, link));
        }

        @Override
        public void closed(Run run, String head) {
            across("closed", () -> {
                farSide.closed(run, head);
                return null;
            });
        }
    }

    private Lane hostLane(String participant) {
        return Lane.inProcess("t-remote", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, participant,
                new Executor(participant, "1.0", "cloud.jengu.test", Scope.BASELINE),
                store, introductions);
    }

    @Test
    @DisplayName("the runner drives a lane it can only reach across a boundary, and the work "
            + "lands exactly as it does beside it")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void theRunnerCannotTell() {
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/remote",
                List.of(WorkModel.DOMAIN));
        AtomicReference<Work> received = new AtomicReference<>();
        RelayLane relay = new RelayLane(hostLane("runner-remote"));

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Optional<StepDeclaration> declaration() {
                    // brought by the participant, so `introduce` has to cross
                    return Optional.of(StepDeclaration.of(PROCESS + "." + STEP, "1.0",
                            WorkModel.DOMAIN));
                }

                @Override
                public Outcome perform(Work handed) {
                    received.set(handed);
                    handed.progress().milestone("validated", Map.of("read", 2L));
                    return Outcome.done(Map.of("validated", 1L));
                }
            });
            runner.attach(relay);

            Eventually.cycling(runner, "the service was handed work over the boundary",
                    () -> received.get() != null);
        } finally {
            relay.close();
        }

        assertTrue(received.get() != null, "the service was handed work over the boundary");
        Run after = runs.byId(work.id()).orElseThrow();
        assertFalse(after.open(), "the run is closed, not parked: " + after.holder());
        assertEquals(1L, after.tally().get("validated"),
                "the tally landed on the record: " + after.tally());
        assertEquals("validated", after.milestone().name(),
                "and the milestone the service named survived the crossing: " + after.milestone());

        List<String> crossed = relay.crossed();
        assertTrue(crossed.containsAll(List.of("poll", "claim", "inputs", "milestone",
                        "checkpoint", "closed", "declare", "introduce")),
                "every verb the runner used went across, milestone and introduce as "
                        + "themselves rather than folded into their neighbours: " + crossed);
    }

    @Test
    @DisplayName("a step that got somewhere and then failed leaves the milestone behind for "
            + "whoever takes it next")
    @Proving({DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
            DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE})
    void whatWasReachedSurvivesTheRelease() {
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/gave-up",
                List.of(WorkModel.DOMAIN));
        RelayLane relay = new RelayLane(hostLane("runner-gives-up"));

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {

                @Override
                public String step() {
                    return PROCESS + "." + STEP;
                }

                @Override
                public Outcome perform(Work handed) {
                    handed.progress().milestone("parsed", Map.of("read", 9L));
                    throw new IllegalStateException("the analyser stopped answering");
                }
            });
            runner.attach(relay);

            // This one used to fall out of its loop and say nothing: a
            // milestone that never arrived reached the assertions below as a
            // null, which reported the wrong thing about the wrong layer.
            Eventually.cycling(runner, "the step left a milestone behind",
                    () -> runs.byId(work.id()).orElseThrow().milestone() != null);
        } finally {
            relay.close();
        }

        Run after = runs.byId(work.id()).orElseThrow();
        assertTrue(after.open(), "released is not done");
        assertEquals("parsed", after.milestone() == null ? null : after.milestone().name(),
                "the next taker resumes from a fact rather than from the beginning: "
                        + after.milestone());
        assertEquals(9L, after.tally().get("read"), "and the counts it got to are on the record");
    }

    @Test
    @DisplayName("a refusal from the far side arrives as a refusal, not as an empty answer")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void aRefusalCrossesAsARefusal() {
        // An entitlement the lane will not honour: the far side throws, and a
        // relay that swallowed it would make "you may not" indistinguishable
        // from "there is nothing", which is the failure mode this refuses.
        Lane bounded = Lane.inProcess("t-remote", runs,
                new PgChangeFeed(ds, WorkModel.DOMAIN), declarations, "bounded-remote",
                new Executor("bounded-remote", "1.0", "cloud.jengu.test", Scope.BASELINE),
                store, introductions, Lane.Entitlement.ofSteps("dbo.lab.something-else"));
        RelayLane relay = new RelayLane(bounded);
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/refused",
                List.of(WorkModel.DOMAIN));

        try {
            IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> relay.claim(work, Duration.ofMinutes(5)));
            assertTrue(refused.getMessage().contains("not entitled"),
                    "the far side's reason crossed intact: " + refused.getMessage());
        } finally {
            relay.close();
        }
    }
}
