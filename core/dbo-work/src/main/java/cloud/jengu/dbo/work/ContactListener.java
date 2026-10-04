package cloud.jengu.dbo.work;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Told when a node hears a worker of one step, and when it stops hearing it.
 *
 * <p><b>Contact is the application's word, and presence is the store's.</b>
 * Presence is derived from a participant's feed cursor and decides whether a
 * declaration is offered work. Contact decides nothing in the store: it is
 * whether this node has heard a worker for a step within a silence the
 * listener chose. A worker can be present and out of contact, or the
 * reverse.
 *
 * <p><b>Noticed, not stored.</b> Contact lives in memory on the node that
 * heard the worker, only for a step somebody listens to, and every event
 * names that node. Several nodes may disagree; what contact means across
 * them is the listener's to decide. Nothing about contact reaches a tenant's
 * records unless the listener starts work that writes it.
 *
 * <p><b>Activity</b> is any request a worker makes for the step: a poll that
 * names it, a claim, checkpoint, release or close of one of its runs, and a
 * heartbeat, which counts for every step the worker has declared on that
 * tenant. A <b>worker</b> is its client, its executor's name and its
 * version.
 *
 * <p>The events arrive in order, on a thread of the node's own, and a
 * listener that throws is named in the log and told the next event all the
 * same. What a listener does with an event is its own affair, and a slow one
 * delays only the events after it.
 */
public interface ContactListener {

    /** The step listened to, as {@code <module>.<process>.<step>}. */
    String step();

    /**
     * How long a worker may say nothing before it is unknown to this
     * listener on this node.
     *
     * <p>Required, with no default. The store imposes no freshness rule of its
     * own, because it cannot evaluate one: each hop owns liveness for the hop
     * below it, and a single threshold would be wrong for most of them. A
     * listener answering null, zero or less is refused when it is registered,
     * by name. Two listeners on one step may declare different silences.
     */
    Duration silence();

    /** The first activity after the worker was unknown here. */
    default void appeared(Appeared appeared) {
    }

    /** Each heartbeat's statistics, while the worker is in contact. */
    default void statistics(Statistics statistics) {
    }

    /** No activity within this listener's silence. Unknown, never gone. */
    default void unknown(Unknown unknown) {
    }

    /**
     * Everything for this step is unknown on this node and must appear again:
     * the node started, or this listener was registered on it.
     */
    default void reset(Reset reset) {
    }

    /**
     * Who was heard.
     *
     * @param client  the credential the request came on, or null where the
     *                lane was built in the node's own process
     * @param name    the executor's name
     * @param version the executor's version
     */
    record Worker(String client, String name, String version) {
    }

    /**
     * A worker heard after it was unknown.
     *
     * @param statistics what the activity carried, when it was a heartbeat;
     *                   null otherwise
     */
    record Appeared(String tenant, String step, Worker worker, String node, Instant since,
            Map<String, Object> statistics) {
    }

    /** One heartbeat, while in contact. */
    record Statistics(String tenant, String step, Worker worker, String node, Instant at,
            Map<String, Object> statistics) {
    }

    /** Silence for longer than the listener's threshold. */
    record Unknown(String tenant, String step, Worker worker, String node, Instant lastSeen) {
    }

    /** A node that knows nothing about this step's workers. */
    record Reset(String step, String node) {
    }
}
