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
     * — kept as the scan goes rather than derived afterwards.
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
            /** This tenant's records: what happened here. */
            ChangeFeed feed,
            /**
             * What its face gave it: profiles, parameters, vocabularies.
             *
             * <p>A second feed rather than a filter on the first, because the
             * two move for different reasons and are read by different
             * readers. A subscriber to a face wants the definitions and would
             * otherwise page through every patient a root happens to hold to
             * find the next profile, and the cursor an image is cut at has to
             * be a definitions cursor while records keep moving past it.
             */
            ChangeFeed definitionsFeed,
            FhirHttpServer endpoint,
            /**
             * This tenant's own grain codec. A stream between two tenants needs
             * BOTH ends' — reassembly reads the source's native form and the
             * destination writes its own — so each runtime carries its own
             * rather than the wiring building one from whichever store is handy.
             */
            cloud.jengu.dbo.core.face.GrainCodec grain,

            /**
             * This tenant's replication lane — the one instance of it.
             *
             * <p>It is here because a lane is not a surface: the HTTP door at
             * {@code /t/{code}/replication} is one caller of it, and a bundle
             * in the same framework is another. Building the second caller its
             * own would give one peer two sets of cursors, so the runtime
             * carries the lane and everyone is handed the same object.
             *
             * <p>Never null. The lane's bookkeeping types are registered for
             * every tenant, so every tenant has one; what a tenant may not
             * have is the door, which needs an authority to say who is asking.
             */
            cloud.jengu.dbo.sync.Lanes replication) {}

    /**
     * A face root gets its face from an image, or reads the packages and
     * leaves one behind.
     *
     * <p>Decided inside the lock rather than around it, because the decision
     * is the expensive part. Several roots coming up together would otherwise
     * all look, all miss, and all read the same packages through — and then
     * cut the same image on top of each other, having already spent what the
     * image was for. Holding the lock across looking, loading and cutting
     * means the first pays and the rest wait seconds and load.
     */
    private void faceRootTakesItsFace(TenantSpec spec, cloud.jengu.dbo.core.api.ObjectStore engine,
            FhirStoreFacade store, TenantRuntime runtime, javax.sql.DataSource dataSource) {
        java.nio.file.Path directory = faceImages;
        if (directory == null) {
            FaceRoot.load(spec, engine, store, runtime.grain());
            return;
        }
        try (AutoCloseable held = FaceWarmup.whileNobodyElseIsCutting(directory, spec.face())) {
            String notFromAnImage = FaceBringUp.aRootFrom(directory, spec.face(), dataSource);
            if (notFromAnImage == null) {
                store.shapesChanged();
                caughtUpOnItsOwnFace(runtime);
                return;
            }
            LOG.info("face root {} is reading its packages: {}", spec.code(), notFromAnImage);
            FaceRoot.load(spec, engine, store, runtime.grain());
            // Published to the runtimes map only later, and cutting reads it
            // from there — so the root is registered for the length of the cut
            // and taken out again if anything about this bring-up fails.
            runtimes.put(spec.code(), runtime);
            try {
                FaceWarmup.Outcome cut = FaceWarmup.cutHoldingTheLock(this, spec.code(), directory);
                if (cut instanceof FaceWarmup.Outcome.NotYet notYet) {
                    LOG.info("face {} was not cut: {}", spec.face(), notYet.why());
                }
            } finally {
                runtimes.remove(spec.code());
            }
        } catch (java.io.IOException | RuntimeException couldNotCut) {
            LOG.warn("face {} could not be cut, so roots on it read their packages: {}",
                    spec.face(), couldNotCut.toString());
        } catch (Exception unexpected) {
            throw new IllegalStateException("the face could not be taken", unexpected);
        }
    }

    /** What the watch on a tenant's own definitions is called. */
    private static String shapesConsumer(String code) {
        return "shapes." + code;
    }

    /**
     * Puts the tenant's own watch at the head of its own face.
     *
     * <p>An image carries what the face published as well as what it holds, so
     * a tenant that loaded one has a feed full of definitions it already has
     * and has just built its view from. The watch exists to catch a profile
     * arriving by a path the facade cannot see; left at the beginning it would
     * instead find a whole face, rebuild a view that is already current and
     * reindex every type for no change at all.
     */
    private void caughtUpOnItsOwnFace(TenantRuntime runtime) {
        if (runtime.definitionsFeed() instanceof PgChangeFeed own) {
            own.resetConsumer(shapesConsumer(runtime.spec().code()), own.headCursor());
        }
    }

    /**
     * Cuts this face, if there is no image of it worth using.
     *
     * <p>On the first tenant that asks for it rather than on every root that
     * comes up. A deployment serving one face has no use for images of the
     * others, and an edge node may never want one at all — so the cost is
     * paid where the benefit is, by the tenant that is about to save fifty
     * seconds by not reading the same face through a chain.
     *
     * <p>Also when the image there is from another release. That case is the
     * one worth catching: it costs nothing visible, because the tenant comes
     * up correctly through the chain, and every tenant after it pays the same
     * again forever while a perfectly good image sits unused.
     *
     * <p>Never fatal. A face that could not be cut costs the tenants on it the
     * half minute they would have cost anyway.
     */
    private void cutTheFaceFor(TenantSpec spec, String rootCode, FaceBringUp.Outcome missing) {
        java.nio.file.Path directory = faceImages;
        if (directory == null || !missing.worthCutting()) {
            return;
        }
        LOG.info("cutting face {} because {}", spec.face(), missing.said());
        try {
            FaceWarmup.Outcome outcome = FaceWarmup.cut(this, rootCode, directory);
            if (outcome instanceof FaceWarmup.Outcome.NotYet notYet) {
                LOG.info("face {} was not cut: {}", spec.face(), notYet.why());
            }
        } catch (java.io.IOException | RuntimeException couldNotCut) {
            LOG.warn("face {} could not be cut, so tenants on it read it through the chain: {}",
                    spec.face(), couldNotCut.toString());
        }
    }

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
    /**
     * Runtimes that are mounted and not yet wired. They are nobody's upstream
     * and answer no lookup; the map exists so that a bring-up which fails
     * half-way has one teardown path rather than an inline list of removals
     * that drifts from the real one.
     */
    private final Map<String, TenantRuntime> mounting = new ConcurrentHashMap<>();
    private final AuthorityConfig authorityConfig;

    /**
     * The versions this container can serve. A tenant's declared version is
     * resolved here (R6) — the wiring no longer knows which ones exist, so a
     * new one is a bundle rather than another branch.
     */
    private final FhirVersions versions;

    /**
     * The steps this container knows about, for classifying a mandatory
     * step's absence as an incident. Like {@link #versions}:
     * registry-backed in the container, classpath-backed on a plain JVM.
     */
    private final cloud.jengu.dbo.core.process.Steps steps;

    /** The classification itself, re-evaluated every scan. */
    private final StepIncidents stepIncidents = new StepIncidents();
    private final Map<String, String> authorityContexts = new ConcurrentHashMap<>();
    private final Map<String, String> identityContexts = new ConcurrentHashMap<>();
    private final Map<String, String> fleetContexts = new ConcurrentHashMap<>();
    private final Map<String, String> erasureContexts = new ConcurrentHashMap<>();
    private final Map<String, String> scimContexts = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.pdi.PersonVault> vaults = new ConcurrentHashMap<>();
    private volatile cloud.jengu.dbo.auth.IdentityHub identityHub;
    private final Map<String, javax.sql.DataSource> tenantDataSources = new ConcurrentHashMap<>();

    /**
     * Where the operator keeps the images faces were cut into, or null when it
     * keeps none and every tenant reads its face through the chain.
     *
     * <p>Set before serving begins and not after: a tenant already up has
     * taken its face by one route or the other, and changing the answer
     * underneath it would only affect the next one.
     */
    private volatile java.nio.file.Path faceImages = whereTheDeploymentSaid();

    /**
     * The image mount as the deployment names it, or null when it names none.
     *
     * <p>A path is a deployment fact like the database's host, so it arrives
     * the way those do rather than through a constructor every caller has to
     * learn about. A deployment that says nothing gets the behaviour it had
     * before there were images, which is the right default: images are an
     * optimisation with an operator's disk behind them, and taking one without
     * being asked is not this store's to decide.
     */
    private static java.nio.file.Path whereTheDeploymentSaid() {
        String named = System.getProperty("dbo.face.images");
        return named == null || named.isBlank() ? null : java.nio.file.Path.of(named);
    }
    private final Map<String, cloud.jengu.dbo.auth.IdentityHub> zoneHubs = new ConcurrentHashMap<>();
    private final Map<String, cloud.jengu.dbo.policy.RetentionSweep> sweeps = new ConcurrentHashMap<>();
    /**
     * The tenants' authorities, kept so the sweep can reach them: a retired
     * signing key has to be REMOVED eventually, or it goes on verifying and
     * the rotation was only cosmetic.
     */
    private final Map<String, cloud.jengu.dbo.auth.TenantAuthority> authorities =
            new ConcurrentHashMap<>();
    private final Map<String, String> maintenanceContexts = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * How long a participant may be behind and unmoving before its
     * declaration stops being a candidate. Long enough that a slow
     * participant is not disqualified for being slow, and short enough that
     * an operator asking "who is running this step" is not told about a
     * machine that left last week.
     */
    private static final java.time.Duration DECLARATION_PATIENCE =
            java.time.Duration.ofMinutes(2);
    /** Where each tenant's lane surface is mounted, for the same teardown. */
    private final Map<String, String> workContexts = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each tenant's door on the stream, while it is served; none unless a substrate was given. */
    private final Map<String, cloud.jengu.dbo.stream.StreamDoor> doors =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile javax.sql.DataSource substrate;

    /**
     * The durable substrate this container opens each tenant's stream door
     * on. Optional: a container without one serves its lanes over HTTP and
     * in-process only, which is every container there was before the fleet.
     */
    public void substrate(javax.sql.DataSource substrate) {
        this.substrate = substrate;
    }
    /** And its replication surface. */
    private final Map<String, String> replicationContexts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine>> syncEngines =
            new ConcurrentHashMap<>();
    /** Where a tenant's runs are written: the engine, under the policy decorator. */
    private final Map<String, ObjectStore> runStores = new ConcurrentHashMap<>();
    /** What went wrong for a tenant that is not serving, and whose problem it is. */
    private final Map<String, Trouble> trouble = new ConcurrentHashMap<>();
    /** The tenant this deployment's own history lives in. */
    private volatile String managementCode;
    /** Where each tenant's ask-to-apply door is mounted, for the same teardown. */
    private final Map<String, String> configurationContexts = new ConcurrentHashMap<>();

    /** A tenant that is not serving, and why — the reason a card has to carry. */
    private record Trouble(cloud.jengu.dbo.work.Failure failure, String reason) {}
    private volatile long lastSweepMillis;
    private volatile Thread scanner;
    private volatile Thread reconciler;
    private volatile boolean running;

    /**
     * Set once, when the node is being taken down.
     *
     * <p>Distinct from {@code running}, which says the loops are turning. A
     * round asked for directly — a test does, a bring-up does — has to work
     * whether or not the loops were ever started, so a drain cannot ask that
     * question. What it may ask is whether the pools it is reading through are
     * about to be closed.
     */
    private volatile boolean closing;

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

    public TenantRuntimeManager(Path directory, TenantDatabaseProvisioner provisioner,
            String host, int port, Listener listener, AuthorityConfig authorityConfig,
            FhirVersions versions) {
        this(directory, provisioner, host, port, listener, authorityConfig, versions,
                cloud.jengu.dbo.core.process.Steps.installed());
    }

    /**
     * @param versions what this container can serve. The container passes the
     *                 service registry's view, which changes as face bundles
     *                 come and go; the constructors above ask the classpath,
     *                 which is what a test or a single-jar assembly has.
     * @param steps    the step catalogue this container knows, against which
     *                 the spec's {@code mandatorySteps} classify incidents —
     *                 same two sources as {@code versions}: the registry in
     *                 the container, the classpath on a plain JVM.
     */
    public TenantRuntimeManager(Path directory, TenantDatabaseProvisioner provisioner,
            String host, int port, Listener listener, AuthorityConfig authorityConfig,
            FhirVersions versions, cloud.jengu.dbo.core.process.Steps steps) {
        this.versions = versions;
        this.steps = steps;
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

    /**
     * A tenant's own database, for the few things that are about the storage
     * rather than about what is stored — cutting a face out of one, loading
     * one into another.
     *
     * <p>Not a general door onto a tenant's rows: everything that reads or
     * writes records goes through the store, which is where handling, policy
     * and the audit trail are. This exists because an image is bytes moved
     * below all of that, and moving them through the record path would be
     * loading a specification one row at a time.
     */
    /**
     * Where images of faces are kept, for tenants to come up from.
     *
     * <p>A mount the operator provides, like a secret: this store cuts images
     * and accepts them, and never decides where they live or how long they
     * are kept.
     */
    public TenantRuntimeManager faceImagesIn(java.nio.file.Path directory) {
        this.faceImages = directory;
        return this;
    }

    public Optional<javax.sql.DataSource> databaseOf(String code) {
        return Optional.ofNullable(tenantDataSources.get(code));
    }

    public Optional<TenantRuntime> runtime(String code) {
        return Optional.ofNullable(runtimes.get(code));
    }

    /**
     * Brings up the tenant that records what this deployment does about the
     * others.
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
    /**
     * What a declared tenant that is not serving would say if asked:
     * the trouble ledger, code (or {@code spec:<file>} for a spec that never
     * parsed) to reason. A bring-up failure is caught into this ledger and
     * the tenant simply never serves — so a caller that only probes the
     * endpoint meets a bare 404 three steps from the cause. This is the
     * accessor that closes that distance: harnesses assert against it, and
     * an operator surface can render it.
     */
    public Map<String, String> troubles() {
        Map<String, String> reasons = new java.util.LinkedHashMap<>();
        trouble.forEach((code, why) -> reasons.put(code, why.reason()));
        return reasons;
    }

    /** The tenant's own authority, for tools and tests that mint its clients. */
    public cloud.jengu.dbo.auth.TenantAuthority authority(String code) {
        return authorities.get(code);
    }

    /**
     * The tenant's policies, with its partner declared as an audience when
     * the tenant did not declare one itself: runs and the trail, revealed in
     * the omitting mode — the journey, never a document, purposes only if
     * the tenant says so. The relation says which tenants a partner may read
     * at all; this says what of each.
     */
    static cloud.jengu.dbo.policy.TenantPolicies policiesOf(TenantSpec spec) {
        if (spec.managedBy() == null) {
            return spec.policies();
        }
        return spec.policies().withAudienceUnlessDeclared(spec.managedBy(),
                new cloud.jengu.dbo.policy.TenantPolicies.Audience(
                        Set.of(cloud.jengu.dbo.work.WorkModel.TYPE, "AuditEntry"),
                        cloud.jengu.dbo.core.api.Disclosure.Mode.OMIT));
    }

    /** The types a lane may carry by type: the tenant's declared types minus the person-typed ones. */
    static Set<String> declarationTypes(TenantSpec spec) {
        Set<String> people = spec.pdi()
                ? cloud.jengu.dbo.pdi.PdiSpec.fhir().personTypes().keySet() : Set.of();
        Set<String> admitted = new java.util.TreeSet<>();
        for (cloud.jengu.dbo.fhir.common.FhirTypeConfig type : spec.types()) {
            if (!people.contains(type.typeName())) {
                admitted.add(type.typeName());
            }
        }
        return admitted;
    }

    /** A partner's issuer, derived from this tenant's own: the same authority base, the partner's code. */
    private static String partnerIssuer(String ownIssuer, String code, String partner) {
        return ownIssuer.replace("/t/" + code + "/", "/t/" + partner + "/");
    }

    public synchronized Set<String> scanOnce() {
        // The declarations first, then what this deployment does about them.
        // Applying is what turns a source into the records the sweep below
        // reads; a deployment with no managing tenant has no records and reads
        // its source directly, which is the floor every deployment starts on
        // and the one the managing tenant itself comes up over.
        try {
            lastApplication = recordDeclarations();
        } catch (RuntimeException unreadable) {
            // Said already, in the ledger and the log. The tenants already
            // declared are unaffected, and the records stand as they were.
            lastApplication = new cloud.jengu.dbo.sync.ConfigApplication.Outcome(0, 0, 0, 0);
        }
        Set<String> declared = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // Together, not one after another. Bring-up is minutes of somebody
        // else's waiting — a database, a schema, a secret that has not landed
        // — and doing them in turn made a queue whose length was the
        // deployment's size: a consumer declaring two dozen tenants at once
        // got the first few and a refusal for everyone behind them, which
        // reads as a broken tenant rather than a busy one.
        together(declaredNow(), declaration -> {
            String named = declaration.name();
            try {
                TenantSpec spec = TenantSpec.parse(new String(declaration.payload(),
                        java.nio.charset.StandardCharsets.UTF_8));
                declared.add(spec.code());
                states.putIfAbsent(spec.code(), TenantState.State.COMING_UP);
                trouble.remove(spec.code());
                trouble.remove("spec:" + named);
                TenantRuntime serving = runtimes.get(spec.code());
                if (serving != null) {
                    noticeRedeclaration(serving, spec);
                }
                if (serving == null) {
                    long began = System.nanoTime();
                    bringUp(spec);
                    states.put(spec.code(), TenantState.State.SERVING);
                    reportedFailures.removeIf(k -> k.startsWith(named + ":"));
                    LOG.info("tenant up: code={} fhir={} pdi={} in {}ms",
                            spec.code(), spec.face(), spec.pdi(),
                            (System.nanoTime() - began) / 1_000_000);
                    // The shape is rolled up once the pass is done rather than
                    // per tenant: with several coming up at once, a rollup per
                    // bring-up prints a count that was true for nobody.
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
                // Two ways of not being up yet, and neither is a
                // fault: an upstream this tenant declares is not
                // serving, or storage somebody else provisions has
                // not arrived. Both are answered by the next scan.
                boolean stillComing = e instanceof UpstreamNotReady
                        || e instanceof TenantDatabaseProvisioner.NotProvisionedYet;
                // The code is known when the spec parsed, which is
                // the case an operator asks about: a tenant that was
                // declared and did not come up. A declaration that never
                // parsed has no tenant to be a state of.
                codeOf(declaration).ifPresent(code -> states.put(code,
                        stillComing
                                ? TenantState.State.COMING_UP
                                : TenantState.State.FAILED));
                // What an operator has to be told, kept where the
                // sweep can find it: a declaration that will never parse
                // has no tenant to be a state of, so it is named by
                // what it is called where it was written — which is what
                // somebody has to open to fix it.
                trouble.put(codeOf(declaration).orElse("spec:" + named),
                        new Trouble(stillComing
                                ? cloud.jengu.dbo.work.Failure.TRANSIENT
                                : cloud.jengu.dbo.work.Failure.of(e),
                                String.valueOf(e)));
                if (stillComing) {
                    // Expected on the way up, so it is not an error
                    // and does not enter the suppression set: the
                    // next scan is where it resolves.
                    LOG.debug("{}", e.getMessage());
                    return;
                }
                String signature = named + ":" + e;
                if (reportedFailures.add(signature)) {
                    LOG.error("tenant bring-up failed: declaration={} (further identical "
                            + "failures suppressed until it changes)", named, e);
                }
            }
        });
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
        trouble.keySet().removeIf(key -> !declared.contains(key) && !key.startsWith("spec:")
                && !key.equals(SOURCE_TROUBLE));
        // Mandatory steps classify, they do not gate: a serving tenant
        // with a mandatory step nothing contributes keeps serving — its runs
        // queue — and the absence is an incident here, re-evaluated every
        // pass because the catalogue changes as modules and participants
        // come and go. The catalogue read is the COMPOSED one:
        // installed modules plus the steps linked participants introduced
        // into this tenant's own store — which is exactly how the platform's
        // steps satisfy a tenant's mandatory list when it connects over the
        // link rather than by installation.
        for (TenantRuntime runtime : runtimes.values()) {
            try {
                stepIncidents.observe(runtime.spec(),
                        new cloud.jengu.dbo.work.Introductions(runtime.engine(), steps)
                                .composedWith());
            } catch (RuntimeException faulty) {
                // A collision (one id, two declarers) or a broken record is
                // one tenant's fault to report, not a reason the other
                // tenants' classification stops running.
                LOG.error("step classification failed: tenant={} {}",
                        runtime.spec().code(), faulty.getMessage());
            }
        }
        stepIncidents.retain(runtimes.keySet());
        rollup();
        recordServing();
        return codes();
    }

    /**
     * Per serving tenant, the mandatory steps nothing has contributed — the
     * operator's incident read. A tenant absent here has no open step
     * incident; the tenant itself is never taken down for one.
     */
    public Map<String, Set<String>> stepIncidents() {
        return stepIncidents.byTenant();
    }

    /**
     * Serves the runtime's own state at {@code /runtime/tenants}.
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
        sharedServer.createContext("/runtime/tenants", opsGuarded(expected, () -> {
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
            return json.append("]}").toString();
        }));
        // The node's inventory, beside its tenants and under the same token.
        // What is INSTALLED here never leaves the node any other way: a
        // participant's declarations are records in a tenant's store whatever
        // node it runs on, but the modules a node carries are the node's
        // alone, and they cannot travel through the introduction door — a
        // step declared by both doors is a collision by design, which two
        // nodes carrying the same modules would hit at once. So this is
        // descriptive, an inventory a deployment unions across its nodes,
        // and never a second declaration of anything.
        sharedServer.createContext("/runtime/catalogue", opsGuarded(expected, () -> {
            List<Map<String, Object>> installed = new java.util.ArrayList<>();
            for (String id : steps.ids()) {
                steps.byId(id).ifPresent(step -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", step.id().toString());
                    row.put("version", step.version());
                    row.put("reads", new java.util.TreeSet<>(step.reads()));
                    row.put("writes", new java.util.TreeSet<>(step.writes()));
                    step.overridable().ifPresent(by -> row.put("overridable", by));
                    installed.add(row);
                });
            }
            return cloud.jengu.dbo.core.wire.RecordWire.write(Map.of("steps", installed));
        }));
        LOG.info("runtime state: serving /runtime/tenants and /runtime/catalogue");
    }

    /**
     * A deployment-level answer behind the deployment's token: GET only, the
     * token compared in constant time, and a refusal that says nothing about
     * what it is refusing to show.
     */
    private com.sun.net.httpserver.HttpHandler opsGuarded(byte[] expected,
            java.util.function.Supplier<String> answer) {
        return exchange -> {
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
                respond(exchange, 200, answer.get());
            } finally {
                exchange.close();
            }
        };
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** The tenant a spec file declares, when it parses — for a state to belong to. */
    private static Optional<String> codeOf(
            cloud.jengu.dbo.sync.ConfigApplication.Declared declaration) {
        try {
            return Optional.of(TenantSpec.parse(new String(declaration.payload(),
                    java.nio.charset.StandardCharsets.UTF_8)).code());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * What each serving tenant is declared as, where that differs from what it
     * was built from. A tenant absent here is serving exactly what somebody
     * declared.
     */
    private final Map<String, SpecChange> redeclared = new ConcurrentHashMap<>();

    /**
     * Per serving tenant, how its declaration now differs from what it is
     * serving — in the words somebody deciding what to do about it needs.
     *
     * <p>Empty is the ordinary answer and the reassuring one: it means every
     * serving tenant is serving what was declared, which nobody could ask
     * before. A declaration used to be read once, at mount, so a changed spec
     * was neither applied nor refused nor reported, and the only way to change
     * a live tenant was to withdraw it — dropping its surfaces, its lanes and
     * its dependents' streams to alter one field.
     */
    public Map<String, String> redeclarations() {
        Map<String, String> saying = new java.util.TreeMap<>();
        redeclared.forEach((code, change) -> saying.put(code, change.says()));
        return Map.copyOf(saying);
    }

    /**
     * Notices that a serving tenant is declared differently, and says what
     * kind of difference it is.
     *
     * <p>Noticing is all this does. What can be absorbed while serving and
     * what has to be rebuilt are applied where the tenant is built; what
     * cannot be had at all is refused here, by name, because a cold change
     * that is silently ignored is a deployment believing something about
     * itself that is not true.
     */
    private void noticeRedeclaration(TenantRuntime serving, TenantSpec declared) {
        SpecChange change = SpecChange.between(serving.spec(), declared);
        if (!change.any()) {
            redeclared.remove(declared.code());
            return;
        }
        SpecChange before = redeclared.put(declared.code(), change);
        if (change.kind() == SpecChange.Kind.COLD) {
            // A person's, and not a retry's: it will read the same way on
            // every pass until somebody decides.
            trouble.put(declared.code(), new Trouble(cloud.jengu.dbo.work.Failure.RECORD,
                    "declared differently from what it serves — " + change.says()));
            if (!change.equals(before)) {
                // Once per distinct difference. The scan comes round every
                // couple of seconds, and a line per pass would bury the one
                // that mattered.
                LOG.info("tenant {} is declared differently from what it serves: {}",
                        declared.code(), change.says());
            }
            return;
        }
        if (change.kind() == SpecChange.Kind.HOT) {
            // A hot change is one the tenant takes where it stands, and that
            // has to mean taking it — a field classified hot and then merely
            // remembered is the no-op the classification exists to prevent.
            if (declared.faceRoot() && !serving.spec().faceRoot()) {
                FaceRoot.load(declared, serving.engine(), serving.store(), serving.grain());
            }
            // Nothing this tenant is made of changes, so nothing is rebuilt:
            // the runtime carries the declaration it answers about, and it
            // answers about the new one from here.
            runtimes.put(declared.code(), new TenantRuntime(declared, serving.engine(),
                    serving.store(), serving.feed(), serving.definitionsFeed(),
                    serving.endpoint(), serving.grain(), serving.replication()));
            redeclared.remove(declared.code());
            LOG.info("tenant {} took a change where it stands: {}",
                    declared.code(), change.says());
            return;
        }
        rebuild(serving, declared, change);
    }

    /**
     * A serving tenant, rebuilt from the declaration it now has.
     *
     * <p>Not a retraction, and the difference is the point: nothing is
     * recorded as withdrawn, the database, the lanes and their cursors stay
     * exactly where they are, and the dependents streaming from this tenant
     * are wired again rather than left holding a feed on a pool that has
     * closed. What does stop, for as long as this takes, is the tenant's HTTP
     * surface — a rebuild is a remount, and saying otherwise would be
     * describing something this does not do.
     *
     * <p>It has to go down first. The container registers a tenant's services
     * when it comes up and unregisters them when it goes, so mounting over a
     * tenant that is still up would register a second set and leak the first:
     * a registry with two of everything, which resolves, publishes and then
     * answers from whichever one a caller happened to get.
     *
     * <p>What can be checked before anything is torn down, is: a declaration
     * naming a face nothing serves, or asking a face for what it does not
     * offer, is refused with the tenant still running. Past that point a
     * rebuild that fails leaves the tenant down with its reason in the ledger,
     * and the declaration is what has to be fixed — the same as for a tenant
     * that never came up.
     */
    private void rebuild(TenantRuntime serving, TenantSpec declared, SpecChange change) {
        String code = declared.code();
        FhirVersion version = versions.require(declared.face());
        FaceRequirements.refuseUnservable(declared, version.face());
        // A tenant that now streams from somebody who is not up yet is a wait,
        // not a rebuild. Checking here rather than inside the mount is the
        // difference between waiting and being taken down: past the teardown
        // the same refusal leaves the tenant unmounted until its upstream
        // appears, and it was serving perfectly well before anybody edited it.
        for (TenantSpec.Dependency dependency : declared.dependencies()) {
            if (!runtimes.containsKey(dependency.name())) {
                throw new UpstreamNotReady(code, dependency.name());
            }
        }
        LOG.info("tenant {} is being rebuilt where it stands: {}", code, change.says());
        runtimes.remove(code);
        listener.tenantDown(code);
        authorities.remove(code);
        unmount(code, serving);
        mountTenant(declared);
        redeclared.remove(code);
        // Whoever streams from this tenant was handed its feed when they were
        // wired, and that feed belonged to the runtime that has just been
        // replaced. Re-wiring them is not a courtesy: a dependent left holding
        // the old one reads from a pool nobody owns any more, and says nothing
        // about it because a stream that delivers no events looks exactly like
        // an upstream with nothing to say.
        for (TenantRuntime dependent : runtimes.values()) {
            boolean streamsFromIt = dependent.spec().dependencies().stream()
                    .anyMatch(dependency -> dependency.name().equals(code));
            if (!streamsFromIt) {
                continue;
            }
            javax.sql.DataSource on = tenantDataSources.get(dependent.spec().code());
            if (on == null) {
                continue;
            }
            wireDependencies(dependent.spec(), dependent, on);
            LOG.info("tenant {} was wired again: the tenant it streams from was rebuilt",
                    dependent.spec().code());
        }
    }

    /**
     * How many dependency streams this node keeps in step at once.
     *
     * <p>Separate from how many tenants come up together, and higher, because
     * the two are bounded by different things. A bring-up holds a validator,
     * which is heap; a stream reads a feed, converts payloads and writes
     * copies, which is a database and a core. Bounding streams by the
     * bring-up number meant tuning them for somebody else's constraint — and
     * the bring-up number came down to two for a heap reason that says
     * nothing at all about replication.
     *
     * <p>Bounded rather than unbounded because a node's streams share more
     * than its database. Every one in flight is reading a feed, rebuilding a
     * CodeSystem from its source's concepts, converting it and taking it apart
     * again — and a payload being converted is held whole. Eight was the first
     * answer here and the suite met {@code OutOfMemoryError} under it, which
     * is the same evidence that brought the bring-up bound down: the memory is
     * the bound, and a number chosen for the database alone was choosing on
     * the wrong axis.
     *
     * <p>Four, then, and a deployment that has measured its own streams raises
     * it deliberately — which is what the property is for.
     */
    private volatile int streamsTogether = 4;

    /** How many dependency streams this node may keep in step at once. */
    public void streamsTogether(int atOnce) {
        if (atOnce < 1) {
            throw new IllegalArgumentException("a node that keeps no streams in step at a time "
                    + "keeps none of them in step: " + atOnce);
        }
        this.streamsTogether = atOnce;
    }

    /**
     * How many tenants this node brings up at once.
     *
     * <p>Bounded rather than unbounded, and the bound is small on purpose:
     * each bring-up opens a pool, sets up a schema and — where the face
     * validates — loads a core package that wants heap. Two dozen of those at
     * once is not two dozen times faster; it is a node that dies as an
     * out-of-memory error three frames under a message that mentions none of
     * this.
     *
     * <p><b>Two, not four.</b> Four is what this was written as, and the
     * suite proving it met {@code OutOfMemoryError} on one tenant of eight —
     * on a JVM with rather more heap than a serving node is given. The number
     * that matters is not how many a node can start, it is how many
     * validators it can hold at once, and that is a property of the deployment
     * rather than of this class. So the default is the smallest one that is
     * still not a queue, and a deployment with heap to spare raises it
     * knowing what it is spending.
     */
    private volatile int broughtUpTogether = 2;

    /**
     * How many tenants may come up at once here. A deployment whose tenants
     * are slow to provision and whose node has room can raise it; one is the
     * old behaviour, in order, for anybody who wants it back.
     */
    public void broughtUpTogether(int atOnce) {
        if (atOnce < 1) {
            throw new IllegalArgumentException("a node that brings up no tenants at a time "
                    + "serves nothing: " + atOnce);
        }
        this.broughtUpTogether = atOnce;
    }

    /**
     * Runs the work for each declaration, several at a time, and returns when
     * every one of them is finished.
     *
     * <p>Waiting is the point. A scan says what it serves, and a scan that
     * returned before its bring-ups finished would be answering about a
     * moment that had not happened yet — the caller's whole question is
     * whether the tenants are there. What it stops being is <b>sequential</b>:
     * nobody waits for the tenant in front of them any more.
     *
     * <p>Each one carries its own failure, as it did when they were in a loop:
     * a bring-up that throws is that tenant's trouble and nobody else's.
     */
    private <T> void together(java.util.List<T> work, java.util.function.Consumer<T> each) {
        together(work, broughtUpTogether, each);
    }

    private <T> void together(java.util.List<T> work, int bound,
            java.util.function.Consumer<T> each) {
        int atOnce = bound;
        if (work.size() <= 1 || atOnce <= 1) {
            work.forEach(each);
            return;
        }
        java.util.concurrent.Semaphore room = new java.util.concurrent.Semaphore(atOnce);
        java.util.List<Thread> running = new java.util.ArrayList<>(work.size());
        for (T one : work) {
            running.add(Thread.ofVirtual().start(() -> {
                room.acquireUninterruptibly();
                try {
                    each.accept(one);
                } finally {
                    room.release();
                }
            }));
        }
        for (Thread thread : running) {
            try {
                thread.join();
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                // The scan is being torn down. Nothing is left half-mounted by
                // this: each bring-up rolls itself back or completes.
                return;
            }
        }
    }

    /**
     * What this deployment is declared to serve, right now.
     *
     * <p>The records, once there is a managing tenant to hold them: the sweep
     * then reconciles against what was <b>applied</b> rather than against a
     * listing it takes itself, so absence has a cause somebody produced. A
     * mount that vanished, a half-written file, a ConfigMap between two
     * generations — none of them reach this list, because applying refuses an
     * incomplete read and the records keep saying what they last said.
     *
     * <p>Without a managing tenant there are no records, and reading the
     * source directly is the floor: a deployment cannot bootstrap out of a
     * store it has not built yet, and the managing tenant itself is declared
     * by configuration for the same reason.
     */
    private java.util.List<cloud.jengu.dbo.sync.ConfigApplication.Declared> declaredNow() {
        ObjectStore management = managementCode == null ? null : runStores.get(managementCode);
        if (management == null) {
            return new cloud.jengu.dbo.sync.DirectoryConfigSource(
                    directory, TenantDeclarationModel.TYPE, ".json").fetch().declarations();
        }
        java.util.List<cloud.jengu.dbo.sync.ConfigApplication.Declared> onRecord =
                new java.util.ArrayList<>();
        for (cloud.jengu.dbo.core.api.StoredObject record : management.select(
                cloud.jengu.dbo.core.api.Criteria.of(TenantDeclarationModel.TYPE))) {
            cloud.jengu.dbo.sync.ConfigApplication.Declared declared =
                    new cloud.jengu.dbo.sync.ConfigApplication.Declared(
                            TenantDeclarationModel.TYPE, record.id(), record.payload());
            // Named by the tenant it declares, which is what somebody looking
            // for it would search — and by the record's own id when it cannot
            // be read at all, so it can still be named in a card.
            onRecord.add(codeOf(declared)
                    .map(code -> new cloud.jengu.dbo.sync.ConfigApplication.Declared(
                            TenantDeclarationModel.TYPE, code, record.payload()))
                    .orElse(declared));
        }
        return onRecord;
    }

    /**
     * What this runtime is doing about each tenant it has been told about
     *.
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
                        r -> r.spec().face(), java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(" "));
        LOG.info("tenants: serving={} {} pdi={}",
                runtimes.size(),
                byVersion.isEmpty() ? "versions=none" : byVersion,
                runtimes.values().stream().filter(r -> r.spec().pdi()).count());
    }

    /**
     * Brings a tenant up, or leaves nothing behind.
     *
     * <p>A bring-up mounts surfaces as it goes, and it can fail after some of
     * them are up — a spec that declares SCIM it cannot serve, a vocabulary
     * that will not publish. What was mounted used to stay mounted, so the
     * retry two seconds later did not meet the original problem: it met its
     * own OIDC context and died as {@code cannot add context to list}. The
     * ledger an operator reads then said that, forever, instead of saying
     * which spec was wrong.
     */
    private void bringUp(TenantSpec spec) {
        try {
            mountTenant(spec);
        } catch (RuntimeException | Error incomplete) {
            rollBack(spec.code());
            throw incomplete;
        }
    }

    /**
     * Everything a bring-up mounted, taken back down — with or without a
     * runtime, because a bring-up can fail on either side of the moment one
     * exists. Not a retraction: nothing served, nobody was told, and there is
     * nothing to record.
     */
    private void rollBack(String code) {
        TenantRuntime staged = mounting.remove(code);
        authorities.remove(code);
        unmount(code, staged);
    }

    private void mountTenant(TenantSpec spec) {
        // Dependencies wire against the upstream's LIVE runtime —
        // like the zone hub, an upstream that isn't up yet stops bring-up
        // here, before anything is created, and the scan retries once it is.
        // The same exception the wiring itself throws: a dependent met before
        // its upstream is COMING_UP rather than FAILED, which is the
        // difference between a wait and a fault on the operator's card.
        for (TenantSpec.Dependency dependency : spec.dependencies()) {
            if (!runtimes.containsKey(dependency.name())) {
                throw new UpstreamNotReady(spec.code(), dependency.name());
            }
        }
        // Resolved before anything is created. A tenant declaring a version
        // nothing provides is refused because nothing provides it, and asking
        // first means the refusal leaves no database behind to clean up.
        FhirVersion version = versions.require(spec.face());
        // The refusing half of the face contract: what this spec
        // requires, compared against what the face declares, before the
        // database exists. An absent capability used to surface where it was
        // first needed — mid-request, or as a quiet degradation.
        FaceRequirements.refuseUnservable(spec, version.face());
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
                // The deployment's own credential for this tenant, and the
                // participation scope is part of that: a host holding
                // a lane over HTTP is the tenant saying so, which is exactly
                // what this credential already is. It grants strictly less
                // than the system scopes beside it — a lane is twelve verbs,
                // and system/*.write is the store. Narrower participation
                // credentials — work/<step>, bounded at issue — are minted
                // per participant by whoever operates the fleet.
                // Erasure is granted EXPLICITLY here, and that is the whole
                // point of it being its own scope: the deployment's own
                // credential may destroy a person's key, and the broad write
                // grant sitting beside it does not imply that. A credential
                // that could write every type still cannot erase anybody
                // unless somebody wrote the word down.
                authority.ensureClient("tenant-bootstrap", db.bootstrapClientSecret(),
                        java.util.List.of("system/*.read", "system/*.write",
                                cloud.jengu.dbo.auth.Scopes.WORK,
                                cloud.jengu.dbo.auth.Scopes.ERASURE,
                                // Identification, granted explicitly for the
                                // same reason erasure is and never as a
                                // consequence of it. They are opposite acts on
                                // one person: this attaches an identity, that
                                // destroys the key that made one legible.
                                cloud.jengu.dbo.auth.Scopes.IDENTITY,
                                // And the fleet: a deployment that operates
                                // the participants may ask what is behind
                                // them. Named here like the rest rather than
                                // implied by the participation scope, which
                                // covers what a bench does and not what
                                // anybody may ask about every bench.
                                cloud.jengu.dbo.auth.Scopes.FLEET));
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
        // answer a consumer can act on.
        FhirVersion.ForTypes declared = version.forTypes(withVocabularyTypes(spec.types(),
                version.face()));
        // Checked here rather than trusted, because a misplaced type fails
        // nowhere: its rows are written, read and fed back exactly as they
        // would be from the right domain, and what breaks is a face cut from a
        // schema missing a profile — or carrying a patient — long after the
        // image was handed out.
        cloud.jengu.dbo.fhir.common.FaceDefinitions.refuseIfMisplaced(
                declared.registrations(), version.domain());
        cloud.jengu.dbo.policy.PolicyObjectStore engine =
                policyWrapped(spec, db, declared.registrations(), version.domain(),
                        version.face());
        if (authority != null) {
            authority.attachSubjects(engine); // §16.1: subjects are the tenant's records
        }
        if (spec.scim() != null) {
            // The provisioning door needs the authority (its scope and its
            // tokens), the vault (the internal enumeration) and the person
            // types the mapping writes. Each absence is named: a door that
            // half-exists answers stranger questions than one that refused.
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (authority == null) {
                missing.add("a tenant authority (scim tokens are its tokens)");
            }
            if (vaults.get(spec.code()) == null) {
                missing.add("the person vault (pdi)");
            }
            java.util.Set<String> typeNames = new java.util.HashSet<>();
            spec.types().forEach(type -> typeNames.add(type.typeName()));
            if (!typeNames.contains("Person") || !typeNames.contains("Practitioner")) {
                missing.add("declared Person and Practitioner types (the mapping writes them)");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(spec.code() + ": scim declared but unservable — "
                        + String.join("; ", missing));
            }
            String scimPath = "/t/" + spec.code() + "/scim/v2";
            sharedServer.createContext(scimPath, new cloud.jengu.dbo.scim.ScimHandler(
                    authority, engine, vaults.get(spec.code()),
                    spec.scim().system(), scimPath));
            scimContexts.put(spec.code(), scimPath);
        }
        // With the tenant's database: a face that validates against current
        // data — the tenant's terminology, and in time its own structure
        // definitions — needs to know where that data lives.
        // Bring-up says what it cost, in the plain log. A first boot on a
        // fresh database does work a later one does not — importing the
        // terminology baseline, publishing the face's vocabulary, reading the
        // tenant's profiles — and the budget test measures the SECOND boot on
        // purpose, so none of this was visible in a number anybody read. It
        // stopped being invisible the day it quadrupled under memory pressure
        // and surfaced as a closed connection pool.
        long facadeAt = System.currentTimeMillis();
        boolean versionHeldAsRecords = spec.faceRoot()
                || spec.dependencies().stream().anyMatch(TenantSpec.Dependency::face);
        FhirStoreFacade store = declared.store(engine, base, db.dataSource(), versionHeldAsRecords);
        long facadeMillis = System.currentTimeMillis() - facadeAt;

        // REQ-DBO-TERM-EVERY-TENANT-ANSWERS: the native form is per tenant,
        // so the facade is built here rather than shared — a tenant answers
        // $expand from its own concepts or it is a second-class reader.
        FhirTerminology terminology = declared.terminology(engine, db.dataSource());
        // The lane's own objects, built once for this tenant and shared by
        // everything that reaches for a lane.
        //
        // The WORK domain's feed, not the tenant's content feed: runs and
        // declarations are work-domain records, and a lane reading the
        // content feed polls a stream runs never appear in — which looks
        // exactly like a lane with no work, for ever.
        cloud.jengu.dbo.core.api.feed.ChangeFeed laneFeed =
                new PgChangeFeed(db.dataSource(), cloud.jengu.dbo.work.WorkModel.DOMAIN);
        // WITH the step catalogue, and that is load-bearing rather than
        // tidy. Reports land through the actions a step declares
        // (REQ-DBO-PROC-REPORT-THROUGH-DECLARED-ACTIONS), the rule lives at
        // the primitive so every door meets one copy of it — and a Runs built
        // without a catalogue resolves no declaration and therefore narrows
        // nothing. Built without one, this lane accepted a close from a step
        // whose declaration says its closure is a person's act, and the rule
        // was enforced only where a caller happened to pass a catalogue: a
        // test, which builds whatever it needs. Composed rather than
        // installed-only, so a participant's introduced step is held to the
        // declaration it introduced, exactly as the authoring surface is.
        cloud.jengu.dbo.work.Runs laneRuns = new cloud.jengu.dbo.work.Runs(
                runStores.get(spec.code()),
                new cloud.jengu.dbo.work.Introductions(runStores.get(spec.code()), steps)
                        .composedWith());
        // Built here rather than beside the door it used to be built beside.
        // A lane needs no authority — an authority answers "who is asking",
        // which is a question the framework's own registry never poses — so
        // gating the lane on one gave a tenant without a door no lane at all,
        // including for the bundle sitting next to it in the container.
        cloud.jengu.dbo.sync.Lanes replication = new cloud.jengu.dbo.sync.Lanes(
                runStores.get(spec.code()), laneFeed, laneRuns, spec.code(),
                // The trail replicates through the audit refusal's one
                // admission (§7.8); the engine below the policy wrapper is
                // what everything else on this lane writes through.
                engine instanceof cloud.jengu.dbo.core.api.AuditReplay admitted ? admitted : null,
                // The second bound: declarations by type, from the tenant's
                // content feeds. What the lane admits by type is every type
                // the tenant declared except the ones about a person — those
                // travel by work or not at all. Both feeds, because a declared
                // set spans them: a profile or a value set a tenant authors is
                // on the one its face moves on, and an observation is not.
                new PgChangeFeed(db.dataSource(), version.domain()),
                new PgChangeFeed(db.dataSource(), cloud.jengu.dbo.core.api.Domains.DEFINITIONS),
                declarationTypes(spec));
        TenantRuntime runtime = new TenantRuntime(spec, engine, store,
                new PgChangeFeed(db.dataSource(), version.domain()),
                new PgChangeFeed(db.dataSource(), cloud.jengu.dbo.core.api.Domains.DEFINITIONS),
                withAuditSurface(withPolicyNote(new FhirHttpServer(sharedServer, store,
                        terminology, "/t/" + spec.code() + "/fhir", guard), spec),
                        version.face(), engine),
                terminology, replication);
        // Staged, not published: what is mounted has to be reachable so a
        // bring-up that throws can be taken back down, and a runtime here is
        // nobody's upstream — a dependent that resolved one mid-wire would
        // call feed() on a tenant whose own streams do not exist yet.
        mounting.put(spec.code(), runtime);
        // Applying what somebody declares for this tenant, and — where this is
        // the tenant that holds them — asking the deployment to apply the
        // declarations it reads itself. Behind the authority like every other
        // private surface: a tenant with none has no way to say who is asking.
        if (authority != null) {
            String configurationPath = "/t/" + spec.code() + "/configuration";
            sharedServer.createContext(configurationPath, new ConfigurationHandler(authority,
                    () -> spec.code().equals(managementCode) ? applyDeclarations() : null,
                    (correlation, declarations) -> new cloud.jengu.dbo.sync.ConfigApplication(
                            runStores.get(spec.code()),
                            new cloud.jengu.dbo.work.Runs(runStores.get(spec.code())),
                            version.domain())
                            .apply(spec.code(), correlation, declarations)));
            configurationContexts.put(spec.code(), configurationPath);
        }
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
                            (cloud.jengu.dbo.policy.PolicyObjectStore) runtime.engine()),
                    // a reshape writes through the tenant's own engine, so it
                    // is audited, policy-guarded and re-stamped exactly like
                    // any other write
                    runtime.engine(), runtime.store()));
            maintenanceContexts.put(spec.code(), adminPath);
            // Asking for a person's erasure. Its own door and its own
            // scope, beside maintenance rather than inside it: archiving and
            // reshaping are things done to the store, and an erasure is an act
            // performed for somebody that has to leave a run behind. The run
            // is the answer this returns.
            cloud.jengu.dbo.pdi.PersonVault vault = vaults.get(spec.code());
            if (vault != null) {
                String erasurePath = "/t/" + spec.code() + "/erasure";
                cloud.jengu.dbo.work.Runs erasureRuns =
                        new cloud.jengu.dbo.work.Runs(runStores.get(spec.code()));
                erasureContexts.put(spec.code(), erasurePath);
                sharedServer.createContext(erasurePath, new ErasureHandler(authority,
                        new PersonErasure(vault, erasureRuns),
                        // A reference names a RECORD and an erasure destroys a
                        // PERSON, and they are not the same thing: a human held
                        // as a Person and a Patient is spoken about by two
                        // records and has one key. So the reference is resolved
                        // through the vault, and erasing by either record
                        // reaches the whole human — which is what the promise
                        // says happens and, while a person was a row, did not.
                        reference -> {
                            int slash = reference.lastIndexOf('/');
                            if (slash < 0 || slash == reference.length() - 1) {
                                return java.util.Optional.<String>empty();
                            }
                            return vault.personOf(reference.substring(0, slash),
                                    reference.substring(slash + 1));
                        }));
            }
            // Identification. Beside erasure rather than inside maintenance,
            // and for the same reason erasure is: identifying somebody is an
            // act performed for a person, not something done to the store.
            //
            // Mounted for a tenant that declares a type to hold identities.
            // Without one there is nothing to resolve against, and a door
            // answering "no candidates" for every claim would be a surface
            // reporting emptiness as an answer.
            String identityType = identityTypeOf(spec);
            if (identityType != null) {
                String identityPath = "/t/" + spec.code() + "/identity";
                sharedServer.createContext(identityPath, new IdentityHandler(
                        authority, runtime.engine(), identityPath, identityType));
                identityContexts.put(spec.code(), identityPath);
            }
            // The participation surface: where a host that is NOT the
            // container obtains a lane. An appliance running dbo in-JVM builds
            // its own over its own store and never comes here; a cloud, whose
            // dbo is a separate deployment precisely so the application holds
            // no CREATE DATABASE, has no Runs to build one from and is at the
            // same time the side that serves work. So the lane is mounted
            // beside the tenant's other private surfaces, and what it offers
            // is the twelve verbs and nothing wider — the surface moves, the
            // primitive does not.
            //
            // Guarded by the authority for the same reason maintenance is: a
            // lane acts on the tenant's work, and a tenant with no authority
            // has no way to say who is asking.
            String workPath = "/t/" + spec.code() + "/work";
            // The runs and the work feed come from the bring-up above, not
            // from a second construction here: these are the same objects the
            // tenant's own bookkeeping uses, and a lane is a view onto them
            // rather than a second copy of them.
            cloud.jengu.dbo.work.Declarations laneDeclarations =
                    new cloud.jengu.dbo.work.Declarations(runtime.engine(), laneFeed,
                            DECLARATION_PATIENCE);
            cloud.jengu.dbo.work.Introductions laneIntroductions =
                    new cloud.jengu.dbo.work.Introductions(runtime.engine(), steps);
            // What participants report behind them. Built once beside
            // the declarations, over the same engine: a trackable is one of
            // this tenant's records, and a second Trackables would be a
            // second view of one fleet.
            cloud.jengu.dbo.work.Trackables laneTrackables =
                    new cloud.jengu.dbo.work.Trackables(runtime.engine());
            // The lane built per asker, the same behind every door: the
            // participant is its feed cursor and the identity is what
            // claims, both from the request; the entitlement is from the
            // credential and never from the request.
            cloud.jengu.dbo.runner.http.LaneHandler.Lanes laneFactory =
                    (participant, identity, entitlement) ->
                            cloud.jengu.dbo.runner.Lane.inProcess(spec.code(), laneRuns,
                                    laneFeed, laneDeclarations, participant, identity,
                                    runtime.engine(), laneIntroductions, entitlement,
                                    laneTrackables,
                                    // A hop is a travel entry about the TASK,
                                    // through the same contributed-event path
                                    // an application uses — actor and time
                                    // stamped by the machinery, never by the
                                    // lane.
                                    new cloud.jengu.dbo.runner.Lane.Trail() {
                                        @Override
                                        public void handedTo(cloud.jengu.dbo.work.Run run,
                                                String to, cloud.jengu.dbo.work.RunChain.Link link) {
                                            underRun(run, () -> engine.recordCustom("travel",
                                                    cloud.jengu.dbo.work.WorkModel.TYPE, run.id(),
                                                    java.util.Map.of("to", to, "key", run.key(),
                                                            "previous", link.previous(),
                                                            "link", link.link())));
                                        }

                                        // The chain as the trail holds it:
                                        // every travel and access entry of
                                        // the run, each carrying its link.
                                        @Override
                                        public java.util.List<cloud.jengu.dbo.work.RunChain.Link>
                                                links(cloud.jengu.dbo.work.Run run) {
                                            return TrailLinks.of(engine, run);
                                        }

                                        // An opening is an access entry on the
                                        // DOCUMENT with the run as its occasion,
                                        // beside every other reading of it —
                                        // the same join the run-occasioned read
                                        // carries, so "who read this" and
                                        // "what did this task open" meet on it.
                                        @Override
                                        public void opened(cloud.jengu.dbo.work.Run run,
                                                String by, String typeName, String id,
                                                cloud.jengu.dbo.work.RunChain.Link link) {
                                            java.util.Map<String, String> detail =
                                                    new java.util.LinkedHashMap<>();
                                            detail.put("by", by);
                                            detail.put("run", run.key());
                                            detail.put("previous", link.previous());
                                            detail.put("link", link.link());
                                            if (link.signature() != null) {
                                                detail.put("signature", link.signature());
                                            }
                                            underRun(run, () -> engine.recordCustom("access",
                                                    typeName, id, detail));
                                        }

                                        private void underRun(cloud.jengu.dbo.work.Run run,
                                                Runnable write) {
                                            String outer = cloud.jengu.dbo.core.api.Caller.run();
                                            cloud.jengu.dbo.core.api.Caller.setRun(run.key());
                                            try {
                                                write.run();
                                            } finally {
                                                if (outer == null) {
                                                    cloud.jengu.dbo.core.api.Caller.clearRun();
                                                } else {
                                                    cloud.jengu.dbo.core.api.Caller.setRun(outer);
                                                }
                                            }
                                        }
                                    },
                                    // The enrolment keys, from the same
                                    // authority that validated the token: what
                                    // a participant holds is what it is sealed to.
                                    new cloud.jengu.dbo.runner.Lane.Keys() {
                                        @Override
                                        public java.util.Optional<cloud.jengu.dbo.core.api.seal.ParticipantKey>
                                                of(String participant) {
                                            return authority.participantKey(participant);
                                        }

                                        @Override
                                        public java.util.Optional<cloud.jengu.dbo.core.api.seal.SigningKey>
                                                signing(String participant) {
                                            return authority.signingKey(participant);
                                        }
                                    });
            if (substrate != null) {
                // The same lane on the store's own stream: a door per tenant
                // on the substrate, guarded by the same authority and the
                // same participation scope, for a service that connects to
                // the substrate and to nothing else. Opened before the HTTP
                // door so a door that fails to open leaves nothing mounted
                // that a retry would trip over.
                WorkGrants grants = new WorkGrants(authority);
                doors.put(spec.code(), new cloud.jengu.dbo.stream.StreamDoor(substrate,
                        spec.code(), grants, laneFactory));
            }
            if (spec.managedBy() != null) {
                // The relation, made true at the door: the partner's own
                // authority is trusted here because this tenant declared it,
                // and its tokens arrive as the partner audience. The keys
                // come from the partner's runtime when it is served beside
                // this one, and from its published JWKS when it is not.
                String partner = spec.managedBy();
                String partnerIssuer = partnerIssuer(authority.issuer(), spec.code(), partner);
                authority.trust(partnerIssuer, () -> {
                    cloud.jengu.dbo.auth.TenantAuthority local = authorities.get(partner);
                    if (local != null) {
                        return local.jwksJson();
                    }
                    try {
                        return java.net.http.HttpClient.newHttpClient().send(
                                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                        partnerIssuer + "/.well-known/jwks.json")).build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                    } catch (java.io.IOException | InterruptedException unreachable) {
                        throw new IllegalStateException(spec.code() + ": the partner '"
                                + partner + "' published no keys at " + partnerIssuer,
                                unreachable);
                    }
                }, partner);
            }
            sharedServer.createContext(workPath, new cloud.jengu.dbo.runner.http.LaneHandler(
                    workPath, new WorkGrants(authority), laneFactory));
            workContexts.put(spec.code(), workPath);
            // What this tenant knows about the things behind its
            // participants. Beside replication rather than as a verb on the
            // lane: a lane is what one participant may do, and an operator
            // asking what state a fleet is in is not a participant act.
            String fleetPath = "/t/" + spec.code() + "/fleet";
            sharedServer.createContext(fleetPath,
                    new FleetHandler(authority, laneTrackables,
                            new cloud.jengu.dbo.work.Runs(runtime.engine()), fleetPath));
            fleetContexts.put(spec.code(), fleetPath);
            // The replication surface: the same asymmetry one layer up.
            // Declarations flow cloud → appliance, so the cloud is the side
            // that must PRODUCE outbound batches, and it is the side that
            // cannot hold a Lanes — pull-not-push does not move that, because
            // whoever pulls, the cloud still builds the batch.
            //
            // The door serves the tenant's lane; it does not own one. The
            // lane's bookkeeping is records in this tenant's store, so a
            // second Lanes over the same store would be a second set of
            // cursors for one peer — which is why the door and the framework's
            // own consumers are handed the same object.
            String replicationPath = "/t/" + spec.code() + "/replication";
            sharedServer.createContext(replicationPath, new cloud.jengu.dbo.sync.http.LanesHandler(
                    replicationPath, new ReplicationGrants(authority),
                    runtime::replication));
            replicationContexts.put(spec.code(), replicationPath);
        }
        wireDependencies(spec, runtime, db.dataSource());
        readyOnItsFace(spec, runtime);
        if (spec.faceRoot()) {
            // Filled before it is published: a dependent that wires against
            // an empty root would stream nothing and serve with no definitions
            // to validate against.
            //
            // From an image when there is one for this release, because a root
            // reading the packages through is the same half minute every root
            // on this face has already spent. The vocabulary a root would put
            // in its own tables on the way past is in the image too — it lives
            // in the schema the image is of — so nothing is left to replay.
            faceRootTakesItsFace(spec, engine, store, runtime, db.dataSource());
        }
        // The engine's own vocabularies land after the face's definitions,
        // because publishing one validates it, and validating needs the
        // view — which is built from the definitions a root has just loaded
        // or a subscriber has just drained. Published before them, the view
        // was built from the carried packages instead, at every mount.
        long vocabularyAt = System.currentTimeMillis();
        publishVocabularies(spec.code(), engine, store, terminology, version);
        LOG.info("tenant bring-up cost: code={} facade={}ms vocabulary={}ms",
                spec.code(), facadeMillis, System.currentTimeMillis() - vocabularyAt);
        // Published only now: wired, mounted, and safe to be somebody's
        // upstream.
        runtimes.put(spec.code(), runtime);
        mounting.remove(spec.code());
        listener.tenantUp(runtime);
    }

    /**
     * The type this tenant keeps identities on, or null if it keeps none.
     *
     * <p>Read from the spec rather than assumed, because assuming it would be
     * the wiring knowing a domain: {@code Person} is the FHIR face's answer and
     * another face's would be a different word. A tenant serving neither has no
     * identification surface, which is a legitimate deployment rather than a
     * gap — a store of gadgets identifies nobody.
     */
    private static String identityTypeOf(TenantSpec spec) {
        for (cloud.jengu.dbo.fhir.common.FhirTypeConfig type : spec.types()) {
            if ("Person".equals(type.typeName())) {
                return type.typeName();
            }
        }
        return null;
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
            javax.sql.DataSource on) {
        if (spec.dependencies().isEmpty()) {
            // Nothing to wire, and nothing to clear: a tenant whose streams
            // have to go is one being taken down or rebuilt, and both go
            // through the teardown that removes them. A second place that
            // cleared them would be a second place to keep in agreement.
            return;
        }
        FhirVersion version = versions.require(spec.face());
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
            java.util.Set<String> definitions = new java.util.LinkedHashSet<>();
            java.util.Set<String> records = new java.util.LinkedHashSet<>();
            for (String type : dependency.types()) {
                (cloud.jengu.dbo.fhir.common.FaceDefinitions.isDefinition(type)
                        ? definitions : records).add(type);
            }
            if (!records.isEmpty()) {
                engines.add(withRuns(spec, new cloud.jengu.dbo.sync.ContentSyncEngine(
                        new cloud.jengu.dbo.sync.ContentDependency(
                                dependency.name(), java.util.Set.copyOf(records)),
                        upstream.feed(), runtime.engine(), on,
                        domain, payloadVersion,
                        java.util.List.of(new cloud.jengu.dbo.fhir.r5.R4ToR5Converter()),
                        // The name it has always had: renaming a consumer
                        // restarts it at the head of a feed it was halfway
                        // through.
                        "sync." + dependency.name() + "." + spec.code(),
                        // the upstream reassembles from ITS concepts; this
                        // tenant takes the result apart into its own
                        upstream.grain(), runtime.grain())));
            }
            if (!definitions.isEmpty()) {
                engines.add(withRuns(spec, new cloud.jengu.dbo.sync.ContentSyncEngine(
                        new cloud.jengu.dbo.sync.ContentDependency(
                                dependency.name(), java.util.Set.copyOf(definitions)),
                        upstream.definitionsFeed(), runtime.engine(), on,
                        // Its bookkeeping belongs beside the rows it is about,
                        // so what a face gave this tenant — the records, their
                        // origins and the cursor — is one schema and moves as
                        // one thing.
                        cloud.jengu.dbo.core.api.Domains.DEFINITIONS, payloadVersion,
                        java.util.List.of(new cloud.jengu.dbo.fhir.r5.R4ToR5Converter()),
                        "sync." + dependency.name() + "." + spec.code() + ".definitions",
                        upstream.grain(), runtime.grain())));
            }
        }
        syncEngines.put(spec.code(), java.util.List.copyOf(engines));
    }

    /** What a tenant must have received from its face before it may serve. */
    static final java.util.Set<String> CRITICAL_ON_THE_FACE =
            java.util.Set.of("StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem");

    /**
     * A tenant that subscribes to a face is not served until the definitions
     * it validates against have arrived.
     *
     * <p>Readiness on a zone is the existing rule — an upstream that is not
     * up stops bring-up until it is. A face adds what must have STREAMED:
     * a tenant served with search parameters in and structures still on the
     * way would validate against nothing and say so about every resource.
     * So the face chain is drained here, before the tenant is published,
     * rather than left to the reconciler's rounds — and the view rebuilt
     * once, because it was built before these records existed.
     *
     * <p>The chain itself is checked before it is drained: its upstream is a
     * root, and a root of this tenant's own face. A face chain never
     * converts — same version both ends, by construction — so a tenant on
     * one version subscribed to a root of another is refused as a
     * declaration disagreeing with itself.
     */
    private void readyOnItsFace(TenantSpec spec, TenantRuntime runtime) {
        java.util.Optional<TenantSpec.Dependency> face = spec.dependencies().stream()
                .filter(TenantSpec.Dependency::face).findFirst();
        if (face.isEmpty()) {
            return;
        }
        TenantRuntime root = runtimes.get(face.get().name());
        if (!root.spec().faceRoot()) {
            throw new IllegalStateException(spec.code() + ": '" + face.get().name()
                    + "' is declared as the face chain and is not a face root");
        }
        if (!root.spec().face().equals(spec.face())) {
            throw new IllegalStateException(spec.code() + " is face '" + spec.face()
                    + "' and subscribes to '" + face.get().name() + "', a root of face '"
                    + root.spec().face() + "' — a face chain does not convert");
        }
        for (String critical : CRITICAL_ON_THE_FACE) {
            if (!face.get().types().contains(critical)) {
                throw new IllegalStateException(spec.code() + ": the face chain does not carry "
                        + critical + ", and a tenant cannot serve without it");
            }
        }
        // However many streams carry it. A dependency's types are split by the
        // feed that carries them, and a face's are its definitions — so the
        // chain is usually one stream over the definitions feed, and is two
        // the moment a root also publishes something that is not a definition.
        String name = "sync." + face.get().name() + "." + spec.code();
        java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine> chain = syncEngines
                .getOrDefault(spec.code(), java.util.List.of()).stream()
                .filter(engine -> engine.name().equals(name)
                        || engine.name().startsWith(name + "."))
                .toList();
        if (chain.isEmpty()) {
            throw new IllegalStateException(spec.code()
                    + ": the face chain was declared and not wired");
        }
        long began = System.currentTimeMillis();
        // The bytes first, when there are any to be had. What the streams do
        // afterwards is the same thing either way — read what the face has
        // published since they last looked — and after an image that is
        // whatever was published since it was cut.
        FaceBringUp.Outcome image = FaceBringUp.from(faceImages, spec.face(),
                tenantDataSources.get(spec.code()), root,
                name + ".definitions", face.get().name(), face.get().types());
        if (!image.fromImage() && image.worthCutting()) {
            // The first tenant to want this face cuts it, and pays a few
            // seconds so that it and everyone after it need not pay fifty.
            cutTheFaceFor(spec, face.get().name(), image);
            image = FaceBringUp.from(faceImages, spec.face(),
                    tenantDataSources.get(spec.code()), root,
                    name + ".definitions", face.get().name(), face.get().types());
        }
        if (image.fromImage()) {
            caughtUpOnItsOwnFace(runtime);
            LOG.info("tenant {} {}", spec.code(), image.said());
        } else {
            LOG.info("tenant {} is reading its face through the chain: {}",
                    spec.code(), image.said());
        }
        int carried = 0;
        for (cloud.jengu.dbo.sync.ContentSyncEngine stream : chain) {
            int events;
            do {
                events = stream.syncOnce(500);
                carried += events;
            } while (events > 0);
        }
        runtime.store().shapesChanged();
        LOG.info("tenant {} took its face from {}: image={} events={} in {}ms", spec.code(),
                face.get().name(), image.fromImage(), carried,
                System.currentTimeMillis() - began);
    }

    /**
     * The stream, recording what it does in the dependent tenant's own store
     * — so a parked shadow is a card somebody can see rather than a row
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
     * still be live.
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
     * arrived without this facade's knowledge.
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
            if (closing) {
                // The feed keeps its place, so what is left is read on the
                // next node's first round rather than through a pool this one
                // is closing.
                break;
            }
            String consumer = shapesConsumer(runtime.spec().code());
            try {
                boolean moved = false;
                boolean searchMoved = false;
                cloud.jengu.dbo.core.api.feed.FeedChunk<
                        cloud.jengu.dbo.core.api.feed.FeedItem> chunk;
                // Drained rather than sampled: the interesting item is not
                // necessarily in the first chunk of a busy tenant, and leaving
                // it behind would defer the rebuild by a round each time.
                while (!closing
                        && !(chunk = runtime.definitionsFeed().readFor(consumer, 500))
                                .items().isEmpty()) {
                    for (cloud.jengu.dbo.core.api.feed.FeedItem item : chunk.items()) {
                        moved |= "StructureDefinition".equals(item.typeName());
                        searchMoved |= "SearchParameter".equals(item.typeName());
                    }
                    runtime.definitionsFeed().ack(consumer, chunk.nextCursor());
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
                // The same watch, for the same reason: a search parameter
                // reaches a tenant by paths its facade cannot see — replicated
                // from a zone, restored from an archive, applied by a lane —
                // and watching the store covers the ones nobody has thought of
                // yet. What it costs is different, so it is said differently:
                // a reindex is work, and a round that quietly spent four
                // minutes on one is a deployment nobody can account for.
                if (searchMoved) {
                    long began = System.currentTimeMillis();
                    int reindexed = runtime.store().searchParametersChanged();
                    if (reindexed > 0) {
                        rebuilt++;
                    }
                    LOG.info("tenant {} honoured a search parameter change: reindexed={} in {}ms",
                            runtime.spec().code(), reindexed,
                            System.currentTimeMillis() - began);
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
        java.util.List<cloud.jengu.dbo.sync.ContentSyncEngine> streams =
                new java.util.ArrayList<>();
        syncEngines.values().forEach(streams::addAll);
        java.util.concurrent.atomic.AtomicInteger seen =
                new java.util.concurrent.atomic.AtomicInteger();
        // Several at once, and each drained before it gives way. Drained is
        // the right unit — the interesting item is not necessarily in the
        // first chunk — but drained ONE STREAM AT A TIME meant a tenant
        // catching up with a large dependency held every other stream behind
        // it, and, while this shared a thread with the scan, every tenant
        // still coming up as well.
        together(streams, streamsTogether, engine -> {
            if (closing) {
                return;
            }
            try {
                long began = System.currentTimeMillis();
                int carried = 0;
                int events;
                do {
                    events = engine.syncOnce(500);
                    carried += events;
                    seen.addAndGet(events);
                    // A backlog is drained in one round, unless the node is
                    // going down — then the chunk in hand is the last one. The
                    // rest is still on the feed at the acked cursor, which is
                    // what the cursor is for; carrying on would only mean
                    // reading through a pool somebody is closing.
                } while (events > 0 && !closing);
                // one recorded round: re-attempt what is parked and say
                // what is left, which is the part a person can act on
                engine.pass(500);
                if (carried > 0) {
                    // Only when it carried something, so this is an account of
                    // work rather than a heartbeat: a line every couple of
                    // seconds saying nothing moved is what people filter out,
                    // including on the round that mattered. What it answers is
                    // the question nobody could answer before — which stream,
                    // how much, how long — because a CodeSystem is rebuilt
                    // from its source's concepts, converted, and taken apart
                    // again into the destination's, and none of that was
                    // visible from outside.
                    LOG.info("stream {} carried events={} in {}ms", engine.name(), carried,
                            System.currentTimeMillis() - began);
                }
            } catch (RuntimeException e) {
                // one stream's failure never blocks the others; the
                // next round retries from the acked cursor
                LOG.warn("sync round failed for one stream; retrying from the "
                        + "acked cursor next round", e);
            }
        });
        return seen.get();
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
        //.
        cloud.jengu.dbo.pdi.PersonVault vault =
                new cloud.jengu.dbo.pdi.PersonVault(db.dataSource(), authorityConfig.kek());
        vaults.put(spec.code(), vault);
        return new cloud.jengu.dbo.pdi.PdiObjectStore(inner, vault,
                pdiSpec,
                face.capability(cloud.jengu.dbo.core.face.Coarsening.class)
                        .orElse(cloud.jengu.dbo.core.face.Coarsening.NONE),
                // Taken from the registrations BEFORE the transform: after it
                // every person type reads INTERNAL with no systems, and the
                // membrane enforcing identity would have nothing to enforce
                // but a guess.
                cloud.jengu.dbo.pdi.PdiSetup.identifiedBy(registrations, pdiSpec));
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
        // queryable and versioned and dropped with it.
        all.addAll(cloud.jengu.dbo.work.WorkModel.registrations());
        // What this deployment was told to serve. Registered for every
        // tenant because which one manages the others is decided after
        // they are built, and a type registered for the wrong tenant is a
        // surface that answers nothing.
        all.addAll(TenantDeclarationModel.registrations());
        // And the replication lane's own bookkeeping: where each peer
        // has reached, and what arrived for which work. Same argument again —
        // a lane's state is this tenant's, dropped when the tenant is. Without
        // these the surface comes up and the first verb fails on a type
        // nobody registered, which is a tenant that looks served and is not.
        all.addAll(cloud.jengu.dbo.sync.LaneModel.registrations());
        all.addAll(cloud.jengu.dbo.sync.PlacementModel.registrations());
        // And what a connected worker reports about the things behind it
        //. Same argument a third time: a lane's state, a placement and
        // a trackable are all this tenant's records, dropped when it is.
        all.addAll(cloud.jengu.dbo.work.TrackableModel.registrations());
        // And what this tenant decided about who somebody is. Same argument
        // once more: an adjudication, a binding and an anonymity declaration
        // are records about the tenant's own people, so they belong in the
        // tenant's own store rather than in the authority's — which is where
        // the whole identity model had been registered, leaving a door that
        // could be mounted, guarded and correct while the first verb failed on
        // a type nobody had registered.
        all.addAll(cloud.jengu.dbo.auth.IdentityModel.identificationRegistrations());
        // Checked rather than assumed: a database this store did not
        // provision was pinned by nobody, and a managed server may let this
        // role read the setting and not change it. An isolated tenant does
        // not come up on a database that would write its people to a log;
        // any other is told, once, at bring-up.
        LogDiscipline.leak(db.dataSource()).ifPresent(why -> {
            if (spec.pdi()) {
                throw new IllegalStateException(spec.code() + ": its database would log "
                        + "personal data in the clear — " + why + ". Pin "
                        + "log_parameter_max_length and log_parameter_max_length_on_error "
                        + "to 0 on it before bringing an isolated tenant up.");
            }
            LOG.warn("tenant {}: its database would log statement parameters, and a slow "
                    + "write carries identifying data — {}", spec.code(), why);
        });
        ObjectStore engine = pdiWrapped(spec, db, all, face);
        // Runs go to the engine rather than through the policy decorator, and
        // everything that records them for this tenant uses the same one.
        runStores.put(spec.code(), engine);
        cloud.jengu.dbo.policy.PolicyObjectStore policyStore =
                new cloud.jengu.dbo.policy.PolicyObjectStore(engine, policiesOf(spec));
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

    private FhirHttpServer withAuditSurface(FhirHttpServer server,
            cloud.jengu.dbo.core.face.DomainFace face, ObjectStore engine) {
        if (engine instanceof cloud.jengu.dbo.policy.PolicyObjectStore policyStore) {
            // the face renders it; the surface only decides which records
            // answer the query and what a posted document records
            server.auditSurface = new cloud.jengu.dbo.rest.AuditProjection(
                    policyStore, policyStore, face);
        }
        if (face.capability(cloud.jengu.dbo.core.face.RecordProjection.class).isPresent()) {
            // The run's face: authored on the store surface under the
            // tenant's write authority, against the composed step catalogue
            // — installed modules plus what participants introduced here.
            cloud.jengu.dbo.core.process.Steps composed =
                    new cloud.jengu.dbo.work.Introductions(engine, steps).composedWith();
            server.workSurface = new WorkProjection(engine,
                    new cloud.jengu.dbo.work.Runs(engine, composed), composed, face);
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
        // Not computeIfAbsent: building a hub reads a store, fetches secrets
        // and mounts a context, and doing that inside a mapping function holds
        // the map's bin for its whole duration. Sequentially that is invisible.
        // With several tenants coming up at once it serialises every tenant of
        // one zone behind the first — which is exactly the deployment shape
        // that has two dozen of them — and a mapping function that reaches
        // back into the same map would deadlock outright.
        cloud.jengu.dbo.auth.IdentityHub known = zoneHubs.get(spec.zone());
        if (known != null) {
            return known;
        }
        synchronized (zoneHubs) {
            cloud.jengu.dbo.auth.IdentityHub sinceWeWaited = zoneHubs.get(spec.zone());
            if (sinceWeWaited != null) {
                return sinceWeWaited;
            }
            cloud.jengu.dbo.auth.IdentityHub built = buildZoneHub(spec);
            zoneHubs.put(spec.zone(), built);
            return built;
        }
    }

    private cloud.jengu.dbo.auth.IdentityHub buildZoneHub(TenantSpec spec) {
        return ((java.util.function.Function<String, cloud.jengu.dbo.auth.IdentityHub>) zone -> {
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
        }).apply(spec.zone());
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
        // object alive for a tenant nobody can reach.
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
        redeclared.remove(code);
        unmount(code, runtime);
    }

    /**
     * Everything a bring-up mounted, removed — the surfaces, the door, the
     * pool. Shared by retraction and by a bring-up that threw half-way, which
     * is the point: an inline second list of removals is how erasure came to
     * be left mounted after its tenant was gone.
     *
     * <p>It says nothing and records nothing. Whether this is a retraction
     * somebody has to know about or a failure being cleaned up after is the
     * caller's to say.
     */
    private void unmount(String code, TenantRuntime runtime) {
        if (runtime != null) {
            runtime.endpoint().close();
        }
        sweeps.remove(code);
        syncEngines.remove(code);
        String adminPath = maintenanceContexts.remove(code);
        if (adminPath != null) {
            sharedServer.removeContext(adminPath);
        }
        String configurationPath = configurationContexts.remove(code);
        if (configurationPath != null) {
            sharedServer.removeContext(configurationPath);
        }
        String workPath = workContexts.remove(code);
        if (workPath != null) {
            sharedServer.removeContext(workPath);
        }
        cloud.jengu.dbo.stream.StreamDoor door = doors.remove(code);
        if (door != null) {
            door.close();
        }
        String replicationPath = replicationContexts.remove(code);
        if (replicationPath != null) {
            sharedServer.removeContext(replicationPath);
        }
        String scimPath = scimContexts.remove(code);
        if (scimPath != null) {
            sharedServer.removeContext(scimPath);
        }
        // Identification and erasure go with the rest. A door left mounted
        // after its tenant is gone is a handle onto a closed pool that still
        // answers, and these two are the ones where that matters most: both
        // act on a person, and both would be answering for a tenant this
        // deployment no longer serves. Erasure had been left behind here.
        String identityPath = identityContexts.remove(code);
        if (identityPath != null) {
            sharedServer.removeContext(identityPath);
        }
        String erasurePath = erasureContexts.remove(code);
        if (erasurePath != null) {
            sharedServer.removeContext(erasurePath);
        }
        String fleetPath = fleetContexts.remove(code);
        if (fleetPath != null) {
            sharedServer.removeContext(fleetPath);
        }
        vaults.remove(code);
        String oidcPath = authorityContexts.remove(code);
        if (oidcPath != null) {
            sharedServer.removeContext(oidcPath);
        }
        provisioner.release(code);
    }

    // --------------------------------------------------- management history

    /** Tenant management as a process: observe, serve, retract, erase. */
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
     * What this deployment has been told to serve, applied into the managing
     * tenant as records.
     *
     * <p>A declaration was a file on a node's disk and nothing else, so the
     * question "what is this deployment declared to serve" could only be
     * answered by somebody with a shell on the node — and never "what did it
     * say yesterday". The answer belongs where every other answer about this
     * deployment lives.
     *
     * <p>It is the ordinary applier over the ordinary source: an unchanged
     * directory is a read, and a spec that will not parse is a card naming the
     * file rather than a line in a boot log that scrolled past.
     *
     * <p>The serving sweep's rule, kept: a deployment with no managing tenant
     * records nothing and serves exactly as before. Recording what is declared
     * is not a condition of honouring it.
     */
    private cloud.jengu.dbo.sync.ConfigApplication.Outcome recordDeclarations() {
        if (managementCode == null) {
            return new cloud.jengu.dbo.sync.ConfigApplication.Outcome(0, 0, 0, 0);
        }
        ObjectStore management = runStores.get(managementCode);
        if (management == null) {
            return new cloud.jengu.dbo.sync.ConfigApplication.Outcome(0, 0, 0, 0);
        }
        try {
            cloud.jengu.dbo.sync.ConfigApplication applied =
                    new cloud.jengu.dbo.sync.ConfigApplication(management,
                            new cloud.jengu.dbo.work.Runs(management),
                            cloud.jengu.dbo.work.WorkModel.DOMAIN);
            cloud.jengu.dbo.sync.ConfigApplication.Outcome outcome = applied.applyFrom(
                    "deployment", new cloud.jengu.dbo.sync.DirectoryConfigSource(
                            directory, TenantDeclarationModel.TYPE, ".json"),
                    declarationsOf(applied, management));
            trouble.remove(SOURCE_TROUBLE);
            return outcome;
        } catch (RuntimeException e) {
            // Same rule as the sweep below: the deployment keeps serving
            // tenants when its own bookkeeping cannot be written. What it must
            // not do is pass quietly — a source nobody can read is the state
            // in which the records stop moving, and an operator reading a
            // ledger that says nothing would conclude they are current.
            trouble.put(SOURCE_TROUBLE, new Trouble(
                    cloud.jengu.dbo.work.Failure.TRANSIENT, String.valueOf(e)));
            LOG.warn("the declarations could not be read; the tenants already declared are "
                    + "unaffected and the records stand as they were", e);
            throw e;
        }
    }

    /**
     * Applies what is declared, now, and reconciles what this node serves
     * against it — the whole of one pass, on the asker's thread rather than on
     * the next beat of the scan.
     *
     * <p>The same pass the deployment runs on its own. Somebody who has just
     * changed a declaration should not have to guess how long the beat is, and
     * somebody who has just fixed one wants to know whether the fix took.
     */
    public cloud.jengu.dbo.sync.ConfigApplication.Outcome applyDeclarations() {
        scanOnce();
        return lastApplication;
    }

    /** What the last application of the declarations did. */
    private volatile cloud.jengu.dbo.sync.ConfigApplication.Outcome lastApplication =
            new cloud.jengu.dbo.sync.ConfigApplication.Outcome(0, 0, 0, 0);

    /**
     * The ledger's name for "the declarations themselves could not be read".
     * Not a tenant's trouble: every tenant is fine, and what is stale is the
     * question of whether there should be more of them.
     */
    static final String SOURCE_TROUBLE = "source:declarations";

    /**
     * Applying a tenant declaration, and undoing one.
     *
     * <p>This is the one applier that can answer honestly what it holds: every
     * declaration record in the managing tenant's store arrived from the
     * declarations this deployment reads, so one the source has stopped naming
     * really has been withdrawn. A store applier answering the same question
     * about value sets would be counting records the tenant authored itself.
     *
     * <p>Undoing removes the record, and nothing else. Whether the tenant it
     * declared keeps serving is the sweep's decision, made from the
     * declarations themselves — because a record vanishing has one cause here
     * and would have several once anything else can write one.
     */
    private cloud.jengu.dbo.sync.ConfigApplication.Applier declarationsOf(
            cloud.jengu.dbo.sync.ConfigApplication application, ObjectStore management) {
        return new cloud.jengu.dbo.sync.ConfigApplication.Applier() {

            @Override
            public void apply(cloud.jengu.dbo.sync.ConfigApplication.Declared declared) {
                application.intoTheStore(declared);
            }

            @Override
            public java.util.List<cloud.jengu.dbo.core.api.Identifier> held(String scope) {
                java.util.List<cloud.jengu.dbo.core.api.Identifier> names =
                        new java.util.ArrayList<>();
                for (cloud.jengu.dbo.core.api.StoredObject record : management.select(
                        cloud.jengu.dbo.core.api.Criteria.of(TenantDeclarationModel.TYPE))) {
                    names.add(TenantDeclarationModel.of(TenantSpec.parse(new String(
                            record.payload(), java.nio.charset.StandardCharsets.UTF_8)).code()));
                }
                return names;
            }

            @Override
            public void withdraw(String scope, cloud.jengu.dbo.core.api.Identifier identity) {
                for (cloud.jengu.dbo.core.api.StoredObject record : management.getByIdentifier(
                        TenantDeclarationModel.TYPE, java.util.List.of(identity))) {
                    management.delete(TenantDeclarationModel.TYPE, record.id(),
                            record.versionId(), cloud.jengu.dbo.core.api.Handling.Authority
                                    .CONFIG_LANE);
                }
            }
        };
    }

    /**
     * The serving sweep: one item per tenant that is not serving, in the
     * management tenant's own store.
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
     * happened instead.
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
     * Erasure: the tenant's data is removed.
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
        // Two loops, because they wait on different things and neither is the
        // other's business. Bringing a tenant up is somebody else's storage,
        // somebody else's secret, a schema; keeping a stream in step is a
        // backlog that can be an afternoon long. Sharing one thread made each
        // the other's queue: a tenant catching up delayed every bring-up in
        // the deployment, and a bring-up waiting on a secret stopped every
        // stream — both invisibly, because neither is anybody's failure.
        scanner = Thread.ofVirtual().name("dbo-tenant-scanner").start(() -> {
            while (running) {
                try {
                    scanOnce();
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    // the scan must outlive any single pass's failure —
                    // including Errors (see bring-up above)
                    LOG.warn("a scan failed; the deployment keeps scanning", e);
                }
            }
        });
        reconciler = Thread.ofVirtual().name("dbo-tenant-reconciler").start(() -> {
            while (running) {
                try {
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
                        // Rotation without this is not rotation.
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
        closing = true;
        running = false;
        // WAITED for, not merely asked to stop. An interrupt ends the sleep
        // between rounds and does nothing to a round already talking to a
        // database, so taking the tenants down straight afterwards closed the
        // pools underneath a round still reading through them — and a shutdown
        // that was working exactly as intended ended in a stack trace per
        // item, per tenant.
        //
        // Nothing was wrong beyond the noise, which is the reason to fix it:
        // a teardown that logs like a crash is where a real crash goes to hide.
        stopped(scanner);
        stopped(reconciler);
        for (String code : Set.copyOf(runtimes.keySet())) {
            takeDown(code, null);
        }
        sharedServer.stop(0);
    }

    /**
     * Ends a loop and waits for the round it is in, but not forever.
     *
     * <p>A round that will not end in a few seconds is abandoned, and the
     * pools go anyway: a shutdown that hangs is worse than a shutdown that
     * logs. The wait is what makes the ordinary case quiet, not a guarantee
     * about the extraordinary one.
     */
    private static void stopped(Thread loop) {
        if (loop == null) {
            return;
        }
        // ASKED, then waited for. Interrupting is what made the noise, and it
        // is worth stating because it is the opposite of what it looks like:
        // these loops are virtual threads, and interrupting a virtual thread
        // blocked on socket I/O closes the channel under it. So the interrupt
        // did not end a round early — it broke the connection the round was
        // reading through, mid-query, and the resulting I/O error was reported
        // per item, per tenant, as a pool failure at teardown.
        //
        // The round already knows to stop: it checks on the way round and the
        // feed keeps its place. So this waits for it to notice, and only
        // interrupts if it will not — a shutdown that hangs being worse than
        // one that logs.
        try {
            loop.join(java.time.Duration.ofSeconds(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (loop.isAlive()) {
            loop.interrupt();
        }
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
    /**
     * The face's own vocabulary, applied into the tenant as a recorded pass.
     *
     * <p>This is the empty-state case of applying configuration: a declared
     * set arrives from somewhere that declared it — here, the face itself —
     * and applying it closes when what is here agrees with what was declared.
     * It went through a loop whose whole outcome was a log line, so
     * <i>six declared, four applied, two refused and why</i> could be read
     * only by whoever was tailing the boot, and only then.
     *
     * <p>The writing stays this tenant's own: a CodeSystem has to arrive at
     * the grain concepts are kept in, or the store cannot answer
     * {@code $lookup} for its own definitions even though the document is
     * fetchable.
     */
    private void publishVocabularies(String code, cloud.jengu.dbo.core.api.ObjectStore engine,
            FhirStoreFacade store, FhirTerminology terminology, FhirVersion version) {
        cloud.jengu.dbo.core.face.DomainFace face = version.face();
        // A face with no terminology surface offers no operations — the
        // existing signal for "this face cannot hold concepts natively",
        // rather than a new flag or a caught exception.
        boolean holdsConceptsNatively = !terminology.operations().isEmpty();
        java.util.List<cloud.jengu.dbo.sync.ConfigApplication.Declared> declared =
                new java.util.ArrayList<>();
        for (String definition : face.capability(
                cloud.jengu.dbo.core.face.RecordProjection.class)
                .map(cloud.jengu.dbo.core.face.RecordProjection::vocabularies)
                .orElse(java.util.List.of())) {
            // Named by its canonical url: what somebody looking at the card
            // would search for, and what the definition is identified by.
            declared.add(new cloud.jengu.dbo.sync.ConfigApplication.Declared(
                    resourceTypeOf(definition), canonicalUrlOf(definition),
                    definition.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        cloud.jengu.dbo.sync.ConfigApplication.Applier applier = one ->
                publishOne(engine, store, terminology, holdsConceptsNatively, one);
        // The face is a source like any other: it declares a set, and the set
        // is its own version. What this tenant last agreed with is on its run,
        // so a tenant coming up against a face that has not moved reads one
        // record instead of asking after every definition it already holds.
        // Not complete, deliberately. The face declares what it publishes; it
        // says nothing about what else this tenant holds, and treating a
        // release that dropped a definition as a withdrawal would delete a
        // tenant's vocabulary because somebody rolled a face back.
        cloud.jengu.dbo.sync.ConfigSource source = () ->
                new cloud.jengu.dbo.sync.ConfigSource.Fetch(declared,
                        cloud.jengu.dbo.sync.ConfigSource.markerOf(declared), false);
        ObjectStore runStore = runStores.get(code);
        if (runStore == null) {
            // Nowhere to write the record. The definitions still land: a
            // tenant's vocabulary is not conditional on its bookkeeping.
            declared.forEach(applier::apply);
        } else {
            try {
                new cloud.jengu.dbo.sync.ConfigApplication(engine,
                        new cloud.jengu.dbo.work.Runs(runStore), version.domain())
                        .applyFrom(code, source, applier);
            } catch (RuntimeException unrecorded) {
                // Same rule as the serving sweep: the deployment keeps serving
                // tenants when its own bookkeeping cannot be written. Applying
                // is idempotent — a version already published is a read and
                // nothing else — so the second attempt costs a lookup each.
                LOG.warn("the face's vocabulary is being applied without a record", unrecorded);
                declared.forEach(applier::apply);
            }
        }
        if (!holdsConceptsNatively) {
            // Said out loud rather than left to be discovered: the definitions
            // are fetchable, and a client cannot resolve a code in them. The
            // face's terminology surface is the missing half.
            LOG.warn("face {} holds no terminology natively: its vocabularies are published "
                    + "as documents, so $lookup and $validate-code answer nothing for them",
                    face.name());
        }
    }

    /**
     * One definition, into the tenant. A throw is the pass's card; the one
     * refusal that is not a card is caught here.
     */
    private static void publishOne(cloud.jengu.dbo.core.api.ObjectStore engine,
            FhirStoreFacade store, FhirTerminology terminology, boolean holdsConceptsNatively,
            cloud.jengu.dbo.sync.ConfigApplication.Declared declaration) {
        String definition = new String(declaration.payload(),
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            // A CodeSystem goes through INGEST where the face can hold
            // concepts, exactly as a client's would: written the ordinary
            // way it is stored whole and answers nothing, so $lookup and
            // $validate-code cannot resolve dbo's own vocabulary even
            // though the document is fetchable. Ingest is
            // replace-all and cheap for these — six systems of a few
            // codes — so it runs every bring-up rather than being skipped,
            // which also repairs a tenant that stored one whole before.
            if (holdsConceptsNatively && "CodeSystem".equals(declaration.typeName())) {
                // Ingest is replace-all: the shell through the engine and
                // the concepts into the native form. Cheap for one and
                // ~500ms for the set, which is not a price to pay on every
                // boot for definitions that have not moved — so the stored
                // version decides, and it is derived from the vocabulary
                // rather than from dbo's release number.
                if (!publishedVersionOf(engine, definition).equals(fieldOf(definition,
                        "\"version\""))) {
                    terminology.ingestCodeSystem(definition);
                }
                return;
            }
            // Asked before written. A conditional create is a full
            // validate and a write every time, and a definition that has
            // not changed since the last boot needs neither — measured at
            // ~300ms per bring-up for work already done. The lookup
            // is on canonical identity, which is indexed, so a warm tenant
            // pays one read per definition and nothing else.
            if (!engine.getByIdentifier(declaration.typeName(),
                    java.util.List.of(new cloud.jengu.dbo.core.api.Identifier(
                            cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                            declaration.name())))
                    .isEmpty()) {
                return;
            }
            // Identity-keyed on the canonical url, so a race between two
            // bring-ups rewrites the same record rather than a second one
            // (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS).
            store.conditionalCreate(definition, java.util.Map.of("url", declaration.name()));
        } catch (cloud.jengu.dbo.core.api.HandlingRefusedException refused) {
            // Anticipated, not wrong, and deliberately not a card: on a tenant
            // whose CodeSystem is somebody else's publication, the engine's
            // own vocabulary arrives through the replication lane instead —
            // the source published the same six at ITS bring-up, and the
            // stream delivers them. A card here would open one on every zone
            // dependent's boot, for nobody to act on.
            LOG.info("the face's vocabulary is not this tenant's to write ({}); "
                    + "it arrives from the source tenant through the replication lane",
                    refused.getMessage());
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
        // layer reads everything else about a definition it was handed.
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
