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
 * <p>It boots a framework, installs the bundle set an assembly recorded, and
 * takes it all down again. Nothing it publishes names a {@code Bundle}, a
 * {@code BundleContext} or a {@code ServiceReference}, and an application
 * author never learns the word.
 *
 * <p><b>One framework, however many assemblies.</b> An application holding
 * the serving half and the performing half contributes two bundle lists and
 * gets one container. Two would each hold a copy of every bundle, and for the
 * element bundle that is a full set of parsed FHIR definitions — measured in
 * the container harness at roughly 100 to 215 MB per framework — held twice.
 */
public final class EmbeddedRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.spring");

    private final ClassLoader loader;
    private final Map<String, String> properties;
    private final Path storage;

    private Framework framework;
    private Map<String, Bundle> installed;

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
        this.loader = loader;
        this.properties = Map.copyOf(properties);
        this.storage = storage;
    }

    /** Whether the container is up. */
    public boolean running() {
        return framework != null && framework.getState() == Bundle.ACTIVE;
    }

    /**
     * Boots the container: find, compute, construct, install, start.
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
        BundleSet set = BundleSet.on(loader);
        Map<String, String> config = new HashMap<>(properties);
        config.put("org.osgi.framework.storage", storage.toAbsolutePath().toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        config.put("org.osgi.framework.system.packages.extra",
                SharedPackages.from(set.sharedWithTheApplication(), set.all(), slf4jVersion()));

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
        this.framework = booting;

        BundleContext ctx = booting.getBundleContext();
        Map<String, Bundle> put = new LinkedHashMap<>();
        java.util.Set<String> attaching = new java.util.HashSet<>();
        for (BundleSet.Found bundle : set.inInstallOrder()) {
            if (bundle.attachesRatherThanRuns()) {
                attaching.add(bundle.symbolicName());
            }
            try (InputStream bytes = bundle.open()) {
                put.put(bundle.symbolicName(), ctx.installBundle(bundle.at().toString(), bytes));
            } catch (BundleException | IOException notInstalled) {
                stopQuietly();
                throw new IllegalStateException(bundle.symbolicName() + " could not be installed "
                        + "from " + bundle.at() + ", so the container is incomplete and has "
                        + "been stopped rather than left serving part of a store", notInstalled);
            }
        }
        this.installed = put;

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
                each.getValue().start();
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
            stopQuietly();
            throw new IllegalStateException("the container did not come up. " + refused.size()
                    + " of " + put.size() + " bundles would not start:\n  "
                    + String.join("\n  ", refused));
        }
        LOG.info("starting: component=dbo-embedded bundles={} storage={}", put.size(), storage);
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
     */
    @Override
    public synchronized void close() {
        if (framework == null) {
            return;
        }
        LOG.info("shutdown requested: component=dbo-embedded");
        stopQuietly();
        framework = null;
        installed = null;
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

    private void stopQuietly() {
        try {
            framework.stop();
            framework.waitForStop(30_000);
        } catch (BundleException | InterruptedException didNotStop) {
            if (didNotStop instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("the container did not stop cleanly", didNotStop);
        }
    }

    /**
     * The version of {@code org.slf4j} the application is holding.
     *
     * <p>Read from the API jar's own manifest rather than assumed, because
     * this is the version the container's bundles have to import at — and it
     * is the application's choice, not ours. Absent, the package is not
     * shared and a bundle logging through slf4j will not resolve, which is a
     * loud failure and the right one: silently dropping the logging of a
     * store is worse than not starting it.
     */
    private String slf4jVersion() {
        String version = Logger.class.getPackage() == null ? null
                : Logger.class.getPackage().getImplementationVersion();
        if (version == null) {
            LOG.warn("the slf4j API on the classpath does not say its version, so the container "
                    + "shares org.slf4j without one");
        }
        return version;
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
