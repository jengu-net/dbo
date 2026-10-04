package cloud.jengu.dbo.work;

/**
 * One participant a router has said sits behind it.
 *
 * <p>The store keeps this one fact because sealing needs it: a router holds
 * the claim on work it cannot read and names a routee as the recipient, and
 * the store seals past the router only to what the router said is behind it.
 * The routee's key is not here — it is the key the routee enrolled with.
 *
 * <p>Nothing else about a routee is the store's. What it is and how it is
 * doing are the router's to say, in its heartbeat statistics, to whoever
 * listens for it.
 *
 * @param id       the routee, as it enrolled — the name a seal is addressed to
 * @param routedBy the participant it sits behind; one a router reports with
 *                 none sits directly behind that router
 */
public record Trackable(String id, String routedBy) {

    public Trackable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a routee is named, or nothing can be sealed "
                    + "to it");
        }
    }

    /** A routee, and the participant it sits behind. */
    public static Trackable routed(String id, String routedBy) {
        return new Trackable(id, routedBy);
    }
}
