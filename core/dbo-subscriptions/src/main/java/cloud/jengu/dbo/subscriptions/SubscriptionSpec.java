package cloud.jengu.dbo.subscriptions;

/** An active subscription, personality-parsed: id, criteria string, delivery endpoint. */
public record SubscriptionSpec(String id, String criteria, String endpoint, boolean idOnly) {

    /**
     * A subscription that asked for the resource itself.
     *
     * <p>Kept so a caller that predates the distinction still compiles, and
     * NOT the default anybody should reach for: a channel that declared no
     * payload is asking to be told <em>that</em> something changed, and FHIR
     * defaults to that rather than to the resource.
     */
    public SubscriptionSpec(String id, String criteria, String endpoint) {
        this(id, criteria, endpoint, false);
    }
}
