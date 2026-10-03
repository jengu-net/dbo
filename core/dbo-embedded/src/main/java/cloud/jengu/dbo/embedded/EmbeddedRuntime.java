package cloud.jengu.dbo.embedded;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The store, running inside somebody else's application, invisibly.
 *
 * <p>It boots a framework, or installs into one the application owns, puts
 * the bundle set an assembly recorded into it, and takes that set out again.
 * Nothing it publishes names a {@code Bundle}, a {@code BundleContext} or a
 * {@code ServiceReference}, and an application author who does not want a
 * framework of their own never learns the word.
 *
 * <p><b>One framework, however many assemblies.</b> An application holding
 * the serving half and the performing half contributes two bundle lists and
 * gets one container. Two would each hold a copy of every bundle, and for the
 * element bundle that is a full set of parsed FHIR definitions — measured in
 * the container harness at roughly 100 to 215 MB per framework — held twice.
 *
 * <p><b>A framework the application owns stays the application's.</b> Its
 * owner created it with {@link DboFramework#properties()} and installs its own
 * bundles in its own order. The store installs and starts its own set into it,
 * and on close stops and uninstalls that set and nothing else; the framework
 * is never stopped by the party that did not create it.
 */
public final class EmbeddedRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.spring");

    private final DboFramework dbo;
    private final Path storage;
    private final Framework handed;

    private Framework framework;
    private Map<String, Bundle> installed;
    /** Bundles the store found already there and leaves there: an attached extension. */
    private java.util.Set<String> adopted = java.util.Set.of();
    private final List<org.osgi.framework.ServiceRegistration<?>> registered =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * @param loader     where the bundle jars and the indexes are read from,
     *                   which is the application's own classloader
     * @param properties what the activators read through
     *                   {@code BundleContext.getProperty} — the same names
     *                   the serving distribution sets as system properties,
     *                   produced from Spring configuration by whichever
     *                   assembly is in play
     * @param storage    where the framework keeps its bundle cache
     */
    public EmbeddedRuntime(ClassLoader loader, Map<String, String> properties, Path storage) {
        this(new DboFramework(loader, properties), storage);
    }

    /**
     * A container the store creates for itself.
     *
     * @param storage where the framework keeps its bundle cache
     */
    public EmbeddedRuntime(DboFramework dbo, Path storage) {
        this.dbo = dbo;
        this.storage = storage;
        this.handed = null;
    }

    /**
     * The store installed into a framework the application created and owns.
     *
     * <p>The framework must be started and created with
     * {@link DboFramework#properties()}. Its storage, its bundle cache and its
     * start levels stay its owner's: the store's own data lives where its
     * configuration says, never in the framework's storage area.
     */
    public EmbeddedRuntime(DboFramework dbo, Framework hostOwned) {
        this.dbo = dbo;
        this.storage = null;
        this.handed = java.util.Objects.requireNonNull(hostOwned, "hostOwned");
    }

    /** Whether the container is up with the store in it. */
    public synchronized boolean running() {
        return framework != null && installed != null && framework.getState() == Bundle.ACTIVE;
    }

    /**
     * Brings the store up: find, compute, construct or check, install, start.
     *
     * <p>Installed by stream, in the order the assemblies asked for, and
     * started afterwards rather than one by one — a bundle started before the
     * one it imports from resolves anyway, and starting in two passes keeps
     * the order a statement about installation rather than about wiring.
     */
    public synchronized void start() {
        if (framework != null) {
            return;
        }
        BundleSet set = dbo.bundles();
        Framework into = handed == null ? created() : checked(handed);
        this.framework = into;

        BundleContext ctx = into.getBundleContext();
        Map<String, Bundle> put = new LinkedHashMap<>();
        java.util.Set<String> attaching = new java.util.HashSet<>();
        java.util.Set<String> foundThere = new java.util.HashSet<>();
        for (BundleSet.Found bundle : set.inInstallOrder()) {
            if (bundle.attachesRatherThanRuns()) {
                attaching.add(bundle.symbolicName());
            }
            try {
                Bundle already = handed == null ? null : alreadyThere(ctx, bundle);
                if (already != null) {
                    put.put(bundle.symbolicName(), already);
                    foundThere.add(bundle.symbolicName());
                    continue;
                }
                try (InputStream bytes = bundle.open()) {
                    put.put(bundle.symbolicName(),
                            ctx.installBundle(bundle.at().toString(), bytes));
                }
            } catch (BundleException | IOException notInstalled) {
                this.installed = put;
                this.adopted = foundThere;
                takeDown();
                throw new IllegalStateException(bundle.symbolicName() + " could not be installed "
                        + "from " + bundle.at() + ", so the container is incomplete and the "
                        + "store has been taken out of it rather than left serving part of a "
                        + "store: " + rootOf(notInstalled), notInstalled);
            }
        }
        this.installed = put;
        this.adopted = foundThere;

        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, Bundle> each : put.entrySet()) {
            if (attaching.contains(each.getKey())) {
                // A fragment attaches to its host and has no lifecycle of its
                // own. The ServiceLoader mediator is an extension of the
                // SYSTEM bundle, so it attaches at install — which is why it
                // is first in the set, and why anything requiring the extender
                // resolves after it and not before.
                //
                // Defensive rather than proved: the specification says
                // starting a fragment throws, and the Felix this runs on
                // tolerates it. Removing this passes today and is one
                // framework version away from a container that will not come
                // up for a reason nobody would look for here.
                continue;
            }
            try {
                // Transient in somebody else's framework: its cache keeps the
                // store's bundles only while the store is in it, and a
                // framework restarted from that cache does not start them on
                // its own with whatever configuration it then has.
                each.getValue().start(handed == null ? 0 : Bundle.START_TRANSIENT);
            } catch (BundleException wouldNotStart) {
                // Collected rather than thrown at the first one. The first
                // refusal is usually a consequence — a bundle whose import
                // nothing satisfies because the bundle that exports it is the
                // one really at fault — and a message naming one of them
                // sends a reader to the wrong file.
                refused.add(each.getKey() + ": " + rootOf(wouldNotStart));
            }
        }
        if (!refused.isEmpty()) {
            takeDown();
            throw new IllegalStateException("the container did not come up. " + refused.size()
                    + " of " + put.size() + " bundles would not start:\n  "
                    + String.join("\n  ", refused));
        }
        if (handed != null) {
            List<String> wrong = new ArrayList<>(splitClassSpace(put));
            wrong.addAll(unresolvedNeighbours(put));
            if (!wrong.isEmpty()) {
                takeDown();
                throw new IllegalStateException("the container did not come up. The "
                        + "application's framework holds bundles that stop it working:\n  "
                        + String.join("\n  ", wrong));
            }
        }
        LOG.info("starting: component=dbo-embedded bundles={} framework={}", put.size(),
                handed == null ? "own storage=" + storage : "the application's");
    }

    /** A framework of the store's own, created and started. */
    private Framework created() {
        Map<String, String> config = new HashMap<>(dbo.properties());
        config.put("org.osgi.framework.storage", storage.toAbsolutePath().toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");

        Framework booting = ServiceLoader.load(FrameworkFactory.class, EmbeddedRuntime.class
                        .getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no OSGi FrameworkFactory on the classpath, so there is no container to "
                                + "start. The assemblies depend on one; something has excluded "
                                + "it."))
                .newFramework(config);
        try {
            booting.start();
        } catch (BundleException wouldNotStart) {
            throw new IllegalStateException("the container would not start, so this application "
                    + "serves nothing of the store", wouldNotStart);
        }
        return booting;
    }

    /**
     * The application's framework, once it is known to carry what the store needs.
     *
     * <p>Started by its owner, not here: starting a framework is choosing its
     * start level and its moment, and both are the owner's.
     */
    private Framework checked(Framework framework) {
        if (framework.getState() != Bundle.ACTIVE) {
            throw new IllegalStateException("the OSGi framework this application provides is not "
                    + "started, so the store cannot be installed into it. Start it where it is "
                    + "created.");
        }
        dbo.refuseWhatItDoesNotCarry(framework);
        return framework;
    }

    /**
     * A bundle of the set that the application's framework already holds.
     *
     * <p>An extension the system bundle already carries is taken as it is:
     * an extension cannot be detached from a running framework, so the one a
     * previous start of the store attached is still there, and installing it
     * twice is refused. The store's own bundle left behind by a JVM that
     * stopped without closing — same location — is replaced, because its
     * cache copy may be a build behind. Anything else with the same symbolic
     * name is the application's own copy, and two copies of one bundle in
     * one framework is a conflict the store does not settle by guessing.
     */
    private Bundle alreadyThere(BundleContext ctx, BundleSet.Found wanted)
            throws BundleException {
        for (Bundle there : ctx.getBundles()) {
            if (!wanted.symbolicName().equals(there.getSymbolicName())) {
                continue;
            }
            if (wanted.attachesRatherThanRuns() && isFragment(there)) {
                return there;
            }
            if (there.getLocation().equals(wanted.at().toString())) {
                there.uninstall();
                refresh(List.of(there));
                return null;
            }
            throw new BundleException("the application's framework already holds "
                    + there.getSymbolicName() + " [" + there.getBundleId() + "] from "
                    + there.getLocation() + ", a copy the store did not install");
        }
        return null;
    }

    /**
     * Shared packages a store bundle took from somebody else's bundle.
     *
     * <p>The class space is one only while every shared package comes from
     * the system bundle. An application bundle exporting one of them at a
     * version the store's bundles accept can be chosen instead, and the
     * result resolves and starts and is two classes with one name — the bean
     * on one side and the whiteboard on the other.
     */
    private List<String> splitClassSpace(Map<String, Bundle> ours) {
        java.util.Set<String> shared = dbo.sharedByName().keySet();
        java.util.Set<Long> ourIds = new java.util.HashSet<>();
        ours.values().forEach(bundle -> ourIds.add(bundle.getBundleId()));
        List<String> split = new ArrayList<>();
        for (Map.Entry<String, Bundle> each : ours.entrySet()) {
            org.osgi.framework.wiring.BundleWiring wiring =
                    each.getValue().adapt(org.osgi.framework.wiring.BundleWiring.class);
            if (wiring == null) {
                continue;
            }
            for (org.osgi.framework.wiring.BundleWire wire
                    : wiring.getRequiredWires("osgi.wiring.package")) {
                Object pkg = wire.getCapability().getAttributes().get("osgi.wiring.package");
                Bundle provider = wire.getProvider().getBundle();
                if (pkg != null && shared.contains(pkg.toString())
                        && provider.getBundleId() != 0
                        && !ourIds.contains(provider.getBundleId())) {
                    split.add(each.getKey() + " takes " + pkg + " from " + nameOf(provider)
                            + " rather than from the application, so that package's classes "
                            + "would exist twice. Stop " + nameOf(provider) + " exporting " + pkg
                            + ", or narrow its version");
                }
            }
        }
        return split;
    }

    /**
     * Bundles in the application's framework that do not resolve.
     *
     * <p>Asked once the store is in, because an application bundle may be
     * waiting for a package one of the store's bundles exports. One still
     * unresolved then is a container that started and in which that bundle
     * does nothing — named, with the packages nothing provides.
     */
    private List<String> unresolvedNeighbours(Map<String, Bundle> ours) {
        org.osgi.framework.wiring.FrameworkWiring wiring =
                framework.adapt(org.osgi.framework.wiring.FrameworkWiring.class);
        wiring.resolveBundles(null);
        java.util.Set<Long> ourIds = new java.util.HashSet<>();
        ours.values().forEach(bundle -> ourIds.add(bundle.getBundleId()));
        List<String> unresolved = new ArrayList<>();
        for (Bundle there : framework.getBundleContext().getBundles()) {
            if (there.getState() != Bundle.INSTALLED || ourIds.contains(there.getBundleId())) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            org.osgi.framework.wiring.BundleRevision revision =
                    there.adapt(org.osgi.framework.wiring.BundleRevision.class);
            if (revision != null) {
                for (org.osgi.resource.Requirement needs
                        : revision.getRequirements("osgi.wiring.package")) {
                    if ("optional".equals(needs.getDirectives().get("resolution"))) {
                        continue;
                    }
                    if (wiring.findProviders(needs).isEmpty()) {
                        missing.add(packageIn(needs.getDirectives().get("filter")));
                    }
                }
            }
            unresolved.add(nameOf(there) + " does not resolve" + (missing.isEmpty() ? ""
                    : "; nothing provides " + String.join(", ", missing)));
        }
        return unresolved;
    }

    private static String packageIn(String filter) {
        java.util.regex.Matcher named = java.util.regex.Pattern
                .compile("osgi\\.wiring\\.package=([^)]+)").matcher(filter == null ? "" : filter);
        return named.find() ? named.group(1) : String.valueOf(filter);
    }

    private static String nameOf(Bundle bundle) {
        return bundle.getSymbolicName() + " [" + bundle.getBundleId() + "]";
    }

    /**
     * Takes the container down and lets go of it.
     *
     * <p><b>Released, not merely stopped.</b> Stopping ends the framework's
     * threads; it does not drop the object graph, and a field holding a
     * stopped framework keeps every bundle classloader alive with everything
     * its statics hold. The container harness has the scar: three stopped
     * frameworks and eleven bundle classloaders still reachable while a suite
     * that touches no OSGi at all was running.
     *
     * <p>In a framework the application owns, only the store's part goes:
     * what it registered for the application, then its bundles in reverse
     * order, uninstalled and refreshed so their classloaders can be
     * collected. A bundle of the application's that wired to a package only
     * the store's bundles export is refreshed with them, which is the
     * framework's rule and not the store's. An attached extension stays,
     * because detaching one restarts the framework.
     */
    @Override
    public synchronized void close() {
        if (framework == null) {
            return;
        }
        LOG.info("shutdown requested: component=dbo-embedded");
        takeDown();
    }

    /** The bundles, by symbolic name. Package-private: a Bundle is not an API. */
    synchronized Map<String, Bundle> bundles() {
        return installed == null ? Map.of() : Map.copyOf(installed);
    }

    /**
     * What the container holds and what each part of it is doing, as words.
     *
     * <p>The report a health check renders and the one a failure is read
     * from. Words rather than {@code Bundle}s, and the reason is the whole
     * posture of these assemblies: an application that could reach a
     * {@code Bundle} would be an application that had learnt what OSGi is,
     * and the first one to ship against it makes that the supported API.
     */
    public synchronized Map<String, String> states() {
        if (installed == null) {
            return Map.of();
        }
        Map<String, String> said = new LinkedHashMap<>();
        installed.forEach((name, bundle) -> said.put(name, stateOf(bundle)));
        return said;
    }

    /**
     * How an assembly puts a bean where the container's whiteboards look.
     *
     * <p>Valid while the container is running, and an assembly asks for it
     * when it has something to register rather than keeping one.
     */
    public DboRegistrar registrar() {
        return new DboRegistrar(this);
    }

    /**
     * What the container is offering right now, for an assembly to read.
     *
     * <p>Asked for when something is wanted rather than kept, on the same
     * reason the lookup itself caches nothing: a tenant's services come and
     * go with the tenant.
     */
    public DboLookup lookup() {
        return new DboLookup(this);
    }

    /** The container's context, for this package. Never handed to an application. */
    synchronized BundleContext context() {
        if (framework == null) {
            throw new IllegalStateException("the container is not running");
        }
        return framework.getBundleContext();
    }

    /**
     * What one bundle is doing, in the words a reader needs.
     *
     * <p>A fragment that has attached sits at RESOLVED and never moves, so
     * reporting it as "resolved, not running" beside a bundle that failed to
     * start would put a healthy container's one correct entry next to the
     * sentence used for a broken one.
     */
    private static String stateOf(Bundle bundle) {
        if (bundle.getState() == Bundle.RESOLVED && isFragment(bundle)) {
            return "attached";
        }
        return switch (bundle.getState()) {
            case Bundle.ACTIVE -> "running";
            case Bundle.RESOLVED -> "resolved, not running";
            case Bundle.INSTALLED -> "installed, unresolved";
            case Bundle.STARTING -> "starting";
            case Bundle.STOPPING -> "stopping";
            default -> "uninstalled";
        };
    }

    private static boolean isFragment(Bundle bundle) {
        org.osgi.framework.wiring.BundleRevision revision =
                bundle.adapt(org.osgi.framework.wiring.BundleRevision.class);
        return revision != null
                && (revision.getTypes() & org.osgi.framework.wiring.BundleRevision.TYPE_FRAGMENT)
                        != 0;
    }

    /**
     * Takes out whatever the store put in, and lets go of the framework.
     *
     * <p>Its own framework is stopped. The application's is left running
     * with the store's part removed from it.
     */
    private void takeDown() {
        try {
            if (handed == null) {
                stopQuietly();
                return;
            }
            for (org.osgi.framework.ServiceRegistration<?> each : registered) {
                try {
                    each.unregister();
                } catch (IllegalStateException alreadyGone) {
                    // Withdrawn by its own closer first; nothing to do.
                }
            }
            List<Bundle> ours = new ArrayList<>(installed == null ? List.of()
                    : installed.entrySet().stream()
                            .filter(each -> !adopted.contains(each.getKey())
                                    && !isFragmentOfTheSystemBundle(each.getValue()))
                            .map(Map.Entry::getValue)
                            .toList());
            java.util.Collections.reverse(ours);
            for (Bundle bundle : ours) {
                try {
                    bundle.uninstall();
                } catch (BundleException | IllegalStateException notUninstalled) {
                    LOG.warn("{} could not be taken out of the application's framework",
                            bundle.getSymbolicName(), notUninstalled);
                }
            }
            refresh(ours);
        } finally {
            registered.clear();
            framework = null;
            installed = null;
            adopted = java.util.Set.of();
        }
    }

    /**
     * Refreshes these bundles and waits for it, so that what was uninstalled
     * is gone rather than pending.
     */
    private void refresh(List<Bundle> bundles) {
        if (bundles.isEmpty()) {
            return;
        }
        org.osgi.framework.wiring.FrameworkWiring wiring =
                handed.adapt(org.osgi.framework.wiring.FrameworkWiring.class);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        wiring.refreshBundles(bundles, event -> done.countDown());
        try {
            if (!done.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("the application's framework did not finish refreshing the store's "
                        + "bundles within 30 seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Remembers a registration made for the application, to withdraw at close. */
    void registered(org.osgi.framework.ServiceRegistration<?> registration) {
        registered.add(registration);
    }

    private static boolean isFragmentOfTheSystemBundle(Bundle bundle) {
        String host = bundle.getHeaders().get("Fragment-Host");
        return host != null && (host.startsWith("system.bundle")
                || host.startsWith("org.apache.felix.framework"));
    }

    private void stopQuietly() {
        try {
            framework.stop();
            framework.waitForStop(30_000);
        } catch (BundleException | InterruptedException didNotStop) {
            if (didNotStop instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("the container did not stop cleanly", didNotStop);
        } finally {
            framework = null;
            installed = null;
        }
    }

    private static String rootOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /** A storage directory of this application's own, under its temp area. */
    public static Path storageUnder(Path parent, String named) {
        try {
            Path under = parent.resolve(named);
            Files.createDirectories(under);
            return under;
        } catch (IOException noRoom) {
            throw new java.io.UncheckedIOException("the container needs somewhere to keep its "
                    + "bundle cache and " + parent + " would not take one", noRoom);
        }
    }
}
