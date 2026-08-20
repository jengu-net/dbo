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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger("dbo.server");

    private ServiceRegistration<TenantDatabaseProvisioner> defaultProvisioner;
    private ServiceTracker<TenantDatabaseProvisioner, TenantDatabaseProvisioner> tracker;
    private LocalDatabasePerTenantProvisioner localProvisioner;
    private TenantRuntimeManager manager;
    private final Map<String, List<ServiceRegistration<?>>> tenantRegistrations = new ConcurrentHashMap<>();

    @Override
    public void start(BundleContext ctx) {
        // The posture, not just the fact of starting. Every field here is
        // something that silently differs between two deployments that look
        // identical, and each has at some point been the answer to "why is
        // this box behaving differently from that one".
        LOG.info("starting: component=dbo-server version={} jdk={} os={} "
                + "provisioner={} authority={} bind={}:{} specs={}",
                version(), Runtime.version(),
                System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                ctx.getProperty("dbo.tenant.k8s.namespace") != null
                        ? "kubernetes-secrets" : "local-database-per-tenant",
                ctx.getProperty("dbo.tenant.auth.kek") != null ? "enabled" : "DISABLED",
                ctx.getProperty("dbo.tenant.http.host"),
                ctx.getProperty("dbo.tenant.http.port"),
                ctx.getProperty("dbo.tenant.dir"));
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

    /**
     * The versions the registry holds right now (R6).
     *
     * <p>Asked at every bring-up rather than captured once, because face
     * bundles come and go: a version installed after the manager started is
     * served by the next scan, and one uninstalled stops being served without
     * anything having to be told. The classpath fallback the plain
     * constructors use would answer "none" here — a bundle's own loader sees
     * no other bundle's providers — so the container never takes it.
     */
    private static cloud.jengu.dbo.fhir.common.FhirVersions registered(BundleContext ctx) {
        cloud.jengu.dbo.fhir.common.FhirVersions live = fromRegistry(ctx);
        if (!Boolean.parseBoolean(ctx.getProperty("dbo.face.watch"))) {
            return live;
        }
        // Gated on purpose (#55): the check costs a volatile read per call and
        // a stack walk per hand-out, and what it catches is a deployment fault
        // rather than a daily one — a tenant still being served by a face
        // bundle nobody can see any more. Worth paying for while a container's
        // dynamics are being changed.
        cloud.jengu.dbo.fhir.common.WatchedVersions watched =
                new cloud.jengu.dbo.fhir.common.WatchedVersions(live);
        new ServiceTracker<cloud.jengu.dbo.fhir.common.FhirVersion,
                cloud.jengu.dbo.fhir.common.FhirVersion>(ctx,
                cloud.jengu.dbo.fhir.common.FhirVersion.class, null) {
            @Override
            public void removedService(
                    ServiceReference<cloud.jengu.dbo.fhir.common.FhirVersion> ref,
                    cloud.jengu.dbo.fhir.common.FhirVersion version) {
                Object code = ref.getProperty("fhir.version");
                if (code != null) {
                    watched.withdrawn(code.toString());
                }
                super.removedService(ref, version);
            }
        }.open();
        return watched;
    }

    private static cloud.jengu.dbo.fhir.common.FhirVersions fromRegistry(BundleContext ctx) {
        return new cloud.jengu.dbo.fhir.common.FhirVersions() {
            @Override
            public java.util.Optional<cloud.jengu.dbo.fhir.common.FhirVersion> byCode(String code) {
                return installed().stream().filter(v -> v.code().equals(code)).findFirst();
            }

            @Override
            public java.util.Set<String> codes() {
                return installed().stream()
                        .map(cloud.jengu.dbo.fhir.common.FhirVersion::code)
                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            }

            private java.util.List<cloud.jengu.dbo.fhir.common.FhirVersion> installed() {
                try {
                    java.util.List<cloud.jengu.dbo.fhir.common.FhirVersion> found =
                            new java.util.ArrayList<>();
                    for (ServiceReference<cloud.jengu.dbo.fhir.common.FhirVersion> ref
                            : ctx.getServiceReferences(
                                    cloud.jengu.dbo.fhir.common.FhirVersion.class, null)) {
                        cloud.jengu.dbo.fhir.common.FhirVersion version = ctx.getService(ref);
                        if (version != null) {
                            found.add(version);
                        }
                    }
                    return found;
                } catch (org.osgi.framework.InvalidSyntaxException e) {
                    throw new IllegalStateException("no filter was given, so none can be invalid", e);
                }
            }
        };
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

    /** The bundle's version, or a marker when it has none. */
    private static String version() {
        Package p = Activator.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "dev" : v;
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
                }, authority, registered(ctx));
        // #74: the tenant this deployment's own history lives in, brought up
        // before anything else and declared by configuration rather than by a
        // file in the watched directory. A deployment whose management tenant
        // will not come up serves nothing — the one failure with nowhere to be
        // recorded, so it goes to the log and stops the start.
        String management = ctx.getProperty("dbo.tenant.management.spec");
        if (management != null && !management.isBlank()) {
            try {
                manager.manages(Path.of(management));
            } catch (RuntimeException e) {
                LOG.error("the management tenant did not come up, so this deployment serves "
                        + "nothing: spec={}", management, e);
                manager.close();
                manager = null;
                throw e;
            }
        }
        // #67: a runtime can be asked what it is serving, when a deployment has
        // said who may ask.
        manager.serveRuntimeState(ctx.getProperty("dbo.tenant.ops.token"));
        manager.start(2_000);
    }

    @Override
    public void stop(BundleContext ctx) {
        LOG.info("shutdown requested: component=dbo-server");
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
