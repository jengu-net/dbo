package cloud.jengu.dbo.tenant;

import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What runs at each point, and which tenants each of them is for.
 *
 * <p>An activity states its selector when it is registered; it does not work
 * out whether it applies. That is the whole of the change: the condition that
 * used to sit inline at the call site — and, in one case, was never written at
 * all — becomes a filter beside the thing it governs, where its absence is a
 * statement rather than an oversight.
 *
 * <p>dbo registers its own provisioning here on the same terms as anybody
 * else's. The composition root stops asking what kind of tenant it is holding
 * and runs what matches it.
 *
 * <p>The filter is the framework's own, over the tenant's published facts, so
 * there is one selector language rather than a second one invented here.
 */
final class TenantActivities {

    /**
     * The tenant as it stands at the point an activity runs, and what it acts
     * on.
     *
     * <p>What is populated depends on the point, because a tenant is not the
     * same thing at all of them: at {@code dispatch} the store exists and no
     * door does, so everything from {@link #authority()} on is null. Rather
     * than a second context type per point, the record says what a tenant IS
     * and each activity takes what its own point guarantees — which is the
     * whole argument for having points at all.
     *
     * @param spec      what was declared, for an activity that needs more than
     *                  the facts it selected on
     * @param authority the tenant's own, or null when it has none — the
     *                  {@code hasAuthority} fact is the selector for it
     * @param runtime   assembled and staged, from {@code surfaces} onward
     * @param guard     the request guard built over that authority, and the
     *                  same object the records surface is guarded by — a
     *                  second one would be a second answer to who is asking
     * @param vault     the person vault, or null — {@code hasVault} selects
     * @param laneRuns  the tenant's runs, with its step catalogue composed in
     */
    record Provisioned(TenantFacts facts, javax.sql.DataSource dataSource,
            cloud.jengu.dbo.fhir.common.FhirStoreFacade store, String recordDomain,
            TenantSpec spec,
            cloud.jengu.dbo.auth.TenantAuthority authority,
            TenantRuntimeManager.TenantRuntime runtime,
            cloud.jengu.dbo.rest.RequestAuthenticator guard,
            cloud.jengu.dbo.pdi.PersonVault vault,
            cloud.jengu.dbo.work.Runs laneRuns) {

        /** What a tenant is before it has a door: the shape dispatch runs on. */
        Provisioned(TenantFacts facts, javax.sql.DataSource dataSource,
                cloud.jengu.dbo.fhir.common.FhirStoreFacade store, String recordDomain) {
            this(facts, dataSource, store, recordDomain, null, null, null, null, null, null);
        }
    }

    /**
     * Something done to a tenant at a point.
     *
     * <p>Returns what should be closed when the tenant goes, or null where
     * there is nothing to close.
     */
    @FunctionalInterface
    interface Activity {
        AutoCloseable perform(Provisioned tenant) throws Exception;
    }

    private record Registered(TenantPoint point, Filter target, String name, Activity activity) {
        boolean appliesTo(TenantFacts facts) {
            // No filter is every tenant, deliberately: some activities do
            // apply to all of them, and saying so is not the same as the
            // silence this class exists to remove.
            return target == null || target.matches((Map<String, ?>) facts.properties());
        }
    }

    private final List<Registered> registered = new CopyOnWriteArrayList<>();

    /**
     * @param target the filter over a tenant's published facts, or null for
     *               every tenant — which is a choice, not a default
     */
    void register(TenantPoint point, String target, String name, Activity activity) {
        Filter filter = null;
        TenantFacts.refuseUnpublishedFacts(target, name);
        if (target != null && !target.isBlank()) {
            try {
                filter = FrameworkUtil.createFilter(target);
            } catch (InvalidSyntaxException e) {
                // Refused when it is registered rather than when it is first
                // consulted: a filter nobody could parse would otherwise match
                // nothing, quietly, which is the failure mode this replaces.
                throw new IllegalArgumentException(
                        name + ": the target filter cannot be parsed: " + target, e);
            }
        }
        registered.add(new Registered(point, filter, name, activity));
    }

    /**
     * Runs what applies to this tenant at this point, in registration order.
     *
     * <p>A failing activity is reported by name and does not stop the others
     * or the tenant: what an activity does is its author's business, and
     * whether its failure is visible is dbo's. Reporting it here is the whole
     * reason the defect this replaces went unseen — the failure was swallowed
     * where it happened, once a second, saying nothing.
     */
    List<AutoCloseable> runAt(TenantPoint point, Provisioned tenant,
            java.util.function.BiConsumer<String, Exception> failed) {
        List<AutoCloseable> closeables = new ArrayList<>();
        for (Registered one : registered) {
            if (one.point() != point || !one.appliesTo(tenant.facts())) {
                continue;
            }
            try {
                AutoCloseable closeable = one.activity().perform(tenant);
                if (closeable != null) {
                    closeables.add(closeable);
                }
            } catch (Exception e) {
                failed.accept(one.name(), e);
            }
        }
        return closeables;
    }
}
