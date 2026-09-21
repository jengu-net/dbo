package cloud.jengu.dbo.work;

import java.time.Duration;
import java.util.List;

/**
 * Somebody watching what their own product asked for.
 *
 * <p>Off unless an integrator asks for it, and it hands over the SHAPE of a
 * question rather than the question. A narrowing is named and its value is
 * not, so an observer sees {@code [correlated, open]} and never the case it
 * was about.
 *
 * <p><b>That is a design decision and not a convenience.</b> Logging in this
 * store is never per-request and never carries identifying data — a search
 * carries {@code identifier=system|value}, and a person's national number is
 * exactly the kind of narrowing a screen makes. A seam that handed over values
 * and relied on the integrator to be careful would be a seam that leaks the
 * first time somebody wires it to a log, and they would be wiring it to a log
 * because that is what an observer looks like it is for.
 *
 * <p>Names and timings are enough for what this is actually wanted for:
 * counting questions, timing them, finding the screen that asks a hundred.
 * Reconstructing the query is not among them, because the caller composed it.
 *
 * <p><b>What a client records is never evidence.</b> The party being audited
 * controls it. The store's own trail is on the other side of the boundary and
 * cannot be declined by a product that would rather not write anything down.
 */
@FunctionalInterface
public interface Watching {

    /** Nothing watching, which is what a vocabulary hands out until asked. */
    Watching NOBODY = asked -> { };

    /**
     * One question, once it is finished with.
     *
     * <p>Reported at the END of a walk rather than the start of one, because
     * a stream is lazy: how long it took and how much it produced are not
     * known until it is closed, and a caller who asked for twenty of a
     * million and stopped has asked a different question from one who read
     * everything.
     */
    void asked(Asked question);

    /**
     * What was asked, in names.
     *
     * @param question   which question, as the vocabulary calls it
     * @param narrowedBy the narrowings by name, in the order they were added
     * @param members    how many came back, or the number a count answered
     * @param took       how long the whole of it took
     */
    record Asked(String question, List<String> narrowedBy, long members, Duration took) {

        public Asked {
            narrowedBy = List.copyOf(narrowedBy);
        }
    }
}
