package cloud.jengu.dbo.asking;

import cloud.jengu.dbo.work.Awaits;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * @param awaits      who owes its next act
 * @param correlation the case it was filed under, absent where nobody set one
 * @param milestone   how far it said it had got, absent where it has said
 *                    nothing
 * @param status      where it stands, in the Task's own words — {@code ready},
 *                    {@code in-progress}, {@code completed}, …
 * @param authored    when it was authored
 * @param moved       when it last moved
 * @param parentId    the id of the run it is part of, absent for a root or
 *                    where that run is not held here
 * @param parentKey   the key of the run it is part of, absent for a root
 * @param requester   who asked for it: a client, or a person by reference;
 *                    absent where nobody asked
 * @param inputs      what each of its slots names, slot to references, in the
 *                    step's order; an object given whole names nothing
 * @param place       the place it was performed at, absent for one performed
 *                    here
 * @param outcome     why it failed or what it refused, absent where nothing
 *                    did
 * @param tally       what it counted
 */
public record Ongoing(String id, String key, String process, String step, Awaits awaits,
        String correlation, String milestone, String status, Instant authored, Instant moved,
        String parentId, String parentKey, String requester, Map<String, List<String>> inputs,
        String place, String outcome, Map<String, Long> tally) {

    public Ongoing {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "a run with no id cannot be asked about further, which is the one thing "
                            + "this type promises");
        }
        inputs = inputs == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(inputs));
        tally = tally == null ? Map.of() : Map.copyOf(tally);
    }

    /** A run as it was answered before a screen asked when, and under what. */
    public Ongoing(String id, String key, String process, String step, Awaits awaits,
            String correlation, String milestone) {
        this(id, key, process, step, awaits, correlation, milestone, null, null, null, null,
                null, null, null, null, null, null);
    }

    /** Whether it was started under no other run: the top of a piece of work. */
    public boolean root() {
        return parentKey == null;
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
