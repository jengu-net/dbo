package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.ProvingLane;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proving a step service without standing up a store.
 *
 * <p>Writing one is two methods; proving one meant a real store, or a
 * hand-built run, inputs map and progress — so every implementor carried its
 * own scaffolding, each slightly different, and the three things the contract
 * actually asks went unchecked. A service that swallows a failure, never
 * checkpoints, or is not idempotent looks exactly like a correct one until
 * something goes wrong somewhere nobody is watching.
 *
 * <p><b>What makes this worth having is that it is not a second runner.</b>
 * The semantics under test live in {@code StepRunner} — release on a throw,
 * the reported run threaded through each checkpoint, close on the tally — so
 * these drive the real runner over a lane with nothing behind it, and assert
 * what the runner did. A harness that called {@code perform} itself and
 * judged the result would be asserting its own reading of the contract, which
 * is the thing a consumer writing its own fake cannot avoid and the reason
 * this belongs here.
 */
class AStepServiceIsProvedWithoutAStoreTest {

    private static final String STEP = "dbo.proving.assay.report";

    @Test
    @DisplayName("a service that finishes closes the run and its tally is carried, so 'done' "
            + "is distinguishable from 'gave up quietly'")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void doneClosesWithItsTally() {
        ProvingLane lane = ProvingLane.offering(STEP).with("specimen", "Basic", "{}").lane();

        run(lane, work -> Outcome.done(Map.of("written", 3L)));

        assertEquals(ProvingLane.Ended.CLOSED, lane.ended());
        assertTrue(lane.checkpoints().stream().anyMatch(c -> c.containsKey("written")),
                "the tally never reached the lane: " + lane.checkpoints());
    }

    @Test
    @DisplayName("a service that fails releases the run with its reason and does not close it, "
            + "because released is still owed and closed is not")
    @Proving(DboPromises.PROC_FAILURE_IS_RELEASED)
    void failureIsReleasedWithItsReason() {
        ProvingLane lane = ProvingLane.offering(STEP).lane();

        run(lane, work -> Outcome.failed("the analyser did not answer"));

        assertEquals(ProvingLane.Ended.RELEASED, lane.ended());
        assertNotEquals(ProvingLane.Ended.CLOSED, lane.ended(),
                "a failure that closed the run has told the store the work is finished, and "
                        + "nobody looks for work the store says is finished");
        assertTrue(lane.reason().contains("did not answer"),
                "the reason is the step's own words: " + lane.reason());
    }

    @Test
    @DisplayName("a service that throws is released too, so an implementor gets the same "
            + "treatment for the failure it did not think about as for the one it did")
    @Proving(DboPromises.PROC_FAILURE_IS_RELEASED)
    void throwingIsReleasedToo() {
        ProvingLane lane = ProvingLane.offering(STEP).lane();

        run(lane, work -> {
            throw new IllegalStateException("the bench is unplugged");
        });

        assertEquals(ProvingLane.Ended.RELEASED, lane.ended());
        assertTrue(lane.reason().contains("unplugged"), lane.reason());
    }

    @Test
    @DisplayName("a milestone a service names is assertable, which is what makes 'it "
            + "checkpoints' a property rather than a claim in a comment")
    @Proving(DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE)
    void milestonesAreVisible() {
        ProvingLane lane = ProvingLane.offering(STEP).lane();

        run(lane, work -> {
            work.progress().milestone("parsed", Map.of("read", 10L));
            work.progress().milestone("validated", Map.of("read", 10L, "ok", 9L));
            return Outcome.done(Map.of("written", 9L));
        });

        assertEquals(List.of("parsed", "validated"), lane.milestones());
        assertEquals(ProvingLane.Ended.CLOSED, lane.ended(),
                "naming a milestone and then finishing must still finish");
    }

    @Test
    @DisplayName("the same work arrives twice on request, through the claim path rather than "
            + "a second call — so idempotency is a property a test asserts")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void theSameWorkCanArriveTwice() {
        ProvingLane lane = ProvingLane.offering(STEP).with("specimen", "Basic", "{\"n\":1}").lane();
        List<String> sawInputs = new ArrayList<>();

        StepRunner runner = runnerFor(lane, work -> {
            sawInputs.add(new String(work.inputs().get("specimen").payload(),
                    StandardCharsets.UTF_8));
            return Outcome.done();
        });
        runner.cycle();
        lane.offerAgain();
        runner.cycle();

        assertEquals(2, lane.performed(), "the re-offered run was not taken a second time");
        assertEquals(List.of("{\"n\":1}", "{\"n\":1}"), sawInputs,
                "the work that arrived the second time was not the work that arrived the "
                        + "first: a step re-offered after a lapsed claim gets what it got");
        assertEquals(List.of(ProvingLane.Ended.CLOSED, ProvingLane.Ended.CLOSED),
                lane.endings());
    }

    @Test
    @DisplayName("nothing is performed when nothing matches, so a service whose step id does "
            + "not line up fails loudly here rather than silently in a deployment")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void workThatMatchesNothingIsNotPerformed() {
        ProvingLane lane = ProvingLane.offering("dbo.proving.assay.report").lane();

        // A service for a different step entirely.
        runnerFor(lane, "dbo.proving.assay.dispatch",
                work -> Outcome.done()).cycle();

        assertEquals(ProvingLane.Ended.NOT_YET, lane.ended());
        assertEquals(0, lane.performed());
    }

    @Test
    @DisplayName("a step id that is not <module>.<process>.<step> is refused when the work is "
            + "built, because a run split the wrong way polls perfectly and matches nothing")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void aStepIdThatCannotBeSplitIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ProvingLane.offering("report").lane());
    }

    // ------------------------------------------------------------- plumbing

    private static void run(ProvingLane lane, Performing performing) {
        runnerFor(lane, performing).cycle();
    }

    private static StepRunner runnerFor(ProvingLane lane, Performing performing) {
        return runnerFor(lane, STEP, performing);
    }

    private static StepRunner runnerFor(ProvingLane lane, String step, Performing performing) {
        return new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
                .register(new StepService() {
                    @Override
                    public String step() {
                        return step;
                    }

                    @Override
                    public Outcome perform(Work work) {
                        return performing.perform(work);
                    }
                })
                .attach(lane);
    }

    @FunctionalInterface
    private interface Performing {
        Outcome perform(Work work);
    }
}
