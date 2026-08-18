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
 * Container wiring: registers the default provisioner when admin
 * config is present, REQUIRES a {@link TenantDatabaseProvisioner} service
 * (the mandatory-service design), and runs the manager — per-tenant
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
            String rpRedirects = ctx.getProperty("dbo.tenant.rp.redirect.uris");
            if (rpRedirects != null && !rpRedirects.isBlank()) {
                // embedded/local RP custody: {code}
                // resolves per tenant at provision time
                localProvisioner.rpRedirectUris(java.util.List.of(rpRedirects.split(",")));
                localProvisioner.rpClientId(ctx.getProperty("dbo.tenant.rp.client.id"));
            }
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

    /** "tara=secret1,eeid=secret2" — custody by broker code (§17.1). */
    private static java.util.Map<String, String> parseBrokerSecrets(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String pair : csv.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return java.util.Map.copyOf(out);
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

        String kekB64 = ctx.getProperty("dbo.tenant.auth.kek");
        String brokerIssuer = ctx.getProperty("dbo.tenant.auth.broker.issuer");
        cloud.jengu.dbo.auth.IdentityHub.Upstream upstream = brokerIssuer == null ? null
                : new cloud.jengu.dbo.auth.IdentityHub.Upstream(brokerIssuer,
                        ctx.getProperty("dbo.tenant.auth.broker.client.id"),
                        ctx.getProperty("dbo.tenant.auth.broker.client.secret"),
                        ctx.getProperty("dbo.tenant.auth.broker.subject.strip"));
        TenantRuntimeManager.AuthorityConfig authority = kekB64 == null ? null
                : new TenantRuntimeManager.AuthorityConfig(
                        java.util.Base64.getDecoder().decode(kekB64),
                        ctx.getProperty("dbo.tenant.auth.issuer.base"),
                        upstream,
                        ctx.getProperty("dbo.tenant.auth.subject.system"),
                        parseBrokerSecrets(ctx.getProperty("dbo.tenant.auth.broker.secrets")));
        manager = new TenantRuntimeManager(dir, provisioner, host, port,
                new TenantRuntimeManager.Listener() {
                    @Override
                    public void tenantUp(TenantRuntimeManager.TenantRuntime runtime) {
                        Hashtable<String, Object> props = new Hashtable<>();
                        props.put("tenant", runtime.spec().code());
                        props.put("fhir.version", runtime.spec().fhirVersion());
                        java.util.List<ServiceRegistration<?>> regs = new java.util.ArrayList<>(List.of(
                                ctx.registerService(ObjectStore.class, runtime.engine(), props),
                                ctx.registerService(FhirStoreFacade.class, runtime.store(), props),
                                ctx.registerService(ChangeFeed.class, runtime.feed(), props)));
                        if (runtime.engine() instanceof cloud.jengu.dbo.policy.PolicyObjectStore p) {
                            // §15.1: module engines contribute custom audit
                            // events through this per-tenant recorder surface
                            regs.add(ctx.registerService(
                                    cloud.jengu.dbo.policy.PolicyObjectStore.class, p, props));
                        }
                        tenantRegistrations.put(runtime.spec().code(), regs);
                    }

                    @Override
                    public void tenantDown(String code) {
                        List<ServiceRegistration<?>> registrations = tenantRegistrations.remove(code);
                        if (registrations != null) {
                            registrations.forEach(ServiceRegistration::unregister);
                        }
                    }
                }, authority);
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
