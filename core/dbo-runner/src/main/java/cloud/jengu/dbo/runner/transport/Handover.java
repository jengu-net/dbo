package cloud.jengu.dbo.runner.transport;

import java.util.List;

/**
 * Where a place hands its work: the runs it performed and what they
 * produced, up the lane it already holds, to the tenant it is a place of.
 *
 * <p>Handed as the lane carries it, in its wire form, because what a batch
 * is belongs to the replication lane and not to the transport that moves it.
 * Applying is the tenant's, and repeatable: a batch handed twice applies
 * once, so a place that could not hear the answer hands it again.
 */
public interface Handover {

    /**
     * What the tenant made of a batch.
     *
     * @param applied how many items it wrote
     * @param skipped how many it held already, at that version or newer
     * @param refused the items it would not write, each with why
     * @param held    the versions of the place's records it holds now, each
     *                {@code type/id/version}; empty from a tenant that does
     *                not say, so nothing is taken to be held that was not said
     * @param kept    the versions it did not take because it holds a record of
     *                its own in their place, each {@code type/id/version Type/id}
     *                with the record it holds; empty from a tenant that does not say
     */
    record Taken(long applied, long skipped, List<String> refused, List<String> held,
            List<String> kept) {

        public Taken {
            refused = refused == null ? List.of() : List.copyOf(refused);
            held = held == null ? List.of() : List.copyOf(held);
            kept = kept == null ? List.of() : List.copyOf(kept);
        }

        /** Compatibility with callers that predate {@code kept}. */
        public Taken(long applied, long skipped, List<String> refused, List<String> held) {
            this(applied, skipped, refused, held, List.of());
        }

        /** Compatibility with callers that predate {@code held}. */
        public Taken(long applied, long skipped, List<String> refused) {
            this(applied, skipped, refused, List.of());
        }
    }

    /**
     * Hands one batch, filed under the place whose credential hands it.
     *
     * @param batch the batch in its wire form
     */
    Taken handed(Object batch);

    /**
     * Which of the place's versions the tenant holds as the place's, at that
     * version or newer. Asked once, by a place that began marking after it had
     * handed work home: nothing is written, and what the tenant does not hold
     * is not answered.
     *
     * @param versions each {@code type/id/version}
     * @return those of them the tenant holds
     * @throws IllegalStateException from a link that cannot ask
     */
    default List<String> holds(List<String> versions) {
        throw new IllegalStateException("this link does not ask what the tenant holds");
    }

    /**
     * The place's records the tenant's trail says it kept on the place, as it
     * held a record of its own in their place: what a place hands again once
     * the tenant knows an internal record by its id alone. In id order.
     *
     * @param after the id of the last record handed again, or null from the first
     * @return each {@code type/id}
     * @throws IllegalStateException from a link that cannot ask
     */
    default List<String> declined(String after, int limit) {
        throw new IllegalStateException("this link does not ask what the tenant declined");
    }
}
