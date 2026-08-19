package cloud.jengu.dbo.karaf.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.table.ShellTable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The tenants this node is serving, read from the service registry.
 *
 * <p>A tenant is live exactly when its services are registered under a
 * {@code tenant=} property, so this asks the registry rather than reading the
 * spec directory: a spec file that failed to come up is not a tenant, and the
 * difference between "declared" and "serving" is the thing worth seeing.
 */
@Command(scope = "dbo-tenant", name = "list",
        description = "Lists the tenants this node is serving.")
@Service
public class TenantListCommand implements Action {

    @Option(name = "--surfaces",
            description = "Show which services each tenant has registered.")
    private boolean surfaces;

    @Override
    public Object execute() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();

        ServiceReference<?>[] references = Tenants.references(context);
        if (references == null || references.length == 0) {
            System.out.println("No tenant is being served. A spec in the tenant directory"
                    + " comes up within a couple of seconds; if one is not appearing, the"
                    + " reason is in the log.");
            return null;
        }

        Map<String, Tenant> tenants = new TreeMap<>();
        for (ServiceReference<?> reference : references) {
            String code = String.valueOf(reference.getProperty(Tenants.TENANT_PROPERTY));
            Tenant tenant = tenants.computeIfAbsent(code, Tenant::new);
            Object version = reference.getProperty(Tenants.FHIR_VERSION_PROPERTY);
            if (version != null) {
                tenant.fhirVersion = String.valueOf(version);
            }
            Object interfaces = reference.getProperty("objectClass");
            if (interfaces instanceof String[] names) {
                for (String name : names) {
                    tenant.surfaces.add(name.substring(name.lastIndexOf('.') + 1));
                }
            }
        }

        String base = "http://" + property(context, "dbo.tenant.http.host", "127.0.0.1")
                + ":" + property(context, "dbo.tenant.http.port", "8090");

        ShellTable table = new ShellTable();
        table.column("CODE");
        table.column("FHIR");
        table.column(surfaces ? "SURFACES" : "SERVICES");
        table.column("FHIR ENDPOINT");
        for (Tenant tenant : tenants.values()) {
            table.addRow().addContent(
                    tenant.code,
                    tenant.fhirVersion == null ? "?" : tenant.fhirVersion,
                    surfaces ? String.join(", ", tenant.surfaces)
                            : String.valueOf(tenant.surfaces.size()),
                    base + "/t/" + tenant.code + "/fhir");
        }
        table.print(System.out);
        return null;
    }

    private static String property(BundleContext context, String key, String fallback) {
        String value = context.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class Tenant {
        private final String code;
        private String fhirVersion;
        private final Set<String> surfaces = new TreeSet<>();

        private Tenant(String code) {
            this.code = code;
        }
    }
}
