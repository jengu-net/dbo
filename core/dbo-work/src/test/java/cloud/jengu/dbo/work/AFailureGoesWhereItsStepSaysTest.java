package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.RetryPolicy;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a failed run goes: back to automation later for a fault its step
 * declared will pass, ended for a record fault, and to people for anything
 * else.
 *
 * <p>Over the work domain's own records and feed, in a map: the routing is a
 * write to the run's record, and that is what every list reads.
 */
class AFailureGoesWhereItsStepSaysTest {

    private static final String PROCESS = "lab.result";
    private static final String STEP = "verify";

    private final WorkInMemory held = new WorkInMemory();

    private Runs runs(StepDeclaration step) {
        return new Runs(held.store(), Steps.of(step));
    }

    private Runner runner(Runs runs) {
        return new Runner(runs, held.feed(),
                new Declarations(held.store(), held.feed(), Duration.ofMinutes(1)),
                new Declarations.Declared(PROCESS, STEP, "analyser", "1", "example.lab",
                        Scope.BASELINE, "analyser"), Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a failure nobody declared would pass goes to people, with the failure as its "
            + "reason, rather than back to automation")
    @Proving(DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS)
    void anUnknownFailureGoesToPeople() {
        Runs runs = runs(StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN));
        Run run = runs.pipeline(PROCESS, STEP);

        runner(runs).runOnce(50, taken -> {
            throw new UnsupportedOperationException("the analyser answered in a dialect "
                    + "nobody wrote a parser for");
        });

        Run after = runs.byKey(run.key()).orElseThrow();
        assertFalse(after.automation(),
                "an unknown fault went back to automation, to be retried without end: " + after);
        assertEquals(Status.READY, after.status(), "it is not waiting on the list: " + after);
        assertTrue(String.valueOf(after.statusReason()).contains("dialect"),
                "the failure is not the reason it stands so: " + after.statusReason());
    }

    @Test
    @DisplayName("a fault the step declared will pass is held back, open to automation, and "
            + "counted — and past the declared attempts it goes to a person")
    @Proving({DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS, DboPromises.PROC_FAILURE_IS_RELEASED})
    void aDeclaredFaultIsRetriedThenSeen() {
        Runs runs = runs(StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN)
                .retrying(new RetryPolicy(List.of("unreachable"), "PT1M", 2)));
        Run run = runs.pipeline(PROCESS, STEP);
        Executor analyser = new Executor("analyser", "1", "example.lab", Scope.BASELINE);

        Instant before = Instant.now();
        Run first = runs.released(runs.claim(run, analyser, Instant.now().plusSeconds(60))
                        .orElseThrow(), "the LIS did not answer", Failure.UNREACHABLE);
        assertEquals(Status.ON_HOLD, first.status());
        assertTrue(first.automation(), "a declared fault went to people: " + first);
        assertEquals(1, first.attempts());
        assertNotNull(first.notBefore());
        assertFalse(first.notBefore().isBefore(before.plus(Duration.ofMinutes(1))),
                "it is not held back for what the step declared: " + first.notBefore());
        assertFalse(first.forAutomation(Instant.now()), "it was offered before its time");

        Run second = runs.released(first, "again", Failure.UNREACHABLE);
        assertEquals(Status.ON_HOLD, second.status());
        assertEquals(2, second.attempts());

        Run third = runs.released(second, "and again", Failure.UNREACHABLE);
        assertEquals(Status.READY, third.status());
        assertFalse(third.automation(),
                "past its attempts a fault that will not pass was given to automation again");
    }

    @Test
    @DisplayName("a record fault ends the run as failed, because trying again would be refused "
            + "in the same words")
    @Proving(DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS)
    void aRecordFaultEndsTheRun() {
        Runs runs = runs(StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN)
                .retrying(new RetryPolicy(List.of("unreachable", "lapsed"), "PT0S", 5)));
        Run run = runs.pipeline(PROCESS, STEP);

        Run after = runs.released(run, "the code is not in the system", Failure.RECORD);

        assertEquals(Status.FAILED, after.status());
        assertFalse(after.open());
    }

    @Test
    @DisplayName("a lapsed claim goes back to automation only where the step declared a lapse "
            + "will pass, and to people otherwise")
    @Proving(DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS)
    void aLapseIsTransientOnlyWhenDeclared() throws InterruptedException {
        Executor analyser = new Executor("analyser", "1", "example.lab", Scope.BASELINE);
        Runs silent = runs(StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN)
                .retrying(new RetryPolicy(List.of("unreachable"), "PT0S", 5)));
        Run undeclared = silent.pipeline(PROCESS, STEP, "undeclared");
        silent.claim(undeclared, analyser, Instant.now().plusMillis(1)).orElseThrow();
        Thread.sleep(20);
        Participation.releaseLapsed(silent);
        assertFalse(silent.byKey("undeclared").orElseThrow().automation(),
                "a lapse the step never said would pass went back to automation");

        Runs patient = runs(StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN)
                .retrying(new RetryPolicy(List.of("lapsed"), "PT0S", 5)));
        Run declared = patient.pipeline(PROCESS, STEP, "declared");
        patient.claim(declared, analyser, Instant.now().plusMillis(1)).orElseThrow();
        Thread.sleep(20);
        Participation.releaseLapsed(patient);
        Run after = patient.byKey("declared").orElseThrow();
        assertTrue(after.automation(), "a declared lapse went to people: " + after);
        assertTrue(after.forAutomation(Instant.now()),
                "held back for nothing and readied by the same housekeeping, it is not "
                        + "offered: " + after);
    }

    @Test
    @DisplayName("a person who has looked at a failure reopens the run saying whether automation "
            + "may take it, and its attempts start again")
    @Proving(DboPromises.PROC_CLOSED_CAN_BE_REOPENED)
    void reopeningSaysWhoMayTakeIt() {
        Runs runs = runs(StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN)
                .retrying(new RetryPolicy(List.of("unreachable"), "PT0S", 1)));
        Run run = runs.pipeline(PROCESS, STEP);
        Run seen = runs.released(runs.released(run, "down", Failure.UNREACHABLE), "down again",
                Failure.UNREACHABLE);
        assertFalse(seen.automation(), "past one attempt it should be a person's");

        Run byHand = runs.reopen(seen, "the analyser is back; I will run this one myself",
                false);
        assertFalse(byHand.automation());
        Run again = runs.reopen(byHand, "on reflection, let the analyser have it", true);
        assertTrue(again.forAutomation(Instant.now()), "returned to automation and not offered");
        assertEquals(0, again.attempts());
        assertEquals("on reflection, let the analyser have it", again.statusReason());
    }

    @Test
    @DisplayName("an unreachable store and a failed read are unreachable, a refusal of the "
            + "record is the record's, and anything else is unknown")
    void theClassIsReadOffTheException() {
        assertEquals(Failure.UNREACHABLE, Failure.of(new java.io.UncheckedIOException(
                new java.io.IOException("connection refused"))));
        assertEquals(Failure.RECORD, Failure.of(new IllegalArgumentException("no such code")));
        assertEquals(Failure.UNKNOWN, Failure.of(new IllegalStateException("on fire")));
    }
}
