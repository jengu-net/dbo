package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A sweep whose open items outnumber what one selection returns, as a stream
 * parking seven hundred shadowed versions has.
 *
 * <p>Over the work domain's own records, in a map that stops a selection at
 * its limit as the store does.
 */
class ASweepHoldsEachReferenceOnceTest {

    /** More than one selection holds. */
    private static final int MANY = 250;

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());

    @Test
    @DisplayName("a pass that finds again more open items than one page holds raises none of "
            + "them twice")
    @Proving(DboPromises.PROC_A_SWEEP_HOLDS_EACH_REFERENCE_ONCE)
    void manyOpenItemsAreFoundAgainNotRaisedAgain() {
        Run sweep = round();
        assertEquals(MANY, runs.openItems(sweep).size(), "the first pass did not raise each one");

        sweep = round();
        sweep = round();

        assertEquals(MANY, runs.openItems(sweep).size(),
                "a pass raised again what was still open");
    }

    @Test
    @DisplayName("a sweep already holding several open items for one reference keeps one and "
            + "closes the rest on its next pass")
    @Proving(DboPromises.PROC_A_SWEEP_HOLDS_EACH_REFERENCE_ONCE)
    void aReferenceRaisedTwiceIsMended() {
        Run sweep = runs.sweep("dbo.sync.stream", "apply", "hogwarts", List.of("r5"));
        for (int raised = 0; raised < 3; raised++) {
            runs.item(sweep, "StructureDefinition/patient", Failure.RECORD, "shadowed");
        }
        runs.item(sweep, "StructureDefinition/gone", Failure.RECORD, "shadowed");
        runs.item(sweep, "StructureDefinition/gone", Failure.RECORD, "shadowed");

        sweep = runs.pass(sweep)
                .item("StructureDefinition/patient", Failure.RECORD, "shadowed")
                .done();

        assertEquals(Map.of("StructureDefinition/patient", 1L),
                runs.openItems(sweep).stream().collect(Collectors.groupingBy(
                        item -> item.item().reference(), Collectors.counting())),
                "the sweep still holds a reference more than once, or holds one it no longer "
                        + "finds");
    }

    /** One pass finding each of many references wrong, as a stream's round does. */
    private Run round() {
        Run sweep = runs.sweep("dbo.sync.stream", "apply", "hogwarts", List.of("r5"));
        Runs.Pass pass = runs.pass(sweep);
        IntStream.range(0, MANY).mapToObj(n -> "PlanDefinition/catalogue-" + n)
                .forEach(reference -> pass.item(reference, Failure.RECORD,
                        "a local override shadows the upstream version"));
        return pass.done();
    }
}
