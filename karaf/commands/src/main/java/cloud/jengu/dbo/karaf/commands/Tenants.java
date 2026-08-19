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

    static ServiceReference<?>[] references(BundleContext context) {
        try {
            return context.getAllServiceReferences(null, "(" + TENANT_PROPERTY + "=*)");
        } catch (org.osgi.framework.InvalidSyntaxException e) {
            throw new IllegalStateException("the tenant filter no longer parses", e);
        }
    }
}
