package cloud.jengu.dbo.tenant;

/**
 * Something outside the runtime told when a tenant reaches a point.
 *
 * <p>Registered as a service with the point it wants and — optionally — a
 * filter over the tenant's published facts:
 *
 * <pre>
 * dbo.tenant.point  = serving
 * dbo.tenant.target = (&amp;(dbo.tenant.kind=ext.clinic)(dbo.tenant.zone=rl))
 * </pre>
 *
 * <p>This is the one extension API that is a callback rather than a feed
 * consumer, and the reason is that it is the only one with no feed: a tenant
 * coming up is not a record in the tenant's own database, so there is no
 * stream of it to read. Everything that <em>is</em> a record — work, the
 * trail, identity, the tenant's own content — is watched with a
 * {@link TenantObserver} instead, which survives its bundle being down.
 *
 * <p>So a listener here is for what is rare and can be re-derived: noticing a
 * tenant, registering it somewhere, warming something. Anything that must not
 * miss an event does not belong on this interface.
 *
 * <p>What a listener does is its author's business. What dbo owns is that a
 * failure here is reported by name rather than swallowed, and that it cannot
 * stop the tenant from serving: a tenant degraded by somebody's listener is
 * better than a deployment an absent listener can take down.
 */
@FunctionalInterface
public interface TenantLifecycleListener {

    /**
     * Called when a tenant reaches a point this listener selected.
     *
     * @param point  where in its life the tenant is
     * @param tenant the facts this listener was selected by
     */
    void reached(TenantPoint point, TenantFacts tenant);
}
