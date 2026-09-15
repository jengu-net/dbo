package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Automation;
import cloud.jengu.dbo.work.Automations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.ExecutorCandidate;
import cloud.jengu.dbo.work.ExecutorResolution;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Resolution;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.StepGrant;
import cloud.jengu.dbo.work.Work;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching automation off for a step, as a decision somebody made.
 *
 * <p>The type said this all along — "a zone switching automation off is a
 * decision somebody made, and it is as visible and as auditable as a
 * terminology overlay; a step that is simply never reached is neither" — and
 * nothing could write one. The rule that honours it was built and correct;
 * what was missing was anywhere to say it.
 *
 * <p><b>Where the switch is read is what it can promise.</b> A reader on the
 * resolution chain alone would have made the console honest and stopped
 * nothing: work here is pulled, not dispatched, and the claim path never
 * consults resolution. So the switch is read where a claim is taken, which is
 * the one place every automatic claim passes through — and the console reads
 * the same switch, so what an operator is told and what happens are one
 * answer.
 *
 * <p>And nothing already in somebody's hands is disturbed, structurally
 * rather than by a rule: a held run carries its holder and its deadline on
 * itself, and no path re-resolves a claim once taken.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ASwitchSaysWhetherAStepIsAutomatedHereIT {

    private static final String PROCESS = "dbo.lab.assay";
    private static final String STEP = "report";
    private static final Scope EE = Scope.zone("ee");
    private static final Executor ANALYSER =
            new Executor("analyser", "1.0", "cloud.jengu.lab", EE);

    static Runs runs;
    static Automations automations;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ASwitchSaysWhetherAStepIsAutomatedHereIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        ObjectStore store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
        automations = new Automations(store);
    }

    @Test
    @Order(1)
    @DisplayName("with nothing declared a step is automated, because this answers about the "
            + "switch rather than about whether anybody automated anything")
    @Proving(DboPromises.PROC_AUTOMATION_IS_A_DECLARED_SWITCH)
    void nothingDeclaredMeansOn() {
        assertTrue(automations.automated(PROCESS, STEP, EE));
        assertTrue(claim("nothing-declared").isPresent(),
                "a step nobody has switched off was not claimable, so the switch is "
                        + "refusing by default — which is a deployment that silently stops");
    }

    @Test
    @Order(2)
    @DisplayName("switched off, the next automatic claim does not happen — and that is the "
            + "half a reader on the resolution chain alone would not have bought")
    @Proving(DboPromises.PROC_AUTOMATION_IS_A_DECLARED_SWITCH)
    void switchingItOffStopsTheNextClaim() {
        automations.declare(Automation.off(PROCESS, STEP, Scope.BASELINE));

        assertFalse(automations.automated(PROCESS, STEP, EE));
        assertTrue(claim("switched-off").isEmpty(),
                "the claim was taken anyway, so the switch is a declaration that reads and "
                        + "does not switch — which is the defect it was built to end");
    }

    @Test
    @Order(3)
    @DisplayName("a run already held is not disturbed, because a configuration change must "
            + "not turn into somebody's abandoned work")
    @Proving(DboPromises.PROC_AUTOMATION_IS_A_DECLARED_SWITCH)
    void whatIsAlreadyHeldStaysHeld() {
        // On, claimed, and only then switched off — the order that matters.
        automations.declare(Automation.on(PROCESS, STEP, Scope.BASELINE));
        Run held = claim("already-held").orElseThrow();
        assertEquals(Holder.AUTOMATION, held.holder());

        automations.declare(Automation.off(PROCESS, STEP, Scope.BASELINE));

        Run after = runs.byKey(held.key()).orElseThrow();
        assertEquals(Holder.AUTOMATION, after.holder(),
                "the switch reached into work somebody was already holding: " + after);
        assertEquals(ANALYSER, after.assignment().executor(),
                "and took the executor off a claim that had already been made");
    }

    @Test
    @Order(4)
    @DisplayName("the most local switch wins, so a zone may turn back on what the deployment "
            + "turned off")
    @Proving(DboPromises.PROC_AUTOMATION_IS_A_DECLARED_SWITCH)
    void theMostLocalOneWins() {
        // Baseline is still off from the test above; the zone says otherwise.
        automations.declare(Automation.on(PROCESS, STEP, EE));

        assertTrue(automations.automated(PROCESS, STEP, EE),
                "the zone's switch did not win over the deployment's");
        assertTrue(claim("most-local").isPresent());

        // And somebody standing somewhere else still reads the baseline.
        assertFalse(automations.automated(PROCESS, STEP, Scope.zone("lv")),
                "the zone's switch reached a zone it was not declared at");
    }

    @Test
    @Order(5)
    @DisplayName("the console and the claim read the same switch, so what an operator is told "
            + "would happen is what happens")
    @Proving(DboPromises.PROC_AUTOMATION_IS_A_DECLARED_SWITCH)
    void oneSwitchAnswersBothQuestions() {
        automations.declare(Automation.off(PROCESS, STEP, EE));
        automations.declare(Automation.off(PROCESS, STEP, Scope.BASELINE));

        // What the console asks: who would run this.
        Resolution resolution = new ExecutorResolution(() -> List.<ExecutorCandidate>of(
                        new Willing(ANALYSER)))
                .resolve(StepGrant.of(PROCESS, STEP), List.of(Scope.BASELINE, EE),
                        automations.forStep(PROCESS, STEP), Work.of(PROCESS, STEP, null));

        assertEquals(null, resolution.executor(),
                "the console would still name somebody: " + resolution.reason());
        assertTrue(resolution.reason().contains("zone:ee"),
                "and it does not say where the switch was declared: " + resolution.reason());

        // What the runner asks: may I take it. Same switch, same answer.
        assertTrue(claim("both-questions").isEmpty(),
                "the console says nobody would run it and the claim was taken anyway");
    }

    /** A candidate that would take the work, so only the switch can stop it. */
    private record Willing(Executor executor) implements ExecutorCandidate {
        @Override
        public boolean willTake(Work work) {
            return true;
        }
    }

    private static Optional<Run> claim(String which) {
        Run run = runs.pipeline(PROCESS, STEP, PROCESS + "/" + which);
        return runs.claim(run, ANALYSER, Instant.now().plus(Duration.ofMinutes(5)));
    }
}
