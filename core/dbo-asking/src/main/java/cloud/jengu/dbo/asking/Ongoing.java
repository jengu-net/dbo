package cloud.jengu.dbo.asking;

import cloud.jengu.dbo.work.Holder;

import java.util.Optional;

/**
 * A run, as much of one as crosses a wire.
 *
 * <p>Seven fields rather than a {@link cloud.jengu.dbo.work.Run}'s eighteen,
 * and the difference is the point. A run holds what the store knows about it —
 * its trace, its tally, its assignment, the domains it touched, what it
 * produced — and a rendering of it on the tenant's surface carries the part a
 * screen asks about. Handing back a Run with the rest left null would put a
 * caller in the position of not being able to tell "this run has no domains"
 * from "the wire did not carry them", which is the answer-shaped hole this
 * store refuses everywhere else.
 *
 * <p>So the vocabulary hands back what both bindings can always fill, and a
 * caller that needs the whole of a run asks the store for it by id — which is
 * a different question, asked where the answer is.
 *
 * @param id          the run's own id, which is how to ask for the rest of it
 * @param key         the key it was minted under
 * @param process     the process it belongs to, absent where the rendering
 *                    carries none
 * @param step        the step it is of
 * @param holder      whose it is now
 * @param correlation the case it was filed under, absent where nobody set one
 * @param milestone   how far it said it had got, absent where it has said
 *                    nothing
 */
public record Ongoing(String id, String key, String process, String step, Holder holder,
        String correlation, String milestone) {

    public Ongoing {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "a run with no id cannot be asked about further, which is the one thing "
                            + "this type promises");
        }
    }

    /** The case it was filed under, where somebody set one. */
    public Optional<String> correlatedWith() {
        return Optional.ofNullable(correlation);
    }

    /** How far it said it had got, where it has said anything. */
    public Optional<String> reached() {
        return Optional.ofNullable(milestone);
    }
}
