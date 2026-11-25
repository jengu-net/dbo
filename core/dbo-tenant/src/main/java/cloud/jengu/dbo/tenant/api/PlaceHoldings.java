package cloud.jengu.dbo.tenant.api;

import java.time.Instant;
import java.util.Map;

/**
 * What a place of a tenant holds on this node, for an application running on
 * the place that says how its disk stands, as the node's own row says it to
 * whoever holds the deployment's token.
 *
 * <p>Registered per tenant, with a {@code tenant} property, on a node serving
 * a place of it, and nowhere else: a place's node is not handed the
 * deployment's token, so in-process is the one way code there reads it.
 *
 * <p>Read on the spot, in one pass over what the place holds, which is
 * cheap enough to read every few minutes.
 */
public interface PlaceHoldings {

    /**
     * One part of what is held.
     *
     * @param versions how many versions
     * @param bytes    what their payloads take as stored
     * @param oldest   when the oldest of them was written, or null when there is none
     * @param goesFrom the soonest retention may let any of it go, or null where
     *                 nothing says; no sooner than this, as open work keeps a
     *                 record and a sweep comes on its own hour
     */
    record Part(long versions, long bytes, Instant oldest, Instant goesFrom) {}

    /**
     * What the place holds now.
     *
     * @param alone            what its tenant is not known to hold, of the types its
     *                         declaration bounds: what retention waits on
     * @param aloneByType      the same, by type
     * @param elsewhere        what is held elsewhere and not yet let go: what the
     *                         coming sweeps remove
     * @param elsewhereByType  the same, by type
     * @param unbounded        how many versions no declaration bounds, which retention
     *                         never lets go of: the store's own housekeeping, and
     *                         types declared to be kept
     * @param lastSwept        when retention last swept the tenant on this node, or
     *                         null when it has not since the node started
     * @param nextSweep        when it next sweeps, or null on a node that sweeps only
     *                         when asked
     */
    record Holdings(Part alone, Map<String, Part> aloneByType, Part elsewhere,
            Map<String, Part> elsewhereByType, long unbounded, Instant lastSwept,
            Instant nextSweep) {

        public Holdings {
            aloneByType = Map.copyOf(aloneByType);
            elsewhereByType = Map.copyOf(elsewhereByType);
        }
    }

    /** What the place holds now, read on the spot. */
    Holdings now();
}
