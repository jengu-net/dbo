package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4Terminology;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.fhir.r5.R5Terminology;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Turns tenant spec files into live tenant service sets:
 * spec appears → provision (via the mandatory {@link TenantDatabaseProvisioner})
 * → personality + engine + facade + feed → FHIR endpoint at
 * {@code /t/<code>/fhir} on ONE shared port (the interim until the routing
 * layer) → listener callback (the OSGi activator registers services there).
 *
 * <p>Spec removal RETRACTS (endpoint down, pool released — data untouched);
 * erasure is only ever the explicit provisioner {@code deprovision}.
 */
public final class TenantRuntimeManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.tenant");

    /** The last reported tenant set, so the rollup reports change rather than time. */
    private volatile String lastRollup;

    /** Bring-up failures already reported, so a permanent one is said once. */
    private final Set<String> reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public record TenantRuntime(
            TenantSpec spec,
            ObjectStore engine,
            FhirStoreFacade store,
            ChangeFeed feed,
            FhirHttpServer endpoint,
            /**
             * This tenant's own grain codec. A stream between two tenants needs
             * BOTH ends' — reassembly reads the source's native form and the
             * destination writes its own — so each runtime carries its own
             * rather than the wiring building one from whichever store is handy.
             */
            cloud.jengu.dbo.core.face.GrainCodec grain) {}

    /**
     * A dependent reached before its upstream. Not a failure: the scan comes
     * round again, and by then the upstream is up. It exists so the log can
     * say that rather than reporting an error that fixes itself.
     */
    static final class UpstreamNotReady extends IllegalStateException {
        UpstreamNotReady(String dependent, String upstream) {
            super("tenant '" + dependent + "' declares a dependency on '" + upstream
                    + "', which is not up yet — waiting for the next scan");
        }
    }

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
    private volatile cloud.jengu.dbo.auth.IdentityHub identityHub;
    private final Map<String, javax.sql.DataSource> tenantDataSources = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.auth.IdentityHub> zoneHubs = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.policy.RetentionSweep> sweeps = new ConcurrentHashMap<>();
    private final Map<String, String> maintenanceContexts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine>> syncEngines =
            new ConcurrentHashMap<>();
    private volatile long lastSweepMillis;
    private volatile Thread scanner;
    private volatile boolean running;

    /**
     * §13 authority wiring: when present, every tenant gets its own OIDC
     * authority at {@code /t/<code>/oidc} and the store surface accepts only
     * that authority's tokens. issuerBase null → derived from the serving
     * address (deployment config, like the REST baseUrl).
     */
    /**
     * §16.2: the upstream broker + subject identifier system are ZONE-scoped
     * values (single-zone deployments pass them here; the zone overlay
     * promotes their source without changing this shape).
     */
    public record AuthorityConfig(byte[] kek, String issuerBase,
            cloud.jengu.dbo.auth.IdentityHub.Upstream upstream, String subjectSystem,
            Map<String, String> brokerSecrets) {
        public AuthorityConfig(byte[] kek, String issuerBase) {
            this(kek, issuerBase, null, null, Map.of());
        }

        public AuthorityConfig(byte[] kek, String issuerBase,
                cloud.jengu.dbo.auth.IdentityHub.Upstream upstream, String subjectSystem) {
            this(kek, issuerBase, upstream, subjectSystem, Map.of());
        }
    }

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
        if (authorityConfig != null && authorityConfig.upstream() != null) {
            String hubBase = authorityConfig.issuerBase() != null
                    ? authorityConfig.issuerBase()
                    : "http://" + host + ":" + port();
            identityHub = new cloud.jengu.dbo.auth.IdentityHub(authorityConfig.upstream(),
                    authorityConfig.subjectSystem(), hubBase, "/hub", 28_800);
            sharedServer.createContext("/hub", identityHub);
        }
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
                                long began = System.nanoTime();
                                bringUp(spec);
                                reportedFailures.removeIf(k -> k.startsWith(f.getFileName() + ":"));
                                LOG.info("tenant up: code={} fhir={} pdi={} in {}ms",
                                        spec.code(), spec.fhirVersion(), spec.pdi(),
                                        (System.nanoTime() - began) / 1_000_000);
                                rollup();
                            }
                        } catch (Throwable e) {
                            // Throwable, not Exception: a missing OSGi wire
                            // arrives as NoClassDefFoundError, and catching
                            // only Exception let it kill the scanner thread
                            // with no output at all — absence of a tenant and
                            // absence of a reason.
                            // Reported once per distinct failure. The scan
                            // retries every couple of seconds, and a spec
                            // that will never parse would otherwise write the
                            // same stack until the disk filled — burying the
                            // one line that mattered.
                            if (e instanceof UpstreamNotReady notReady) {
                                // Expected on the way up, so it is not an error
                                // and does not enter the suppression set: the
                                // next scan is where it resolves.
                                LOG.debug("{}", notReady.getMessage());
                                return;
                            }
                            String signature = f.getFileName() + ":" + e;
                            if (reportedFailures.add(signature)) {
                                LOG.error("tenant bring-up failed: spec={} (further identical "
                                        + "failures suppressed until it changes)",
                                        f.getFileName(), e);
                            }
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
        rollup();
        return codes();
    }

    /**
     * What this box is now, after whatever just changed.
     *
     * <p>A per-tenant line answers "what happened"; this answers "what is
     * true". An operator reading a log two hours later needs the second one,
     * and reconstructing it by replaying every up and down event is how a
     * count goes wrong.
     */
    private void rollup() {
        // Emitted only when the shape actually changed. A scan loop that
        // restates the same counts every two seconds turns the log into a
        // heartbeat, and a heartbeat is what people filter out — including
        // on the run where the number finally moved.
        String shape = runtimes.keySet().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        if (shape.equals(lastRollup)) {
            return;
        }
        lastRollup = shape;
        LOG.info("tenants: serving={} r4={} r5={} pdi={}",
                runtimes.size(),
                runtimes.values().stream().filter(r -> "r4".equals(r.spec().fhirVersion())).count(),
                runtimes.values().stream().filter(r -> "r5".equals(r.spec().fhirVersion())).count(),
                runtimes.values().stream().filter(r -> r.spec().pdi()).count());
    }

    private void bringUp(TenantSpec spec) {
        // Dependencies wire against the upstream's LIVE runtime —
        // like the zone hub, an upstream that isn't up yet fails bring-up
        // loudly and the scan loop retries once it is.
        for (TenantSpec.Dependency dependency : spec.dependencies()) {
            if (!runtimes.containsKey(dependency.name())) {
                throw new IllegalStateException(spec.code() + ": upstream '" + dependency.name()
                        + "' is not up yet — retrying on the next scan");
            }
        }
        TenantDatabaseProvisioner.TenantDatabase db = provisioner.provision(spec);
        tenantDataSources.put(spec.code(), db.dataSource());
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
            cloud.jengu.dbo.auth.IdentityHub hub = spec.zone() != null
                    ? zoneHub(spec) : identityHub;
            if (hub != null) {
                authority.federation(new cloud.jengu.dbo.auth.TenantAuthority.Federation(
                        hub.issuer() + "/authorize", hub::assertionKey, hub.issuer(),
                        spec.broker(), spec.acceptedBrokers()));
            }
            if (db.bootstrapClientSecret() != null) {
                authority.ensureClient("tenant-bootstrap", db.bootstrapClientSecret(),
                        java.util.List.of("system/*.read", "system/*.write"));
            }
            if (db.rpClientSecret() != null) {
                // The relying party's record is ensured FROM custody — id,
                // secret and redirect URIs all — so the Secret and the record
                // cannot drift. Which application it is, the store never knows.
                authority.ensureClient(db.rpClientIdOrDefault(), db.rpClientSecret(),
                        java.util.List.of("user/*.read", "user/*.write"),
                        "confidential", db.rpRedirectUris());
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
            cloud.jengu.dbo.policy.PolicyObjectStore engine = policyWrapped(spec, db, personality.registrations(), R4Personality.DOMAIN);
            if (authority != null) {
                authority.attachSubjects(engine); // §16.1: subjects are the tenant's records
            }
            FhirStoreFacade store = new R4Store(engine, personality, base);
            // REQ-DBO-TERM-EVERY-TENANT-ANSWERS: the native form is per tenant,
            // so the facade is built here rather than shared — a tenant answers
            // $expand from its own concepts or it is a second-class reader.
            R4Terminology terminology = new R4Terminology(engine, personality,
                    new cloud.jengu.dbo.terminology.TerminologyStore(db.dataSource()));
            runtime = new TenantRuntime(spec, engine, store,
                    new PgChangeFeed(db.dataSource(), R4Personality.DOMAIN),
                    withAuditSurface(withPolicyNote(new FhirHttpServer(sharedServer, store,
                            terminology, "/t/" + spec.code() + "/fhir", guard), spec), spec, engine),
                    terminology);
        } else {
            R5Personality personality = new R5Personality(spec.types());
            cloud.jengu.dbo.policy.PolicyObjectStore engine = policyWrapped(spec, db, personality.registrations(), R5Personality.DOMAIN);
            if (authority != null) {
                authority.attachSubjects(engine);
            }
            FhirStoreFacade store = new R5Store(engine, personality, base);
            R5Terminology terminology = new R5Terminology(engine, personality,
                    new cloud.jengu.dbo.terminology.TerminologyStore(db.dataSource()));
            runtime = new TenantRuntime(spec, engine, store,
                    new PgChangeFeed(db.dataSource(), R5Personality.DOMAIN),
                    withAuditSurface(withPolicyNote(new FhirHttpServer(sharedServer, store,
                            terminology, "/t/" + spec.code() + "/fhir", guard), spec), spec, engine),
                    terminology);
        }
        // The maintenance surface, when the tenant has an authority to guard
        // it: backups are system-plane, and a tenant with no authority has no
        // way to say who is asking.
        if (authority != null) {
            String adminPath = "/t/" + spec.code() + "/admin";
            String domain = "r4".equals(spec.fhirVersion())
                    ? R4Personality.DOMAIN : R5Personality.DOMAIN;
            boolean r4 = "r4".equals(spec.fhirVersion());
            R4Personality r4Face = r4 ? new R4Personality(spec.types()) : null;
            R5Personality r5Face = r4 ? null : new R5Personality(spec.types());
            sharedServer.createContext(adminPath, new MaintenanceHandler(authority,
                    db.dataSource(), domain,
                    r4 ? r4Face.registrations() : r5Face.registrations(),
                    r4 ? r4Face.portableRendering() : r5Face.portableRendering(),
                    adminPath,
                    new AuditedImportLedger(
                            (cloud.jengu.dbo.policy.PolicyObjectStore) runtime.engine())));
            maintenanceContexts.put(spec.code(), adminPath);
        }
        runtimes.put(spec.code(), runtime);
        wireDependencies(spec, runtime, db);
        listener.tenantUp(runtime);
    }

    /**
     * REQ-DBO-SYNC-SPEC-DECLARED: one stream engine per declared
     * dependency, upstream feed → this tenant's store. The consumer id
     * carries the DEPENDENT's code — the ack cursor lives in the upstream's
     * feed, and two dependents of the same upstream must not share one. A
     * fresh consumer starts at the feed's beginning, so a newly declared
     * dependency catches up from full history
     * (REQ-DBO-SYNC-FULL-HISTORY-CATCH-UP).
     */
    private void wireDependencies(TenantSpec spec, TenantRuntime runtime,
            TenantDatabaseProvisioner.TenantDatabase db) {
        if (spec.dependencies().isEmpty()) {
            return;
        }
        boolean r4 = "r4".equals(spec.fhirVersion());
        String domain = r4 ? R4Personality.DOMAIN : R5Personality.DOMAIN;
        String payloadVersion = r4 ? "4.0" : "5.0";
        java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine> engines = new java.util.ArrayList<>();
        for (TenantSpec.Dependency dependency : spec.dependencies()) {
            TenantRuntime upstream = runtimes.get(dependency.name());
            if (upstream == null) {
                // Bring-up order comes from Files.list, so a dependent can be
                // reached before the tenant it streams from. That is normal and
                // temporary — the scan retries — but the null used to travel to
                // upstream.feed() and arrive as a NullPointerException naming a
                // local variable. Say which tenant is waiting for which.
                throw new UpstreamNotReady(spec.code(), dependency.name());
            }
            engines.add(new cloud.jengu.dbo.sync.ContentSyncEngine(
                    new cloud.jengu.dbo.sync.ContentDependency(
                            dependency.name(), dependency.types()),
                    upstream.feed(), runtime.engine(), db.dataSource(),
                    domain, payloadVersion,
                    java.util.List.of(new cloud.jengu.dbo.fhir.r5.R4ToR5Converter()),
                    "sync." + dependency.name() + "." + spec.code(),
                    // the upstream reassembles from ITS concepts; this tenant
                    // takes the result apart into its own
                    upstream.grain(), runtime.grain()));
        }
        syncEngines.put(spec.code(), java.util.List.copyOf(engines));
    }

    /**
     * One sync round over every wired stream (declared changes applied,
     * parked shadows re-attempted). The scan loop calls this continuously;
     * tests call it for determinism. Returns events seen.
     */
    public int syncRound() {
        int seen = 0;
        for (java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine> engines : syncEngines.values()) {
            for (cloud.jengu.dbo.sync.ContentSyncEngine engine : engines) {
                try {
                    int events;
                    do {
                        events = engine.syncOnce(500);
                        seen += events;
                    } while (events > 0);
                    engine.reconcile();
                } catch (RuntimeException e) {
                    // one stream's failure never blocks the others; the
                    // next round retries from the acked cursor
                    LOG.warn("sync round failed for one stream; retrying from the "
                            + "acked cursor next round", e);
                }
            }
        }
        return seen;
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
    private cloud.jengu.dbo.policy.PolicyObjectStore policyWrapped(TenantSpec spec,
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

    /**
     * §17: the zone's declarations are records in the ZONE tenant. The zone
     * must be up first — a dependent arriving earlier fails bring-up loudly
     * and the scan loop retries. One hub per zone, brokers from records,
     * secrets from custody by broker code.
     */
    private cloud.jengu.dbo.auth.IdentityHub zoneHub(TenantSpec spec) {
        return zoneHubs.computeIfAbsent(spec.zone(), zone -> {
            javax.sql.DataSource zoneDs = tenantDataSources.get(zone);
            if (zoneDs == null) {
                throw new IllegalStateException(spec.code() + ": zone '" + zone
                        + "' is not up yet — retrying on the next scan");
            }
            cloud.jengu.dbo.core.api.ObjectStore zoneStore =
                    new PgObjectStore(zoneDs, cloud.jengu.dbo.auth.ZoneModel.registrations());
            java.util.Map<String, cloud.jengu.dbo.auth.IdentityHub.Upstream> upstreams =
                    new java.util.LinkedHashMap<>();
            String defaultBroker = null;
            for (cloud.jengu.dbo.core.api.StoredObject record : zoneStore.select(
                    cloud.jengu.dbo.core.api.Criteria.of("ZoneBroker"))) {
                cloud.jengu.dbo.auth.ZoneModel.Broker broker =
                        cloud.jengu.dbo.auth.ZoneModel.Broker.parse(record.payload());
                String secret = authorityConfig.brokerSecrets().get(broker.code());
                if (secret == null) {
                    throw new IllegalStateException("zone '" + zone + "' broker '"
                            + broker.code() + "': no secret in custody");
                }
                upstreams.put(broker.code(), new cloud.jengu.dbo.auth.IdentityHub.Upstream(
                        broker.issuer(), broker.clientId(), secret, broker.subjectStripPrefix()));
                if (defaultBroker == null) {
                    defaultBroker = broker.code();
                }
            }
            if (upstreams.isEmpty()) {
                throw new IllegalStateException("zone '" + zone + "' declares no brokers");
            }
            String subjectSystem = zoneSubjectSystem(zoneStore);
            String hubBase = authorityConfig.issuerBase() != null
                    ? authorityConfig.issuerBase() : "http://" + host + ":" + port();
            String path = "/z/" + zone + "/hub";
            cloud.jengu.dbo.auth.IdentityHub hub = new cloud.jengu.dbo.auth.IdentityHub(
                    upstreams, defaultBroker, subjectSystem, hubBase, path, 28_800);
            sharedServer.createContext(path, hub);
            return hub;
        });
    }

    /** §17.1: the subject-resolution system comes from the zone's declared domains. */
    private String zoneSubjectSystem(cloud.jengu.dbo.core.api.ObjectStore zoneStore) {
        return zoneStore.getByIdentifier("ZoneIdentifierDomain",
                        java.util.List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.auth.ZoneModel.IDENTIFIER_USE_SYSTEM,
                                cloud.jengu.dbo.auth.ZoneModel.USE_PERSON_PRIMARY))).stream()
                .findFirst()
                .map(record -> {
                    String payload = new String(record.payload(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    return payload.replaceAll(".*\"system\":\"([^\"]+)\".*", "$1");
                })
                .orElse(authorityConfig.subjectSystem());
    }

    private void takeDown(String code) {
        TenantRuntime runtime = runtimes.remove(code);
        if (runtime == null) {
            return;
        }
        // Retraction, not erasure: the tenant stops being served and its
        // database stays. Deprovision is the destructive one and says so
        // separately.
        LOG.info("tenant down: code={} reason=undeclared", code);
        listener.tenantDown(code);
        runtime.endpoint().close();
        sweeps.remove(code);
        syncEngines.remove(code);
        String adminPath = maintenanceContexts.remove(code);
        if (adminPath != null) {
            sharedServer.removeContext(adminPath);
        }
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
                    syncRound();
                    if (System.currentTimeMillis() - lastSweepMillis > 3_600_000) {
                        lastSweepMillis = System.currentTimeMillis();
                        sweeps.values().forEach(cloud.jengu.dbo.policy.RetentionSweep::sweepOnce);
                    }
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    // the reconciler must outlive any single round's
                    // failure — including Errors (see bring-up above)
                    LOG.warn("reconciliation round failed; the reconciler continues", e);
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
