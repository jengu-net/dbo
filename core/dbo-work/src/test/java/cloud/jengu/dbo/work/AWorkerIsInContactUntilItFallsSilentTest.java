package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node's contact with the workers of a step, over a clock the test moves.
 *
 * <p>No scheduler: events arrive on the caller's thread, and silence is
 * judged when the test asks, so every moment here is one the test chose.
 */
class AWorkerIsInContactUntilItFallsSilentTest {

    private static final String STEP = "lab.result.verify";
    private static final ContactListener.Worker WORKER =
            new ContactListener.Worker("analyser-client", "analyser", "2");

    private final MovableClock clock = new MovableClock();
    private final Contacts contacts = new Contacts("node-a", clock, null, (named, threw) -> {
        throw threw;
    });

    @Test
    @DisplayName("heard, it appears; heard again with statistics, they are delivered; silent "
            + "for the listener's threshold, it is unknown and named with when it was last seen")
    @Proving(DboPromises.PROC_A_CONTACT_LISTENER_DECLARES_ITS_SILENCE)
    void aWorkerAppearsIsHeardAndFallsSilent() {
        Told told = new Told(STEP, Duration.ofSeconds(30));
        contacts.listen(told);

        contacts.heard("hospital", Set.of("verify"), WORKER, null);
        clock.move(Duration.ofSeconds(20));
        contacts.heard("hospital", Set.of(STEP), WORKER,
                Map.of("example.bench", Map.of("queued", 3L)));
        Instant lastHeard = clock.instant();
        clock.move(Duration.ofSeconds(29));
        contacts.expire();
        assertEquals(List.of("reset", "appeared", "statistics"), told.kinds(),
                "silent for less than its threshold, it is still in contact");

        clock.move(Duration.ofSeconds(1));
        contacts.expire();

        assertEquals(List.of("reset", "appeared", "statistics", "unknown"), told.kinds());
        ContactListener.Unknown unknown = (ContactListener.Unknown) told.events.get(3);
        assertEquals(lastHeard, unknown.lastSeen(), "it is unknown as of when it was last heard");
        assertEquals("node-a", unknown.node(), "and the event names the node that lost it");
        ContactListener.Statistics statistics = (ContactListener.Statistics) told.events.get(2);
        assertEquals(Map.of("queued", 3L), statistics.statistics().get("example.bench"),
                "statistics arrive nested, as the worker sent them");

        contacts.heard("hospital", Set.of(STEP), WORKER, null);
        assertEquals("appeared", told.kinds().get(4), "heard after it was unknown, it appears again");
    }

    @Test
    @DisplayName("two listeners on one step keep their own silences, and each is told on its own "
            + "threshold")
    @Proving(DboPromises.PROC_A_CONTACT_LISTENER_DECLARES_ITS_SILENCE)
    void eachListenerKeepsItsOwnSilence() {
        Told patient = new Told(STEP, Duration.ofMinutes(5));
        Told brisk = new Told(STEP, Duration.ofSeconds(10));
        contacts.listen(patient);
        contacts.listen(brisk);

        contacts.heard("hospital", Set.of(STEP), WORKER, null);
        clock.move(Duration.ofSeconds(11));
        contacts.expire();

        assertEquals(List.of("reset", "appeared", "unknown"), brisk.kinds());
        assertEquals(List.of("reset", "appeared"), patient.kinds());
    }

    @Test
    @DisplayName("a listener declaring no silence is refused when it is registered, by name, "
            + "because there is no default to fall back on")
    @Proving(DboPromises.PROC_A_CONTACT_LISTENER_DECLARES_ITS_SILENCE)
    void aListenerWithNoSilenceIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> contacts.listen(new Told(STEP, null)));

        assertTrue(refused.getMessage().contains(Told.class.getName()),
                "the refusal names the listener: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("no default"), refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> contacts.listen(new Told(STEP, Duration.ZERO)),
                "a silence of nothing is no silence");
    }

    @Test
    @DisplayName("a step nobody listens to is not tracked: activity for it tells nobody and is "
            + "kept nowhere")
    @Proving(DboPromises.PROC_A_CONTACT_LISTENER_IS_OPTIONAL_PER_STEP)
    void aStepNobodyListensToIsNotTracked() {
        Told told = new Told(STEP, Duration.ofSeconds(30));
        contacts.listen(told);

        contacts.heard("hospital", Set.of("lab.result.report", "report"), WORKER, null);
        clock.move(Duration.ofHours(1));
        contacts.expire();

        assertEquals(List.of("reset"), told.kinds(),
                "a listener on one step heard about another");
        assertEquals(Set.of(STEP), contacts.listened());
    }

    @Test
    @DisplayName("a listener taken up on a node is told everything about its step is unknown "
            + "there, and a worker it had heard must appear again")
    @Proving(DboPromises.PROC_A_NODE_START_RESETS_CONTACT)
    void aNodeStartResetsContact() {
        Told told = new Told(STEP, Duration.ofSeconds(30));
        contacts.listen(told);
        contacts.heard("hospital", Set.of(STEP), WORKER, null);

        // The same listener on the node after it restarted: nothing was kept.
        Contacts restarted = new Contacts("node-a", clock, null, (named, threw) -> {
            throw threw;
        });
        restarted.listen(told);
        restarted.heard("hospital", Set.of(STEP), WORKER, null);

        assertEquals(List.of("reset", "appeared", "reset", "appeared"), told.kinds());
        ContactListener.Reset reset = (ContactListener.Reset) told.events.get(2);
        assertEquals(new ContactListener.Reset(STEP, "node-a"), reset);
    }

    /** A listener that writes down what it was told, in order. */
    private static final class Told implements ContactListener {

        private final String step;
        private final Duration silence;
        private final List<Object> events = new ArrayList<>();

        Told(String step, Duration silence) {
            this.step = step;
            this.silence = silence;
        }

        @Override
        public String step() {
            return step;
        }

        @Override
        public Duration silence() {
            return silence;
        }

        @Override
        public void appeared(Appeared appeared) {
            events.add(appeared);
        }

        @Override
        public void statistics(Statistics statistics) {
            events.add(statistics);
        }

        @Override
        public void unknown(Unknown unknown) {
            events.add(unknown);
        }

        @Override
        public void reset(Reset reset) {
            events.add(reset);
        }

        List<String> kinds() {
            return events.stream().map(event -> {
                String simple = event.getClass().getSimpleName();
                return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            }).toList();
        }
    }

    /** A clock that stands still until it is moved. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-10-04T08:00:00Z");

        void move(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
