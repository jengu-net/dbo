package cloud.jengu.dbo.runner;

import java.util.Map;

/**
 * What performing a run came to. The doctrine is enforced by shape: done
 * closes, failed releases with the reason, and there is no third constructor
 * — a service cannot close a run it failed.
 */
public sealed interface Outcome {

    static Outcome done() {
        return new Done(Map.of());
    }

    /** Done, with what was counted — the tally lands on the run's record. */
    static Outcome done(Map<String, Long> tally) {
        return new Done(Map.copyOf(tally));
    }

    /** Not done, and why — the run is released saying so, for the next taker. */
    static Outcome failed(String reason) {
        return new Failed(reason);
    }

    record Done(Map<String, Long> tally) implements Outcome {}

    record Failed(String reason) implements Outcome {}
}
