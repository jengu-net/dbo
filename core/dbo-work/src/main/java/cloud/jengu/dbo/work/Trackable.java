package cloud.jengu.dbo.work;

import java.time.Instant;
import java.util.Map;

/**
 * One thing whose state is worth knowing.
 *
 * <p>One shape at every depth: the connector a store talks to, the appliance
 * behind it, and the instrument behind that are all this record. What differs
 * is only how presence is known — derived from a cursor for something that
 * reports for itself, attested by whoever last saw it for something that
 * cannot.
 *
 * <p><b>The fields are chosen so a face can project this mechanically.</b> A
 * version that spells connected things as a resource of its own will find an
 * identifier, a type, a parent and a state here, because those are the facts
 * such a resource is made of — and {@code routedBy} is a parent edge for
 * exactly the reason such resources have one. The engine still says none of
 * those words: it knows a trackable may route other trackables, and a face
 * knows what that renders as, the same line the run record holds against
 * the word for a task.
 *
 * @param id         as the router that reports it names it — opaque here,
 *                   because what makes an instrument identifiable is the
 *                   router's business and a store that invented the scheme
 *                   would be deciding what a trackable is
 * @param kind       the reporter's word for what this is, equally opaque. A
 *                   face maps it; the engine compares it and nothing else
 * @param routedBy   the trackable this one sits behind, or null for one that
 *                   reports for itself. The tree, one edge at a time
 * @param state      extensible key/value, opaque to the engine, replaced on
 *                   each report rather than accumulated — the same contract
 *                   vitals have, for the same reason: a state record must not
 *                   become a metrics history
 * @param attested   who last saw it and when, for something with no cursor of
 *                   its own; null where presence is derived instead. Not
 *                   second-class trust — knowing which hop last saw something
 *                   is what tells an operator where to look
 */
public record Trackable(String id, String kind, String routedBy, Map<String, String> state,
        Attested attested) {

    public Trackable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a trackable is identified, or it is not "
                    + "something whose state can be known");
        }
        state = state == null ? Map.of() : Map.copyOf(state);
    }

    /** Something that reports for itself: presence is derived from its cursor. */
    public static Trackable reporting(String id, String kind, Map<String, String> state) {
        return new Trackable(id, kind, null, state, null);
    }

    /** Something behind a router: presence is what the router says it saw. */
    public static Trackable routed(String id, String kind, String routedBy,
            Map<String, String> state) {
        return new Trackable(id, kind, routedBy, state, null);
    }

    /**
     * Who last saw a trackable, and when.
     *
     * <p>The observer is the <b>connected worker that reported</b>, which is
     * not always the parent: a connector reporting an instrument two hops away
     * is the observer, while the appliance between them is the parent. An
     * operator chasing something that has gone quiet needs both — where it
     * sits, and who to ask about it.
     */
    public record Attested(String observedBy, Instant at) {}

    /** Whether this one speaks for itself, which is what decides how presence is read. */
    public boolean reportsForItself() {
        return routedBy == null;
    }
}
