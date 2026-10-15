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
     */
    record Taken(long applied, long skipped, List<String> refused) {

        public Taken {
            refused = refused == null ? List.of() : List.copyOf(refused);
        }
    }

    /**
     * Hands one batch, filed under the place whose credential hands it.
     *
     * @param batch the batch in its wire form
     */
    Taken handed(Object batch);
}
