package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTerminology;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
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
import java.util.List;
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

    /**
     * What this runtime is doing about each tenant it has been told about
     * (#67) — kept as the scan goes rather than derived afterwards.
     *
     * <p>Derived afterwards, it would have to re-read the spec directory, and
     * the answer would agree with the configuration by construction: the
     * caller asking is comparing this against that directory, so an answer
     * taken from it is a report that can only ever say "no drift".
     */
    private final Map<String, TenantState.State> states = new ConcurrentHashMap<>();

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

    /**
     * The versions this container can serve. A tenant's declared version is
     * resolved here (R6) — the wiring no longer knows which ones exist, so a
     * new one is a bundle rather than another branch.
     */
    private final FhirVersions versions;
    private final Map<String, String> authorityContexts = new ConcurrentHashMap<>();
    private volatile cloud.jengu.dbo.auth.IdentityHub identityHub;
    private final Map<String, javax.sql.DataSource> tenantDataSources = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.auth.IdentityHub> zoneHubs = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.policy.RetentionSweep> sweeps = new ConcurrentHashMap<>();
    /**
     * The tenants' authorities, kept so the sweep can reach them: a retired
     * signing key has to be REMOVED eventually, or it goes on verifying and
     * the rotation was only cosmetic (#122).
     */
    private final Map<String, cloud.jengu.dbo.auth.TenantAuthority> authorities =
            new ConcurrentHashMap<>();
    private final Map<String, String> maintenanceContexts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine>> syncEngines =
            new ConcurrentHashMap<>();
    /** Where a tenant's runs are written: the engine, under the policy decorator. */
    private final Map<String, ObjectStore> runStores = new ConcurrentHashMap<>();
    /** What went wrong for a tenant that is not serving, and whose problem it is. */
    private final Map<String, Trouble> trouble = new ConcurrentHashMap<>();
    /** The tenant this deployment's own history lives in (#74, ADR 0061). */
    private volatile String managementCode;

    /** A tenant that is not serving, and why — the reason a card has to carry. */
    private record Trouble(cloud.jengu.dbo.work.Failure failure, String reason) {}
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
        this(directory, provisioner, host, port, listener, authorityConfig,
                FhirVersions.installed());
    }

    /**
     * @param versions what this container can serve. The container passes the
     *                 service registry's view, which changes as face bundles
     *                 come and go; the constructors above ask the classpath,
     *                 which is what a test or a single-jar assembly has.
     */
    public TenantRuntimeManager(Path directory, TenantDatabaseProvisioner provisioner,
            String host, int port, Listener listener, AuthorityConfig authorityConfig,
            FhirVersions versions) {
        this.versions = versions;
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
            // "Address already in use" with no address in it is the least
            // useful sentence a bring-up can end on: the whole runtime fails to
            // start, and what a reader needs is which port and who is likely
            // holding it — commonly a second dbo on the same box.
            throw new UncheckedIOException(host + ":" + port + " could not be bound, so this "
                    + "runtime serves nothing. Something else is on that port — another dbo, "
                    + "or an application embedding one.", e);
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

    /**
     * Brings up the tenant that records what this deployment does about the
     * others (#74, ADR 0061).
     *
     * <p>The managing party is <b>a tenant like the others</b> — the juridical
     * body operating the deployment, distinguished by role rather than by
     * position — so management history is ordinary records in an ordinary
     * store, inheriting authority, roles, audit, export, feeds and erasure from
     * machinery that already exists. No plane, no store outside every tenant,
     * no bespoke retention rule.
     *
     * <p>Declared by deployment configuration rather than by a file in the
     * watched directory, so <b>the scan loop that retracts tenants cannot
     * retract the thing recording retractions</b>.
     *
     * <p>A deployment whose management tenant will not come up serves nothing:
     * this throws, and that is the one failure with nowhere to be recorded —
     * it belongs in the log and the exit code.
     *
     * <p>Single-tenant deployments are not a special mode. The management
     * tenant is the tenant, and the serving sweep has one item.
     */
    public synchronized String manages(Path specFile) {
        TenantSpec spec;
        try {
            spec = TenantSpec.parse(Files.readString(specFile));
        } catch (IOException e) {
            throw new UncheckedIOException("the management tenant's spec cannot be read: "
                    + specFile, e);
        }
        if (!runtimes.containsKey(spec.code())) {
            bringUp(spec);
            states.put(spec.code(), TenantState.State.SERVING);
            LOG.info("management tenant up: code={}", spec.code());
        }
        managementCode = spec.code();
        return spec.code();
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
                            states.putIfAbsent(spec.code(), TenantState.State.COMING_UP);
                            trouble.remove(spec.code());
                            trouble.remove("spec:" + f.getFileName());
                            if (!runtimes.containsKey(spec.code())) {
                                long began = System.nanoTime();
                                bringUp(spec);
                                states.put(spec.code(), TenantState.State.SERVING);
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
                            // The code is known when the spec parsed, which is
                            // the case an operator asks about: a tenant that was
                            // declared and did not come up. A file that never
                            // parsed has no tenant to be a state of.
                            codeOf(f).ifPresent(code -> states.put(code,
                                    e instanceof UpstreamNotReady
                                            ? TenantState.State.COMING_UP
                                            : TenantState.State.FAILED));
                            // What an operator has to be told, kept where the
                            // sweep can find it: a spec that will never parse
                            // has no tenant to be a state of, so it is named by
                            // the file it is — which is what somebody has to
                            // open to fix it.
                            trouble.put(codeOf(f).orElse("spec:" + f.getFileName()),
                                    new Trouble(e instanceof UpstreamNotReady
                                            ? cloud.jengu.dbo.work.Failure.TRANSIENT
                                            : cloud.jengu.dbo.work.Failure.of(e),
                                            String.valueOf(e)));
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
            // The management tenant is declared by configuration and is not in
            // this directory, so the loop that retracts undeclared tenants
            // would retract the thing recording retractions.
            if (!declared.contains(code) && !code.equals(managementCode)) {
                takeDown(code, "the declaration was withdrawn");
            }
        }
        // A tenant nobody declares any more is not a tenant this runtime has a
        // state about: retraction is not a failure, and reporting it as one
        // would make every removal look like a fault.
        if (managementCode != null) {
            declared.add(managementCode);
        }
        states.keySet().retainAll(declared);
        trouble.keySet().removeIf(key -> !declared.contains(key) && !key.startsWith("spec:"));
        rollup();
        recordServing();
        return codes();
    }

    /**
     * Serves the runtime's own state at {@code /runtime/tenants} (#67).
     *
     * <p>Registered only when a token is configured, so a deployment that has
     * not decided who may ask does not have a surface to be asked through —
     * the same posture as the tenant endpoints, which refuse without an
     * authority rather than serving openly.
     *
     * <p>Cross-tenant on purpose, and therefore <b>not</b> a tenant's to reach:
     * the codes this answers with are other tenants' existence, which no tenant
     * credential may buy. It is a deployment-level answer to a
     * deployment-level question, and the token is the deployment's.
     */
    public void serveRuntimeState(String opsToken) {
        if (opsToken == null || opsToken.isBlank()) {
            return;
        }
        byte[] expected = opsToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sharedServer.createContext("/runtime/tenants", exchange -> {
            try {
                String presented = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] offered = presented == null || !presented.startsWith("Bearer ")
                        ? new byte[0]
                        : presented.substring(7).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (!java.security.MessageDigest.isEqual(expected, offered)) {
                    respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "{\"error\":\"invalid_request\"}");
                    return;
                }
                StringBuilder json = new StringBuilder("{\"tenants\":[");
                boolean first = true;
                for (TenantState state : tenantStates()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append("{\"code\":\"").append(state.code())
                            .append("\",\"state\":\"").append(state.state().wire()).append("\"}");
                }
                respond(exchange, 200, json.append("]}").toString());
            } finally {
                exchange.close();
            }
        });
        LOG.info("runtime state: serving /runtime/tenants");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** The tenant a spec file declares, when it parses — for a state to belong to. */
    private Optional<String> codeOf(Path spec) {
        try {
            return Optional.of(TenantSpec.parse(Files.readString(spec)).code());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * What this runtime is doing about each tenant it has been told about
     * (#67).
     *
     * <p>Serving, coming up and failed are different facts, and the third is
     * the one an operator most wants: a list that silently omitted a tenant
     * that failed would answer "which tenants are fine" while looking like it
     * answered "which tenants exist".
     *
     * <p>From runtime state, never from re-reading the spec directory. The
     * caller is comparing this answer against that directory, so an answer
     * taken from it agrees by construction and can only ever say "no drift".
     */
    public List<TenantState> tenantStates() {
        List<TenantState> out = new java.util.ArrayList<>();
        states.forEach((code, state) -> out.add(new TenantState(code,
                runtimes.containsKey(code) ? TenantState.State.SERVING : state)));
        out.sort(java.util.Comparator.comparing(TenantState::code));
        return List.copyOf(out);
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
        // Counted by whatever versions are actually being served: naming two of
        // them here would go quietly wrong the day a third is installed, and a
        // rollup that under-reports is worse than one that says nothing.
        String byVersion = runtimes.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.spec().fhirVersion(), java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(" "));
        LOG.info("tenants: serving={} {} pdi={}",
                runtimes.size(),
                byVersion.isEmpty() ? "versions=none" : byVersion,
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
        // Resolved before anything is created. A tenant declaring a version
        // nothing provides is refused because nothing provides it, and asking
        // first means the refusal leaves no database behind to clean up.
        FhirVersion version = versions.require(spec.fhirVersion());
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
        if (authority != null) {
            authorities.put(spec.code(), authority);
        }
        // Built once and shared by everything this bring-up wires. It used to
        // be built twice — the maintenance surface constructed a second one —
        // which is how a tenant came to cost two of the heaviest object here.
        // The face's own vocabulary is served BY the tenant, so the types
        // that carry it are registered whether or not the tenant listed them:
        // a client meeting urn:dbo:run:holder must be able to fetch what
        // defines it, and "the tenant did not ask for CodeSystem" is not an
        // answer a consumer can act on (#91).
        FhirVersion.ForTypes declared = version.forTypes(withVocabularyTypes(spec.types(),
                version.face()));
        cloud.jengu.dbo.policy.PolicyObjectStore engine =
                policyWrapped(spec, db, declared.registrations(), version.domain(),
                        version.face());
        if (authority != null) {
            authority.attachSubjects(engine); // §16.1: subjects are the tenant's records
        }
        // With the tenant's database: a face that validates against current
        // data — the tenant's terminology, and in time its own structure
        // definitions — needs to know where that data lives (#50).
        // Bring-up says what it cost, in the plain log. A first boot on a
        // fresh database does work a later one does not — importing the
        // terminology baseline, publishing the face's vocabulary, reading the
        // tenant's profiles — and the budget test measures the SECOND boot on
        // purpose, so none of this was visible in a number anybody read. It
        // stopped being invisible the day it quadrupled under memory pressure
        // and surfaced as a closed connection pool (#93).
        long facadeAt = System.currentTimeMillis();
        FhirStoreFacade store = declared.store(engine, base, db.dataSource());
        long facadeMillis = System.currentTimeMillis() - facadeAt;

        // REQ-DBO-TERM-EVERY-TENANT-ANSWERS: the native form is per tenant,
        // so the facade is built here rather than shared — a tenant answers
        // $expand from its own concepts or it is a second-class reader.
        FhirTerminology terminology = declared.terminology(engine, db.dataSource());
        long vocabularyAt = System.currentTimeMillis();
        publishVocabularies(engine, store, terminology, version.face());
        LOG.info("tenant bring-up cost: code={} facade={}ms vocabulary={}ms",
                spec.code(), facadeMillis, System.currentTimeMillis() - vocabularyAt);
        TenantRuntime runtime = new TenantRuntime(spec, engine, store,
                new PgChangeFeed(db.dataSource(), version.domain()),
                withAuditSurface(withPolicyNote(new FhirHttpServer(sharedServer, store,
                        terminology, "/t/" + spec.code() + "/fhir", guard), spec),
                        version.face(), engine),
                terminology);
        // The maintenance surface, when the tenant has an authority to guard
        // it: backups are system-plane, and a tenant with no authority has no
        // way to say who is asking.
        if (authority != null) {
            String adminPath = "/t/" + spec.code() + "/admin";
            sharedServer.createContext(adminPath, new MaintenanceHandler(authority,
                    db.dataSource(), version.domain(),
                    declared.registrations(),
                    declared.portableRendering(),
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
        FhirVersion version = versions.require(spec.fhirVersion());
        String domain = version.domain();
        String payloadVersion = version.payloadVersion();
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
            engines.add(withRuns(spec, new cloud.jengu.dbo.sync.ContentSyncEngine(
                    new cloud.jengu.dbo.sync.ContentDependency(
                            dependency.name(), dependency.types()),
                    upstream.feed(), runtime.engine(), db.dataSource(),
                    domain, payloadVersion,
                    java.util.List.of(new cloud.jengu.dbo.fhir.r5.R4ToR5Converter()),
                    "sync." + dependency.name() + "." + spec.code(),
                    // the upstream reassembles from ITS concepts; this tenant
                    // takes the result apart into its own
                    upstream.grain(), runtime.grain())));
        }
        syncEngines.put(spec.code(), java.util.List.copyOf(engines));
    }

    /**
     * The stream, recording what it does in the dependent tenant's own store
     * (#73) — so a parked shadow is a card somebody can see rather than a row
     * in a table nothing reads.
     */
    private cloud.jengu.dbo.sync.ContentSyncEngine withRuns(TenantSpec spec,
            cloud.jengu.dbo.sync.ContentSyncEngine engine) {
        ObjectStore store = runStores.get(spec.code());
        return store == null ? engine : engine.withRuns(new cloud.jengu.dbo.work.Runs(store));
    }

    /**
     * The wired streams of one tenant — what it is replicating and what has
     * parked. Ops asks this to see a shadow somebody has to clear; tests ask it
     * to assert that nothing parked at all.
     */
    public java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine> streamsOf(String tenantCode) {
        return syncEngines.getOrDefault(tenantCode, java.util.List.of());
    }

    /**
     * Remove signing keys retired longer ago than any token they signed can
     * still be live (#122).
     *
     * <p>Rotation leaves the old key published so nothing signed a moment
     * before it breaks. That is correct and it is only half: a key that is
     * never removed verifies forever, so a leaked one is never actually
     * retired. This is the other half, on the same hourly beat as the
     * retention sweeps because it is the same kind of work.
     *
     * @return how many keys were removed across all tenants
     */
    public int pruneSigningKeys() {
        int removed = 0;
        for (Map.Entry<String, cloud.jengu.dbo.auth.TenantAuthority> tenant
                : authorities.entrySet()) {
            try {
                removed += tenant.getValue().pruneRetiredKeys();
            } catch (RuntimeException e) {
                // one tenant's failure never stops the others, and the next
                // hour tries again from the same state
                LOG.warn("could not prune retired signing keys for tenant {}",
                        tenant.getKey(), e);
            }
        }
        return removed;
    }

    /**
     * One round of watching each tenant's own change feed for profiles that
     * arrived without this facade's knowledge (#87, point 2).
     *
     * <p>A write THROUGH the facade rebuilds that tenant's validation view at
     * the write, and always did. A profile that reaches the engine another way
     * — replicated from a zone, restored from an archive, written by a lane —
     * left the view holding the shapes it had at bring-up, so a tenant could be
     * given a profile and go on validating as though it had not been.
     *
     * <p>The tenant's own feed is the answer because it records every write
     * whatever made it: watching the store rather than the writers is what
     * makes this cover the paths nobody has thought of yet, including the ones
     * added later.
     *
     * <p>One durable consumer per tenant, so an interrupted round resumes
     * rather than replaying, and a rebuild is driven by the event rather than
     * by a clock. Reading the tenant's own feed here does not disturb any other
     * consumer: cursors are per consumer name.
     *
     * <p>The first round for a tenant reads its feed from the beginning, which
     * costs one pass over its history and usually one redundant rebuild. The
     * cheaper alternative — start the consumer at the head, since bring-up
     * already read the profiles out of the store — has a gap in it: a profile
     * written between that read and the consumer's first round would be behind
     * the head and never seen. A one-time pass is the price of not having a
     * window where a profile can be missed permanently.
     *
     * @return how many tenants rebuilt their view this round
     */
    public int shapesRound() {
        int rebuilt = 0;
        for (TenantRuntime runtime : runtimes.values()) {
            String consumer = "shapes." + runtime.spec().code();
            try {
                boolean moved = false;
                cloud.jengu.dbo.core.api.feed.FeedChunk<
                        cloud.jengu.dbo.core.api.feed.FeedItem> chunk;
                // Drained rather than sampled: the interesting item is not
                // necessarily in the first chunk of a busy tenant, and leaving
                // it behind would defer the rebuild by a round each time.
                while (!(chunk = runtime.feed().readFor(consumer, 500)).items().isEmpty()) {
                    for (cloud.jengu.dbo.core.api.feed.FeedItem item : chunk.items()) {
                        moved |= "StructureDefinition".equals(item.typeName());
                    }
                    runtime.feed().ack(consumer, chunk.nextCursor());
                }
                if (moved) {
                    // Acked before rebuilding, deliberately: a rebuild that
                    // throws is this tenant's broken profile and is reported as
                    // that, not a reason to re-read the same events forever.
                    runtime.store().shapesChanged();
                    rebuilt++;
                    LOG.info("tenant {} rebuilt its validation view: a profile arrived "
                            + "without going through its facade", runtime.spec().code());
                }
            } catch (RuntimeException e) {
                // One tenant's broken profile never stops the others, and the
                // next round retries from the acked cursor.
                LOG.warn("could not refresh validation shapes for tenant {}",
                        runtime.spec().code(), e);
            }
        }
        return rebuilt;
    }

    /**
     * One sync round over every wired stream (declared changes applied,
     * parked shadows re-attempted, and what is left recorded). The scan loop
     * calls this continuously; tests call it for determinism. Returns events
     * seen.
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
                    // one recorded round: re-attempt what is parked and say
                    // what is left, which is the part a person can act on
                    engine.pass(500);
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
            java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> registrations,
            cloud.jengu.dbo.core.face.DomainFace face) {
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
        // The face's own coarsening, not the absent one. The engine declares
        // THAT an element is generalised; only a face knows a birth date
        // reduces to its year. Built without it, every GENERALISE element
        // silently became a REMOVE — a tenant that asked for a coarse birth
        // date got none at all, and the capability was published all along
        // (#114).
        return new cloud.jengu.dbo.pdi.PdiObjectStore(inner,
                new cloud.jengu.dbo.pdi.PersonVault(db.dataSource(), authorityConfig.kek()),
                pdiSpec,
                face.capability(cloud.jengu.dbo.core.face.Coarsening.class)
                        .orElse(cloud.jengu.dbo.core.face.Coarsening.NONE));
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
            String domain, cloud.jengu.dbo.core.face.DomainFace face) {
        java.util.List<cloud.jengu.dbo.core.api.TypeRegistration> all =
                new java.util.ArrayList<>(registrations);
        all.addAll(cloud.jengu.dbo.policy.AuditModel.registrations());
        // Runs join them for the same reason audit entries do: what this
        // deployment did about a tenant belongs in that tenant's own store,
        // queryable and versioned and dropped with it (#46).
        all.addAll(cloud.jengu.dbo.work.WorkModel.registrations());
        ObjectStore engine = pdiWrapped(spec, db, all, face);
        // Runs go to the engine rather than through the policy decorator, and
        // everything that records them for this tenant uses the same one.
        runStores.put(spec.code(), engine);
        cloud.jengu.dbo.policy.PolicyObjectStore policyStore =
                new cloud.jengu.dbo.policy.PolicyObjectStore(engine, spec.policies());
        if (!spec.policies().retention().isEmpty()) {
            cloud.jengu.dbo.policy.RetentionSweep sweep = new cloud.jengu.dbo.policy.RetentionSweep(
                    db.dataSource(), domain, spec.policies(), policyStore,
                    // an audit entry per checkpoint would record the runtime
                    // interacting with its own bookkeeping, doubling the writes
                    // to say nothing about a caller
                    new cloud.jengu.dbo.work.Runs(engine));
            sweep.sweepOnce();
            sweeps.put(spec.code(), sweep);
        }
        return policyStore;
    }

    private static FhirHttpServer withPolicyNote(FhirHttpServer server, TenantSpec spec) {
        server.policyNote = spec.policies().describe();
        return server;
    }

    private static FhirHttpServer withAuditSurface(FhirHttpServer server,
            cloud.jengu.dbo.core.face.DomainFace face, ObjectStore engine) {
        if (engine instanceof cloud.jengu.dbo.policy.PolicyObjectStore policyStore) {
            // the face renders it; the surface only decides which records
            // answer the query and what a posted document records
            server.auditSurface = new cloud.jengu.dbo.rest.AuditProjection(
                    policyStore, policyStore, face);
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

    private void takeDown(String code, String because) {
        TenantRuntime runtime = runtimes.remove(code);
        // Goes with the runtime: a tenant that is not served has no keys for
        // the sweep to prune, and holding its authority would keep the whole
        // object alive for a tenant nobody can reach (#122).
        authorities.remove(code);
        if (runtime == null) {
            return;
        }
        // Retraction, not erasure: the tenant stops being served and its
        // database stays. Deprovision is the destructive one and says so
        // separately.
        LOG.info("tenant down: code={} reason={}", code,
                because == null ? "shutdown" : because);
        if (because != null) {
            // Shutdown is not a retraction: the deployment stopping is not a
            // decision about any tenant, and recording one per tenant every
            // time the process ends would bury the retractions that were.
            recordRetraction(code, because);
        }
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

    // --------------------------------------------------- management history

    /** Tenant management as a process: observe, serve, retract, erase (#74). */
    public static final String TENANT_PROCESS = "dbo.tenant.serving";

    /** The sweep: what this deployment is doing about the tenants it was told about. */
    public static final String SERVE_STEP = "serve";

    /** Stopping serving. The tenant's data is untouched. */
    public static final String RETRACT_STEP = "retract";

    /** Removing the data. An operator act, and never a sweep's. */
    public static final String ERASE_STEP = "erase";

    /**
     * Where this deployment's own history goes, or empty when nobody is
     * managing — a mechanic with no management tenant records nothing rather
     * than inventing somewhere to write.
     */
    private Optional<cloud.jengu.dbo.work.Runs> managementRuns() {
        ObjectStore store = managementCode == null ? null : runStores.get(managementCode);
        return Optional.ofNullable(store).map(cloud.jengu.dbo.work.Runs::new);
    }

    /**
     * The serving sweep: one item per tenant that is not serving, in the
     * management tenant's own store (#74).
     *
     * <p>Written from {@link #tenantStates()} rather than from a second walk of
     * the directory — the three states are the same reasoning, and reasoning
     * that arrives twice disagrees with itself eventually. {@code
     * /runtime/tenants} stays deployment-level and keeps answering with the
     * management tenant down; this is the same answer, recorded.
     */
    private void recordServing() {
        managementRuns().ifPresent(runs -> {
            try {
                // Over the work domain and nothing a face claims: what this
                // deployment does about tenants is not clinical work, and
                // rendering it beside clinical work is how it would read as
                // some.
                cloud.jengu.dbo.work.Run sweep = runs.sweep(TENANT_PROCESS, SERVE_STEP,
                        "deployment",
                        java.util.List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN));
                cloud.jengu.dbo.work.Runs.Pass pass = runs.pass(sweep);
                long serving = 0;
                long comingUp = 0;
                long failed = 0;
                for (TenantState state : tenantStates()) {
                    switch (state.state()) {
                        case SERVING -> serving++;
                        case COMING_UP -> comingUp++;
                        case FAILED -> failed++;
                        default -> { }
                    }
                }
                for (Map.Entry<String, Trouble> entry : trouble.entrySet()) {
                    pass.item(entry.getKey(), entry.getValue().failure(),
                            entry.getValue().reason());
                }
                // A file that never parsed has no tenant to be a state of, and
                // is counted where it can be seen rather than nowhere.
                failed += trouble.keySet().stream().filter(key -> key.startsWith("spec:")).count();
                pass.counted("serving", serving)
                        .counted("coming_up", comingUp)
                        .counted("failed", failed)
                        .done();
            } catch (RuntimeException e) {
                // The deployment keeps serving tenants when its own bookkeeping
                // cannot be written: management history is a record of the
                // work, not a condition of it.
                LOG.warn("the serving sweep could not be recorded; tenants are unaffected", e);
            }
        });
    }

    /**
     * A retraction, recorded with who did it — or, when nobody did, with what
     * happened instead (#74).
     *
     * <p>"Who retracted that tenant?" had no answer, because the actor was a
     * process reading a directory. It still is, sometimes, and that is now
     * something the record says rather than something it omits.
     */
    private void recordRetraction(String code, String because) {
        managementRuns().ifPresent(runs -> {
            try {
                cloud.jengu.dbo.work.Run retraction = runs.pipeline(TENANT_PROCESS, RETRACT_STEP,
                        TENANT_PROCESS + "/" + RETRACT_STEP + "/" + code + "/"
                                + java.time.Instant.now(),
                        java.util.List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN));
                // the recorded run, not the one before it: closing the stale
                // handle would write its state back over what was just said
                runs.closed(runs.selected(retraction,
                        cloud.jengu.dbo.work.Scope.organisation(code), actor(), because));
            } catch (RuntimeException e) {
                LOG.warn("a retraction could not be recorded; the tenant is retracted", e);
            }
        });
    }

    /**
     * Whoever is acting, as an executor names itself: a person with a
     * credential in the management tenant, or the scan loop when nobody asked.
     */
    private static cloud.jengu.dbo.work.Executor actor() {
        return new cloud.jengu.dbo.work.Executor(
                cloud.jengu.dbo.core.api.Caller.current(), "1", "cloud.jengu.dbo.tenant",
                cloud.jengu.dbo.work.Scope.BASELINE);
    }

    /**
     * Erasure: the tenant's data is removed (#74).
     *
     * <p>An <b>operator act</b>, and deliberately not reachable from the scan
     * path — nothing in reconciliation calls this, and a spec disappearing
     * retracts serving and touches no data. The two are separate steps because
     * they carry different authority, and one of them cannot be undone.
     */
    public synchronized void erase(String code) {
        if (code.equals(managementCode)) {
            throw new IllegalArgumentException(
                    "the management tenant holds the record of every erasure, and erasing it "
                            + "would erase the account of what was erased");
        }
        takeDown(code, "erased by " + cloud.jengu.dbo.core.api.Caller.current());
        managementRuns().ifPresent(runs -> {
            cloud.jengu.dbo.work.Run erasure = runs.pipeline(TENANT_PROCESS, ERASE_STEP,
                    TENANT_PROCESS + "/" + ERASE_STEP + "/" + code + "/" + java.time.Instant.now(),
                    java.util.List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN));
            runs.closed(runs.selected(erasure, cloud.jengu.dbo.work.Scope.organisation(code),
                    actor(), "erased on request"));
        });
        provisioner.deprovision(code);
        states.remove(code);
        trouble.remove(code);
        LOG.info("tenant erased: code={} by={}", code, cloud.jengu.dbo.core.api.Caller.current());
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
                    // AFTER the sync round: a replicated profile arrives in
                    // that round, and watching before it would leave the
                    // rebuild a full poll interval behind its own cause.
                    shapesRound();
                    if (System.currentTimeMillis() - lastSweepMillis > 3_600_000) {
                        lastSweepMillis = System.currentTimeMillis();
                        sweeps.values().forEach(cloud.jengu.dbo.policy.RetentionSweep::sweepOnce);
                        // On the same hourly beat: a key retired longer ago
                        // than any token it signed can still be live is gone.
                        // Rotation without this is not rotation (#122).
                        pruneSigningKeys();
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
            takeDown(code, null);
        }
        sharedServer.stop(0);
    }

    /**
     * The tenant's declared types, plus the ones the face's vocabulary needs
     * to be fetchable — added only when absent, and canonical because that is
     * what a definition is identified by.
     */
    private static java.util.List<cloud.jengu.dbo.fhir.common.FhirTypeConfig> withVocabularyTypes(
            java.util.List<cloud.jengu.dbo.fhir.common.FhirTypeConfig> declared,
            cloud.jengu.dbo.core.face.DomainFace face) {
        java.util.Set<String> carried = new java.util.LinkedHashSet<>();
        for (String definition : face.capability(
                cloud.jengu.dbo.core.face.RecordProjection.class)
                .map(cloud.jengu.dbo.core.face.RecordProjection::vocabularies)
                .orElse(java.util.List.of())) {
            carried.add(resourceTypeOf(definition));
        }
        java.util.List<cloud.jengu.dbo.fhir.common.FhirTypeConfig> all =
                new java.util.ArrayList<>(declared);
        for (String typeName : carried) {
            if (all.stream().noneMatch(t -> t.typeName().equals(typeName))) {
                all.add(cloud.jengu.dbo.fhir.common.FhirTypeConfig.canonical(typeName));
            }
        }
        return all;
    }

    /**
     * The definitions themselves, written once per tenant. Idempotent by
     * canonical identity — a definition written twice is one record, so a
     * restart costs a conditional write and nothing else.
     */
    private static void publishVocabularies(cloud.jengu.dbo.core.api.ObjectStore engine,
            FhirStoreFacade store, FhirTerminology terminology,
            cloud.jengu.dbo.core.face.DomainFace face) {
        // A face with no terminology surface offers no operations — the
        // existing signal for "this face cannot hold concepts natively",
        // rather than a new flag or a caught exception.
        boolean holdsConceptsNatively = !terminology.operations().isEmpty();
        for (String definition : face.capability(
                cloud.jengu.dbo.core.face.RecordProjection.class)
                .map(cloud.jengu.dbo.core.face.RecordProjection::vocabularies)
                .orElse(java.util.List.of())) {
            try {
                String url = canonicalUrlOf(definition);
                // A CodeSystem goes through INGEST where the face can hold
                // concepts, exactly as a client's would: written the ordinary
                // way it is stored whole and answers nothing, so $lookup and
                // $validate-code cannot resolve dbo's own vocabulary even
                // though the document is fetchable (#98). Ingest is
                // replace-all and cheap for these — six systems of a few
                // codes — so it runs every bring-up rather than being skipped,
                // which also repairs a tenant that stored one whole before.
                if (holdsConceptsNatively && "CodeSystem".equals(resourceTypeOf(definition))) {
                    // Ingest is replace-all: the shell through the engine and
                    // the concepts into the native form. Cheap for one and
                    // ~500ms for the set, which is not a price to pay on every
                    // boot for definitions that have not moved — so the stored
                    // version decides, and it is derived from the vocabulary
                    // rather than from dbo's release number (#93, #98).
                    if (!publishedVersionOf(engine, definition).equals(fieldOf(definition,
                            "\"version\""))) {
                        terminology.ingestCodeSystem(definition);
                    }
                    continue;
                }
                // Asked before written. A conditional create is a full
                // validate and a write every time, and a definition that has
                // not changed since the last boot needs neither — measured at
                // ~300ms per bring-up for work already done (#93). The lookup
                // is on canonical identity, which is indexed, so a warm tenant
                // pays one read per definition and nothing else.
                if (!engine.getByIdentifier(resourceTypeOf(definition),
                        java.util.List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url)))
                        .isEmpty()) {
                    continue;
                }
                // Identity-keyed on the canonical url, so a race between two
                // bring-ups rewrites the same record rather than a second one
                // (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS).
                store.conditionalCreate(definition, java.util.Map.of("url", url));
            } catch (cloud.jengu.dbo.core.api.HandlingRefusedException refused) {
                // Anticipated, not wrong (#125): on a tenant whose CodeSystem
                // is somebody else's publication, the engine's own vocabulary
                // arrives through the replication lane instead — the source
                // published the same six at ITS bring-up, and the stream
                // delivers them. A WARN here cried wolf on every zone
                // dependent's boot.
                LOG.info("the face's vocabulary is not this tenant's to write ({}); "
                        + "it arrives from the source tenant through the replication lane",
                        refused.getMessage());
            } catch (RuntimeException e) {
                // A face that publishes a definition this store cannot hold is
                // a misconfiguration worth naming, not a tenant that fails to
                // come up: the trail and the runs still serve without it.
                LOG.warn("could not publish the face's vocabulary: {}", e.toString());
            }
        }
        if (!holdsConceptsNatively) {
            // Said out loud rather than left to be discovered: the definitions
            // are fetchable, and a client cannot resolve a code in them. The
            // face's terminology surface is the missing half (#50, #98).
            LOG.warn("face {} holds no terminology natively: its vocabularies are published "
                    + "as documents, so $lookup and $validate-code answer nothing for them",
                    face.name());
        }
    }

    /** What version of this vocabulary the tenant already holds, or "". */
    private static String publishedVersionOf(cloud.jengu.dbo.core.api.ObjectStore engine,
            String definition) {
        return engine.getByIdentifier(resourceTypeOf(definition),
                        java.util.List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                                canonicalUrlOf(definition))))
                .stream().findFirst()
                .map(stored -> {
                    String json = new String(stored.payload(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    return json.contains("\"version\"") ? fieldOf(json, "\"version\"") : "";
                })
                .orElse("");
    }

    /** The canonical url a definition is identified by. */
    private static String canonicalUrlOf(String definition) {
        String url = fieldOf(definition, "\"url\"");
        // A NamingSystem carries no url in every version — the element does
        // not exist in R4 — and is identified by the namespace it names, which
        // the face claims from its uniqueId. The same value, read the way this
        // layer reads everything else about a definition it was handed (#91).
        return url != null ? url : fieldOf(definition, "\"value\"");
    }

    /** Which resource a definition is, read from the document itself. */
    private static String resourceTypeOf(String definition) {
        return fieldOf(definition, "\"resourceType\"");
    }

    /** One string field, read without a parser: this layer holds documents. */
    private static String fieldOf(String definition, String field) {
        int at = definition.indexOf(field);
        if (at < 0) {
            // Absent, said as absent. Without this the search for the colon
            // starts at 0 and the method returns some other field's value
            // rather than nothing — a wrong answer wearing the shape of a
            // right one.
            return null;
        }
        int colon = definition.indexOf(':', at);
        int open = definition.indexOf('"', colon);
        return definition.substring(open + 1, definition.indexOf('"', open + 1));
    }
}
