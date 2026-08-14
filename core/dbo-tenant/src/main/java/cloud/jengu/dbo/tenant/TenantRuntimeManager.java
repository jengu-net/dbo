package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Turns tenant spec files into live tenant service sets (dbo#17):
 * spec appears → provision (via the mandatory {@link TenantDatabaseProvisioner})
 * → personality + engine + facade + feed → FHIR endpoint at
 * {@code /t/<code>/fhir} on ONE shared port (the interim until the routing
 * layer) → listener callback (the OSGi activator registers services there).
 *
 * <p>Spec removal RETRACTS (endpoint down, pool released — data untouched);
 * erasure is only ever the explicit provisioner {@code deprovision}.
 */
public final class TenantRuntimeManager implements AutoCloseable {

    public record TenantRuntime(
            TenantSpec spec,
            ObjectStore engine,
            FhirStoreFacade store,
            ChangeFeed feed,
            FhirHttpServer endpoint) {}

    public interface Listener {
        void tenantUp(TenantRuntime runtime);

        void tenantDown(String code);
    }

    private final Path directory;
    private final TenantDatabaseProvisioner provisioner;
    private final Listener listener;
    private final HttpServer sharedServer;
    private final String host;
    private final Map<String, TenantRuntime> runtimes = new ConcurrentHashMap<>();
    private final AuthorityConfig authorityConfig;
    private final Map<String, String> authorityContexts = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.policy.RetentionSweep> sweeps = new ConcurrentHashMap<>();
    private volatile long lastSweepMillis;
    private volatile Thread scanner;
    private volatile boolean running;

    /**
     * §13 authority wiring: when present, every tenant gets its own OIDC
     * authority at {@code /t/<code>/oidc} and the store surface accepts only
     * that authority's tokens. issuerBase null → derived from the serving
     * address (deployment config, like the REST baseUrl).
     */
    public record AuthorityConfig(byte[] kek, String issuerBase) {}

    public TenantRuntimeManager(Path directory, TenantDatabaseProvisioner provisioner,
            String host, int port, Listener listener) {
        this(directory, provisioner, host, port, listener, null);
    }

    public TenantRuntimeManager(Path directory, TenantDatabaseProvisioner provisioner,
            String host, int port, Listener listener, AuthorityConfig authorityConfig) {
        this.authorityConfig = authorityConfig;
        this.directory = directory;
        this.provisioner = provisioner;
        this.host = host;
        this.listener = listener != null ? listener : new Listener() {
            @Override
            public void tenantUp(TenantRuntime runtime) {
            }

            @Override
            public void tenantDown(String code) {
            }
        };
        try {
            this.sharedServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sharedServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        sharedServer.start();
    }

    public int port() {
        return sharedServer.getAddress().getPort();
    }

    public String baseUrl(String code) {
        return "http://" + host + ":" + port() + "/t/" + code + "/fhir";
    }

    public Set<String> codes() {
        return Set.copyOf(runtimes.keySet());
    }

    public Optional<TenantRuntime> runtime(String code) {
        return Optional.ofNullable(runtimes.get(code));
    }

    /** One deterministic reconciliation round. Returns codes currently served. */
    public synchronized Set<String> scanOnce() {
        Set<String> declared = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> {
                        try {
                            TenantSpec spec = TenantSpec.parse(Files.readString(f));
                            declared.add(spec.code());
                            if (!runtimes.containsKey(spec.code())) {
                                bringUp(spec);
                            }
                        } catch (Exception e) {
                            // a malformed spec provisions nothing — but the
                            // failure must be diagnosable from the process
                            // output, not only via absence
                            System.err.println("dbo-tenant: bring-up failed for " + f.getFileName());
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String code : Set.copyOf(runtimes.keySet())) {
            if (!declared.contains(code)) {
                takeDown(code);
            }
        }
        return codes();
    }

    private void bringUp(TenantSpec spec) {
        TenantDatabaseProvisioner.TenantDatabase db = provisioner.provision(spec);
        String base = baseUrl(spec.code());
        cloud.jengu.dbo.rest.RequestAuthenticator guard = null;
        cloud.jengu.dbo.auth.TenantAuthority tenantAuthority = null;
        if (authorityConfig != null) {
            String issuerBase = authorityConfig.issuerBase() != null
                    ? authorityConfig.issuerBase()
                    : "http://" + host + ":" + port();
            String oidcPath = "/t/" + spec.code() + "/oidc";
            cloud.jengu.dbo.auth.TenantAuthority authority = tenantAuthority = new cloud.jengu.dbo.auth.TenantAuthority(
                    new PgObjectStore(db.dataSource(), cloud.jengu.dbo.auth.IdentityModel.registrations()),
                    issuerBase + oidcPath,
                    new cloud.jengu.dbo.auth.KeyProtector(authorityConfig.kek()));
            authority.ensureSigningKey();
            if (db.bootstrapClientSecret() != null) {
                authority.ensureClient("tenant-bootstrap", db.bootstrapClientSecret(),
                        java.util.List.of("system/*.read", "system/*.write"));
            }
            sharedServer.createContext(oidcPath,
                    new cloud.jengu.dbo.auth.AuthorityHandler(authority, oidcPath));
            authorityContexts.put(spec.code(), oidcPath);
            guard = new cloud.jengu.dbo.auth.AuthorityAuthenticator(authority);
        }
        final cloud.jengu.dbo.auth.TenantAuthority authority = tenantAuthority;
        TenantRuntime runtime;
        if ("r4".equals(spec.fhirVersion())) {
            R4Personality personality = new R4Personality(spec.types());
            ObjectStore engine = policyWrapped(spec, db, personality.registrations(), R4Personality.DOMAIN);
            if (authority != null) {
                authority.attachSubjects(engine); // §16.1: subjects are the tenant's records
            }
            FhirStoreFacade store = new R4Store(engine, personality, base);
            runtime = new TenantRuntime(spec, engine, store,
                    new PgChangeFeed(db.dataSource(), R4Personality.DOMAIN),
                    withAuditSurface(withPolicyNote(new FhirHttpServer(sharedServer, store, null,
                            "/t/" + spec.code() + "/fhir", guard), spec), spec, engine));
        } else {
            R5Personality personality = new R5Personality(spec.types());
            ObjectStore engine = policyWrapped(spec, db, personality.registrations(), R5Personality.DOMAIN);
            if (authority != null) {
                authority.attachSubjects(engine);
            }
            FhirStoreFacade store = new R5Store(engine, personality, base);
            runtime = new TenantRuntime(spec, engine, store,
                    new PgChangeFeed(db.dataSource(), R5Personality.DOMAIN),
                    withAuditSurface(withPolicyNote(new FhirHttpServer(sharedServer, store, null,
                            "/t/" + spec.code() + "/fhir", guard), spec), spec, engine));
        }
        runtimes.put(spec.code(), runtime);
        listener.tenantUp(runtime);
    }

    /**
     * §14: under PDI the engine is the isolation decorator — identifying
     * elements encrypted in place per person, identity vault-side. The
     * working key derives from the authority KEK (machinery custody); PDI
     * therefore requires the authority to be configured.
     */
    private ObjectStore pdiWrapped(TenantSpec spec,
            TenantDatabaseProvisioner.TenantDatabase db,
            java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> registrations) {
        if (!spec.pdi()) {
            return new PgObjectStore(db.dataSource(), registrations);
        }
        if (authorityConfig == null) {
            throw new IllegalStateException(spec.code()
                    + ": pdi requires the tenant authority (the working key derives from its KEK)");
        }
        cloud.jengu.dbo.pdi.PdiSpec pdiSpec = cloud.jengu.dbo.pdi.PdiSpec.fhir();
        PgObjectStore inner = new PgObjectStore(db.dataSource(),
                cloud.jengu.dbo.pdi.PdiSetup.transform(registrations, pdiSpec));
        return new cloud.jengu.dbo.pdi.PdiObjectStore(inner,
                new cloud.jengu.dbo.pdi.PersonVault(db.dataSource(), authorityConfig.kek()),
                pdiSpec);
    }

    /**
     * §15: the policy decorator is OUTERMOST (audit sees interactions after
     * authorization, never content); the AuditEntry model joins the engine's
     * registrations so entries are regular records in the tenant's store;
     * retention sweeps at bring-up (a restored pre-sweep archive comes up
     * already swept) and periodically from the scan loop.
     */
    private ObjectStore policyWrapped(TenantSpec spec,
            TenantDatabaseProvisioner.TenantDatabase db,
            java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> registrations,
            String domain) {
        java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> all =
                new java.util.ArrayList<>(registrations);
        all.addAll(cloud.jengu.dbo.policy.AuditModel.registrations());
        ObjectStore engine = pdiWrapped(spec, db, all);
        cloud.jengu.dbo.policy.PolicyObjectStore policyStore =
                new cloud.jengu.dbo.policy.PolicyObjectStore(engine, spec.policies());
        if (!spec.policies().retention().isEmpty()) {
            cloud.jengu.dbo.policy.RetentionSweep sweep = new cloud.jengu.dbo.policy.RetentionSweep(
                    db.dataSource(), domain, spec.policies(), policyStore);
            sweep.sweepOnce();
            sweeps.put(spec.code(), sweep);
        }
        return policyStore;
    }

    private static FhirHttpServer withPolicyNote(FhirHttpServer server, TenantSpec spec) {
        server.policyNote = spec.policies().describe();
        return server;
    }

    private static FhirHttpServer withAuditSurface(FhirHttpServer server, TenantSpec spec,
            ObjectStore engine) {
        if (engine instanceof cloud.jengu.dbo.policy.PolicyObjectStore policyStore) {
            server.auditSurface = new cloud.jengu.dbo.policy.FhirAuditProjection(
                    policyStore, spec.fhirVersion());
        }
        return server;
    }

    private void takeDown(String code) {
        TenantRuntime runtime = runtimes.remove(code);
        if (runtime == null) {
            return;
        }
        listener.tenantDown(code);
        runtime.endpoint().close();
        sweeps.remove(code);
        String oidcPath = authorityContexts.remove(code);
        if (oidcPath != null) {
            sharedServer.removeContext(oidcPath);
        }
        provisioner.release(code);
    }

    /** Background reconciliation on a virtual thread. */
    public synchronized void start(long pollMillis) {
        if (running) {
            return;
        }
        running = true;
        scanner = Thread.ofVirtual().name("dbo-tenant-scanner").start(() -> {
            while (running) {
                try {
                    scanOnce();
                    if (System.currentTimeMillis() - lastSweepMillis > 3_600_000) {
                        lastSweepMillis = System.currentTimeMillis();
                        sweeps.values().forEach(cloud.jengu.dbo.policy.RetentionSweep::sweepOnce);
                    }
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // keep reconciling
                }
            }
        });
    }

    @Override
    public synchronized void close() {
        running = false;
        if (scanner != null) {
            scanner.interrupt();
        }
        for (String code : Set.copyOf(runtimes.keySet())) {
            takeDown(code);
        }
        sharedServer.stop(0);
    }
}
