package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sync stream records each round as a pass over its sweep, once a second.
 * Every write to a run is an event every participant of the tenant reads
 * through, so a round that found nothing new must write nothing.
 *
 * <p>Over the work domain's own records and feed, in a map: what is counted
 * is the events a participant would read.
 */
class AnIdleSweepAddsNothingToTheWorkFeedTest {

    private final WorkInMemory held = new WorkInMemory();
    private final Runs runs = new Runs(held.store());

    @Test
    @DisplayName("a round that carried nothing and changed nothing writes nothing a participant "
            + "has to read")
    @Proving(DboPromises.PROC_A_PASS_THAT_CHANGES_NOTHING_WRITES_NOTHING)
    void anIdleRoundIsSilent() {
        round(0, false);
        int settled = events();

        for (int idle = 0; idle < 60; idle++) {
            round(0, false);
        }

        assertEquals(settled, events(),
                "a minute of rounds that carried nothing wrote to the work feed");
    }

    @Test
    @DisplayName("a round that carried something, a stream that cannot reach its upstream, and "
            + "one that reaches it again each still show on the run")
    @Proving(DboPromises.PROC_A_PASS_THAT_CHANGES_NOTHING_WRITES_NOTHING)
    void whatChangedStillShows() {
        round(0, false);
        int idle = events();

        Run carried = round(12, false);
        assertTrue(events() > idle, "a round that carried something wrote nothing");
        assertEquals(12L, carried.tally().get("seen"));

        int beforeFailing = events();
        Run failing = round(0, true);
        assertTrue(events() > beforeFailing, "a stream that lost its upstream wrote nothing");
        assertEquals(1, openItems(failing).size(), "the lost upstream is not on the run");

        int whileFailing = events();
        round(0, true);
        assertEquals(whileFailing, events(),
                "a stream still unable to reach its upstream wrote the same thing again");

        Run recovered = round(0, false);
        assertTrue(events() > whileFailing, "a stream that reached its upstream again wrote nothing");
        assertEquals(List.of(), openItems(recovered), "the recovered upstream is still on the run");
    }

    /** One round of a stream, as ContentSyncEngine records it. */
    private Run round(long seen, boolean unreachable) {
        Run sweep = runs.sweep("dbo.sync.stream", "apply", "hogwarts", List.of("r5"));
        Runs.Pass pass = runs.pass(sweep);
        if (unreachable) {
            pass.item("upstream:hogwarts", Failure.UNREACHABLE, "the link is down");
        }
        return pass.counted("seen", seen)
                .counted("applied", seen)
                .counted("parked", 0)
                .counted("undelivered", 0)
                .done();
    }

    private List<Run> openItems(Run sweep) {
        return runs.items(sweep).stream().filter(Run::open).toList();
    }

    /** Every event on the work feed, as a participant enrolled now would read them. */
    private int events() {
        return held.feed().readFor("enrolled-now-" + System.nanoTime(), 100_000).items().size();
    }
}
