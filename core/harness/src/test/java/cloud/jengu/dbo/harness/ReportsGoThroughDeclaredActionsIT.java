package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
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

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A report lands through the actions the step declares.
 *
 * <p>Closing and reopening are acts of judgment; a step's declaration says
 * which of them it contains, and a verb it does not contain is refused naming
 * both sides. Releasing is never narrowed — released-is-not-done is failure
 * honesty, and a step must not be able to refuse to hear that its executor
 * failed. A step that has not declared actions is not narrowed at all: empty
 * means "has not said", never "admits nothing".
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportsGoThroughDeclaredActionsIT {

    /** Its closure is a human's act: automation prepares, a person closes. */
    private static final StepDeclaration REVIEW =
            StepDeclaration.of("lab.result.review", "1.0", "r4").containing("open");

    /** The full vocabulary, reopening included. */
    private static final StepDeclaration VALIDATE =
            StepDeclaration.of("lab.result.validate", "1.0", "r4")
                    .containing("open", "close", "reopen");

    /** Closes, but a close is final: nobody declared reopen. */
    private static final StepDeclaration DISPATCH =
            StepDeclaration.of("lab.result.dispatch", "1.0", "r4").containing("open", "close");

    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ReportsGoThroughDeclaredActionsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()),
                Steps.of(REVIEW, VALIDATE, DISPATCH));
    }

    @Test
    @DisplayName("a step whose actions omit close cannot be closed by a participant, by name")
    @Proving(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS)
    void aStepWithoutCloseCannotBeClosed() {
        Run run = runs.of(REVIEW, RunKind.PIPELINE, "report-review-1");

        Runs.NotAnAction refused = assertThrows(Runs.NotAnAction.class,
                () -> runs.closed(run),
                "REVIEW's closure is a human's act — automation reporting done is exactly "
                        + "the unstructured write this rule exists to prevent");
        assertTrue(refused.getMessage().contains("close")
                        && refused.getMessage().contains("lab.result.review")
                        && refused.getMessage().contains("open"),
                "the refusal names the act, the step, and what it does declare: "
                        + refused.getMessage());
        assertTrue(runs.byKey(run.key()).orElseThrow().open(), "and the run stays open");
    }

    @Test
    @DisplayName("releasing is never narrowed — failure honesty must not be refusable")
    void releasingIsNeverNarrowed() {
        Run run = runs.of(REVIEW, RunKind.PIPELINE, "report-review-2");

        Run released = runs.released(run, "the reviewer went home");
        assertTrue(released.open(), "released is not done — still open, for the next taker");
    }

    @Test
    @DisplayName("a closed run reopens through the declared reopen action, claimable again with the reason")
    @Proving({DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS, DboPromises.PROC_CLOSED_CAN_BE_REOPENED})
    void aClosedRunReopensThroughTheDeclaredAction() {
        Run run = runs.of(VALIDATE, RunKind.PIPELINE, "report-validate-1");
        runs.closed(run);
        assertFalse(runs.byKey(run.key()).orElseThrow().open(), "closed: nobody holds it");

        Run reopened = runs.reopen(runs.byKey(run.key()).orElseThrow(),
                "the control sample was expired");
        assertTrue(reopened.open(), "deliberately open again — not a second run invented "
                + "to disagree with the first");
        assertEquals("the control sample was expired", reopened.assignment().note(),
                "the reason is on the record");

        Optional<Run> claimed = runs.claim(reopened,
                new Executor("validator", "1.0", "test", Scope.BASELINE),
                Instant.now().plusSeconds(60));
        assertTrue(claimed.isPresent(), "and it is claimable again");
    }

    @Test
    @DisplayName("a step that never declared reopen keeps its closes final")
    @Proving(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS)
    void aStepWithoutReopenKeepsItsClosesFinal() {
        Run run = runs.of(DISPATCH, RunKind.PIPELINE, "report-dispatch-1");
        runs.closed(run);

        Runs.NotAnAction refused = assertThrows(Runs.NotAnAction.class,
                () -> runs.reopen(runs.byKey(run.key()).orElseThrow(), "second thoughts"));
        assertTrue(refused.getMessage().contains("reopen"),
                "refused by the name of the act nobody declared: " + refused.getMessage());
    }

    @Test
    @DisplayName("an undeclared step is not narrowed — empty means has-not-said, not admits-nothing")
    @Proving(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS)
    void anUndeclaredStepIsNotNarrowed() {
        Run run = runs.pipeline("lab.result", "archive", "lab.result/archive/one",
                java.util.List.of(WorkModel.DOMAIN));

        runs.closed(run);
        assertFalse(runs.byKey(run.key()).orElseThrow().open(),
                "nothing declared lab.result.archive, so nothing narrows it");
        runs.reopen(runs.byKey(run.key()).orElseThrow(), "and reopening is equally unnarrowed");
        assertTrue(runs.byKey(run.key()).orElseThrow().open());
    }
}
