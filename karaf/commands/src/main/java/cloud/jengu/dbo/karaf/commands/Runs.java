package cloud.jengu.dbo.karaf.commands;

import cloud.jengu.dbo.core.api.ObjectStore;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The stores this node has, by tenant, so runs can be read where they live.
 *
 * <p>Runs are a tenant's records (#69), including the management tenant's for
 * tenant-lifecycle work (#74), so there is no node-wide list to read — the
 * console asks each tenant it can see.
 *
 * <p><b>This binds a tenant-plane service from the registry</b>, which the
 * console plan names as the thing a shipped console must not do: the operator
 * is deliberately less privileged than the tenant. It is acceptable here for
 * one reason and only that one — <b>this bundle is not in the serving
 * distribution</b>, it is development tooling dropped into a console's deploy
 * folder by somebody who already holds the database credentials. The guarded
 * route for a console that ships is the authenticated surface with a session
 * behind it (#76), and it is not this.
 */
final class Runs {

    private Runs() {
    }

    /** Every tenant's store, by code. */
    static Map<String, cloud.jengu.dbo.work.Runs> byTenant(BundleContext context, String only) {
        Map<String, cloud.jengu.dbo.work.Runs> stores = new TreeMap<>();
        ServiceReference<?>[] references;
        try {
            references = context.getServiceReferences(ObjectStore.class.getName(), null);
        } catch (org.osgi.framework.InvalidSyntaxException e) {
            throw new IllegalStateException(e);
        }
        if (references == null) {
            return stores;
        }
        for (ServiceReference<?> reference : references) {
            Object code = reference.getProperty(Tenants.TENANT_PROPERTY);
            if (code == null || (only != null && !only.equals(String.valueOf(code)))) {
                continue;
            }
            Object service = context.getService(reference);
            if (service instanceof ObjectStore store) {
                stores.put(String.valueOf(code), new cloud.jengu.dbo.work.Runs(store));
            }
        }
        return stores;
    }

    /** A tally as one column, in the order the run kept it. */
    static String tally(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "";
        }
        Map<String, Long> ordered = new LinkedHashMap<>(counts);
        StringBuilder text = new StringBuilder();
        ordered.forEach((name, count) -> text.append(text.isEmpty() ? "" : " ")
                .append(name).append('=').append(count));
        return text.toString();
    }
}
