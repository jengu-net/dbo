package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.nio.file.Path;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Container wiring (dbo#17): registers the default provisioner when admin
 * config is present, REQUIRES a {@link TenantDatabaseProvisioner} service
 * (Alan's mandatory-service design), and runs the manager — per-tenant
 * service sets appear/retract in the registry with {@code tenant=<code>}
 * properties (REQ-DBO-CONT-DYNAMIC-TENANT-SERVICES).
 *
 * Framework properties: dbo.tenant.dir, dbo.tenant.http.host/.port,
 * dbo.tenant.admin.url/.user/.password (optional — enables the default).
 */
public final class Activator implements BundleActivator {

    private ServiceRegistration<TenantDatabaseProvisioner> defaultProvisioner;
    private ServiceTracker<TenantDatabaseProvisioner, TenantDatabaseProvisioner> tracker;
    private LocalDatabasePerTenantProvisioner localProvisioner;
    private TenantRuntimeManager manager;
    private final Map<String, List<ServiceRegistration<?>>> tenantRegistrations = new ConcurrentHashMap<>();

    @Override
    public void start(BundleContext ctx) {
        String adminUrl = ctx.getProperty("dbo.tenant.admin.url");
        if (adminUrl != null) {
            localProvisioner = new LocalDatabasePerTenantProvisioner(adminUrl,
                    ctx.getProperty("dbo.tenant.admin.user"),
                    ctx.getProperty("dbo.tenant.admin.password"));
            defaultProvisioner = ctx.registerService(TenantDatabaseProvisioner.class,
                    localProvisioner, null);
        }
        tracker = new ServiceTracker<>(ctx, TenantDatabaseProvisioner.class,
                new ServiceTrackerCustomizer<>() {
                    @Override
                    public TenantDatabaseProvisioner addingService(
                            ServiceReference<TenantDatabaseProvisioner> ref) {
                        TenantDatabaseProvisioner provisioner = ctx.getService(ref);
                        startManager(ctx, provisioner);
                        return provisioner;
                    }

                    @Override
                    public void modifiedService(ServiceReference<TenantDatabaseProvisioner> ref,
                            TenantDatabaseProvisioner s) {
                    }

                    @Override
                    public void removedService(ServiceReference<TenantDatabaseProvisioner> ref,
                            TenantDatabaseProvisioner s) {
                    }
                });
        tracker.open();
    }

    private synchronized void startManager(BundleContext ctx, TenantDatabaseProvisioner provisioner) {
        if (manager != null) {
            return; // exactly one manager; first provisioner wins
        }
        Path dir = Path.of(ctx.getProperty("dbo.tenant.dir"));
        String host = ctx.getProperty("dbo.tenant.http.host") != null
                ? ctx.getProperty("dbo.tenant.http.host") : "127.0.0.1";
        int port = ctx.getProperty("dbo.tenant.http.port") != null
                ? Integer.parseInt(ctx.getProperty("dbo.tenant.http.port")) : 0;

        manager = new TenantRuntimeManager(dir, provisioner, host, port,
                new TenantRuntimeManager.Listener() {
                    @Override
                    public void tenantUp(TenantRuntimeManager.TenantRuntime runtime) {
                        Hashtable<String, Object> props = new Hashtable<>();
                        props.put("tenant", runtime.spec().code());
                        props.put("fhir.version", runtime.spec().fhirVersion());
                        tenantRegistrations.put(runtime.spec().code(), List.of(
                                ctx.registerService(ObjectStore.class, runtime.engine(), props),
                                ctx.registerService(FhirStoreFacade.class, runtime.store(), props),
                                ctx.registerService(ChangeFeed.class, runtime.feed(), props)));
                    }

                    @Override
                    public void tenantDown(String code) {
                        List<ServiceRegistration<?>> registrations = tenantRegistrations.remove(code);
                        if (registrations != null) {
                            registrations.forEach(ServiceRegistration::unregister);
                        }
                    }
                });
        manager.start(2_000);
    }

    @Override
    public void stop(BundleContext ctx) {
        if (tracker != null) {
            tracker.close();
        }
        if (manager != null) {
            manager.close();
            manager = null;
        }
        if (defaultProvisioner != null) {
            defaultProvisioner.unregister();
        }
        if (localProvisioner != null) {
            localProvisioner.close();
        }
    }
}
