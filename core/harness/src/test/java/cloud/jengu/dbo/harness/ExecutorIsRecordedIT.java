package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Automation;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.ExecutorCandidate;
import cloud.jengu.dbo.work.ExecutorResolution;
import cloud.jengu.dbo.work.Resolution;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.ScopeClass;
import cloud.jengu.dbo.work.StepGrant;
import cloud.jengu.dbo.work.Work;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What resolution chose, on the record.
 *
 * <p>Over a real store, because the claim is that a decision made today can be
 * explained next year: an executor named in a variable proves nothing about
 * what survives in the tenant's own records.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutorIsRecordedIT {

    private static final String PROCESS = "dbo.claims.adjudication";
    private static final String STEP = "adjudicate";
    private static final Scope EE = Scope.zone("ee");
    private static final Scope HOGWARTS = Scope.organisation("hogwarts");
    private static final List<Scope> CHAIN = List.of(Scope.BASELINE, EE, HOGWARTS);

    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ExecutorIsRecordedIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()));
    }

    private record Candidate(Executor executor, boolean willing) implements ExecutorCandidate {
        @Override
        public boolean willTake(Work work) {
            return willing;
        }
    }

    @Test
    @DisplayName("the run names the executor, its version, its provider and the scope it was "
            + "chosen at")
    void theRunNamesWhatRanIt() {
        Executor zone = new Executor("ee-adjudicator", "2.1", "cloud.jengu.insurance", EE);
        Resolution resolution = new ExecutorResolution(() -> List.<ExecutorCandidate>of(
                        new Candidate(zone, true)))
                .resolve(StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ZONE),
                        CHAIN, List.of(), Work.of(PROCESS, STEP, "Claim/1"));

        Run run = runs.pipeline(PROCESS, STEP, PROCESS + "/claim-1");
        Run recorded = runs.selected(run, HOGWARTS, resolution.executor());

        Run read = runs.byKey(recorded.key()).orElseThrow();
        assertEquals(zone, read.assignment().executor(),
                "all four facts, or a decision made last year cannot be reproduced");
        assertEquals(HOGWARTS, read.assignment().at(),
                "and where the work happened, which is not where the executor was declared");
    }

    @Test
    @DisplayName("what nothing took is a person's, and countable per step and per zone")
    void theFallThroughIsCountable() {
        long before = runs.backlog(PROCESS, STEP, HOGWARTS);

        Resolution resolution = new ExecutorResolution(() -> List.<ExecutorCandidate>of(
                        new Candidate(new Executor("national", "1.0", "cloud.jengu.dbo",
                                Scope.BASELINE), true)))
                .resolve(StepGrant.of(PROCESS, STEP), CHAIN,
                        List.of(Automation.off(PROCESS, STEP, EE)),
                        Work.of(PROCESS, STEP, "Claim/2"));

        Run run = runs.pipeline(PROCESS, STEP, PROCESS + "/claim-2");
        Run held = runs.fellThrough(run, HOGWARTS, resolution.reason());

        assertTrue(held.needsAPerson(), "nothing automated took it, so somebody has to");
        assertTrue(held.fellThrough());
        assertTrue(runs.byKey(held.key()).orElseThrow().assignment().note().contains("zone:ee"),
                "and the person is told why: " + held.assignment());
        assertEquals(before + 1, runs.backlog(PROCESS, STEP, HOGWARTS),
                "the automation backlog is a number, per step and per zone");
        assertEquals(0, runs.backlog(PROCESS, STEP, Scope.zone("lv")),
                "and it is per zone rather than in total");
    }

    @Test
    @DisplayName("a refused override survives on the run that ran without it")
    void aRefusedOverrideIsRecordedEvenWhenSomethingElseRan() {
        Executor national = new Executor("national", "1.0", "cloud.jengu.dbo", Scope.BASELINE);
        Resolution resolution = new ExecutorResolution(() -> List.<ExecutorCandidate>of(
                        new Candidate(new Executor("local", "1.0", "someone.else", HOGWARTS), true),
                        new Candidate(national, true)))
                .resolve(StepGrant.of(PROCESS, STEP), CHAIN, List.of(),
                        Work.of(PROCESS, STEP, "Claim/3"));

        Run run = runs.pipeline(PROCESS, STEP, PROCESS + "/claim-3");
        Run recorded = runs.selected(run, HOGWARTS, resolution.executor(), resolution.note());

        assertEquals(national, recorded.assignment().executor());
        assertTrue(recorded.assignment().note().contains("organisation:hogwarts"),
                "somebody tried to displace the step's own rule, and that is a fact about "
                        + "their rule: " + recorded.assignment());
    }
}
