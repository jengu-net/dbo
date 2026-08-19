package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.fhir.common.FhirVersion;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.Hashtable;

/**
 * This bundle announces the version it serves.
 *
 * <p>The whole of R6's configuration answer is here: a container serves the
 * versions whose faces are installed, and the wiring resolves one under the
 * code a tenant declared rather than choosing between two it was compiled
 * against. Adding a version is installing a bundle; removing one is
 * uninstalling it, and the tenants that declared it are refused by name
 * instead of served by a face that no longer exists.
 *
 * <p>The service property carries the code as well, so a consumer can filter
 * on it without getting the service first.
 */
public final class Activator implements BundleActivator {

    private ServiceRegistration<FhirVersion> registration;

    @Override
    public void start(BundleContext ctx) {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put("fhir.version", R4FhirVersion.INSTANCE.code());
        registration = ctx.registerService(FhirVersion.class, R4FhirVersion.INSTANCE, properties);
    }

    @Override
    public void stop(BundleContext ctx) {
        if (registration != null) {
            registration.unregister();
            registration = null;
        }
    }
}
