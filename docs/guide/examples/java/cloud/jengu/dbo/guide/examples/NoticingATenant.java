package cloud.jengu.dbo.guide.examples;

import cloud.jengu.dbo.tenant.TenantFacts;
import cloud.jengu.dbo.tenant.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.TenantPoint;

/** Told when a tenant reaches a point. Registered against the point it wants. */
public final class NoticingATenant implements TenantLifecycleListener {

    @Override
    public void reached(TenantPoint point, TenantFacts tenant) {
        // Rare, and re-derivable if it is missed: register the tenant with
        // something outside, warm a cache, announce it. Anything that must not
        // miss an event is an observer instead.
        System.out.println("noticed " + tenant.code() + " at " + point);
    }
}
