package cloud.jengu.dbo.core.api.feed;

import java.time.Instant;
import java.util.Map;

/**
 * Searches a tenant's own work watches its changes with, beside the
 * subscriptions others hold.
 *
 * <p>Matched by the store, as a subscription's criteria are: a change matches
 * a watch iff its record, as it now stands, is found by the search. Each match
 * is handed over once per watch and record version, in a durable workflow, and
 * may ask to be looked at again at a moment it names — in which case the
 * workflow sleeps until then, asks the store again whether the record still
 * matches, and hands it over again only if it does.
 */
public interface Watching {

    /** Watch name to search, as {@code Type?param=value}; read on every round. */
    Map<String, String> searches();

    /**
     * A record that matched a watch.
     *
     * @return when to look at it again, or null when nothing more is wanted
     */
    Instant matched(String watch, String type, String id, long version);

    /**
     * The same, saying which run's result wrote that version, or null where
     * no run did: what a run started by the change is a part of.
     */
    default Instant matched(String watch, String type, String id, long version, String run) {
        return matched(watch, type, id, version);
    }
}
