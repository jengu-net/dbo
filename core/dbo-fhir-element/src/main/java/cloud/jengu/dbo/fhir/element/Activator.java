package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.fhir.common.FhirVersion;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * This bundle announces every version whose definitions it carries.
 *
 * <p>One face, several versions: what is announced is what was pinned into the
 * bundle at build time, so adding a version is a package and a line in the
 * build rather than a new bundle — and a container's set of versions is still
 * what is installed rather than what anything was compiled against.
 */
public final class Activator implements BundleActivator {

    private final List<ServiceRegistration<FhirVersion>> registrations = new ArrayList<>();

    @Override
    public void start(BundleContext ctx) {
        for (String code : CarriedDefinitions.versions()) {
            Hashtable<String, Object> properties = new Hashtable<>();
            properties.put("fhir.version", code);
            registrations.add(ctx.registerService(FhirVersion.class,
                    ElementFhirVersion.serving(code), properties));
        }
    }

    @Override
    public void stop(BundleContext ctx) {
        registrations.forEach(ServiceRegistration::unregister);
        registrations.clear();
    }
}
