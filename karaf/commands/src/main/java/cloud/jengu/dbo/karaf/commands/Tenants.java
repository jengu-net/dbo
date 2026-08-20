package cloud.jengu.dbo.karaf.commands;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import java.util.Set;
import java.util.TreeSet;

/** What the registry says this node is serving. */
final class Tenants {

    /** Registered on every per-tenant service by the tenant runtime. */
    static final String TENANT_PROPERTY = "tenant";
    static final String FHIR_VERSION_PROPERTY = "fhir.version";

    private Tenants() {
    }

    /**
     * The codes currently being served, sorted.
     *
     * <p>From the registry, so what completes is what a command can actually
     * act on — a spec file that never came up should not be offered.
     */
    static Set<String> codes(BundleContext context) {
        Set<String> codes = new TreeSet<>();
        ServiceReference<?>[] references = references(context);
        if (references != null) {
            for (ServiceReference<?> reference : references) {
                Object code = reference.getProperty(TENANT_PROPERTY);
                if (code != null) {
                    codes.add(String.valueOf(code));
                }
            }
        }
        return codes;
    }

    /** The bundle that serves tenants, whatever state it is in. */
    static org.osgi.framework.Bundle runtimeBundle(BundleContext context) {
        for (org.osgi.framework.Bundle bundle : context.getBundles()) {
            if ("cloud.jengu.dbo.tenant".equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

    /**
     * Why there are no tenants, when there are none.
     *
     * <p>Three different situations look identical from the registry, and only
     * one of them is about the spec directory: the set is not installed, the
     * tenant runtime is installed and did not start — a port already bound is
     * the usual reason, and the activator failing leaves the bundle Resolved
     * forever — or it is running and nothing is declared. Saying the last one
     * in all three cases sends somebody to look at files that are fine.
     */
    static String whyNothingIsServed(BundleContext context) {
        org.osgi.framework.Bundle runtime = runtimeBundle(context);
        if (runtime == null) {
            return "The dbo bundles are not installed here yet. Run dbo-console:up.";
        }
        if (runtime.getState() != org.osgi.framework.Bundle.ACTIVE) {
            return "The tenant runtime is installed and not running (bundle "
                    + runtime.getBundleId() + " is "
                    + stateName(runtime.getState()) + "), so nothing can be served."
                    + " Its activator failed — bundle:diag " + runtime.getBundleId()
                    + " and the log have the reason; a port already bound by another dbo"
                    + " is the usual one. Starting it again after the port is free is"
                    + " bundle:start " + runtime.getBundleId() + ".";
        }
        return "No tenant is being served. A spec in the tenant directory comes up within"
                + " a couple of seconds; if one is not appearing, the reason is in the log.";
    }

    private static String stateName(int state) {
        return switch (state) {
            case org.osgi.framework.Bundle.INSTALLED -> "Installed";
            case org.osgi.framework.Bundle.RESOLVED -> "Resolved";
            case org.osgi.framework.Bundle.STARTING -> "Starting";
            case org.osgi.framework.Bundle.STOPPING -> "Stopping";
            case org.osgi.framework.Bundle.UNINSTALLED -> "Uninstalled";
            default -> "Active";
        };
    }

    static ServiceReference<?>[] references(BundleContext context) {
        try {
            return context.getAllServiceReferences(null, "(" + TENANT_PROPERTY + "=*)");
        } catch (org.osgi.framework.InvalidSyntaxException e) {
            throw new IllegalStateException("the tenant filter no longer parses", e);
        }
    }
}
