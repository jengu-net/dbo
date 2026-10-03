package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step that declares an answer gives its run's requester a window once the
 * result is written: a time beside the run, not a state of it.
 *
 * <p>Over the work domain's own records, in a map: what is decided is read
 * off the record {@link Runs} writes, and the window is asked of the clock
 * the question is asked with — so it lapses here without anybody waiting.
 */
class AnAnswerOpensAWindowBesideTheRunTest {

    private static final String ASKER = "the-clinic";
    private static final Duration COLLECT = Duration.ofMinutes(15);

    private final Runs runs = new Runs(new WorkInMemory().store());

    private Run asked(Duration collect) {
        return runs.filling(StepDeclaration.of("care.records.register", "1", "r5")
                        .taking("patient", "Patient"), RunKind.PIPELINE,
                cloud.jengu.dbo.core.UuidV7.newId(),
                Map.of("patient", RunSlot.given("{\"resourceType\":\"Patient\"}")), ASKER,
                null, collect);
    }

    @Test
    @DisplayName("the result's write completes the run, leaves nobody owing it anything, and "
            + "opens the requester's window for as long as the step said")
    @Proving(DboPromises.PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER)
    void theResultOpensTheWindow() {
        Run open = asked(COLLECT);
        assertFalse(open.collectableBy(ASKER, Instant.now()),
                "a run whose work is still open was collectable by its asker");

        Instant before = Instant.now();
        Run answered = runs.closed(open, List.of("Patient/p1/1"));

        assertEquals(Status.COMPLETED, answered.status());
        assertEquals(Awaits.NOTHING, answered.awaits(Instant.now()),
                "a run whose asker may still collect was read as work somebody owes");
        Instant until = answered.window().until();
        assertTrue(!until.isBefore(before.plus(COLLECT))
                        && !until.isAfter(Instant.now().plus(COLLECT)),
                "the window does not run from the result's write for the step's length: "
                        + until);
        assertTrue(answered.collectableBy(ASKER, Instant.now()));
        assertFalse(answered.collectableBy("another-client", Instant.now()),
                "a client that did not ask for the run may collect from it");
    }

    @Test
    @DisplayName("past the window the requester collects nothing, read off the clock with no "
            + "advance of the run")
    @Proving(DboPromises.PROC_AN_UNCOLLECTED_ANSWER_LAPSES)
    void theWindowLapsesByTheClock() {
        Run answered = runs.closed(asked(COLLECT), List.of("Patient/p1/1"));
        long version = answered.versionId();

        assertFalse(answered.collectableBy(ASKER, answered.window().until()),
                "the window was still open at the instant it shuts");
        assertFalse(answered.collectableBy(ASKER, Instant.now().plus(COLLECT).plusSeconds(1)));
        assertEquals(version, runs.byId(answered.id()).orElseThrow().versionId(),
                "the window lapsing wrote to the run");
        assertEquals(List.of("Patient/p1/1"), runs.byId(answered.id()).orElseThrow()
                .produced().versions(), "the lapsed run no longer names what it produced");
    }

    @Test
    @DisplayName("the requester saying it is done collecting shuts the window now, and saying "
            + "it again changes nothing")
    @Proving(DboPromises.PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER)
    void theAskerShutsItsWindow() {
        Run answered = runs.closed(asked(COLLECT), List.of("Patient/p1/1"));
        Instant now = Instant.now();

        Run collected = runs.collected(answered, now);
        assertEquals(now, collected.window().until());
        assertFalse(collected.collectableBy(ASKER, now));
        assertEquals(collected.versionId(), runs.collected(collected, now.plusSeconds(1))
                .versionId(), "shutting a shut window advanced the run");
    }

    @Test
    @DisplayName("a step that declares no answer completes as it always did, with no window, "
            + "and a refused result opens none")
    @Proving(DboPromises.PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER)
    void noAnswerNoWindow() {
        Run unanswered = runs.closed(asked(null), List.of("Patient/p1/1"));
        assertNull(unanswered.window());
        assertFalse(unanswered.collectableBy(ASKER, Instant.now()));

        Run refused = runs.refused(asked(COLLECT), "the identifier is already held");
        assertNull(refused.window().until(), "a result the tenant refused opened a window");
        assertFalse(refused.collectableBy(ASKER, Instant.now()));
    }
}
