package cloud.jengu.dbo.embedded;

import org.osgi.framework.Version;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a framework has to be created with for the store to live in it.
 *
 * <p>An application that owns its OSGi framework — because bundles of its own
 * belong in the same container as the store's — creates it with these
 * properties and its own beside them, and hands it over. The store installs
 * into it rather than creating a second one: two frameworks in one JVM each
 * hold a copy of every bundle, and for the element bundle that is a parsed set
 * of FHIR definitions held twice.
 *
 * <p><b>Properties, because only creation can set them.</b> The packages the
 * application and the container hold as one class space are exported by the
 * system bundle, and the system bundle's exports are fixed when the framework
 * is constructed. So is every property an activator reads through
 * {@code BundleContext.getProperty}. Nothing installed later can add either,
 * which is why the store checks a framework it is handed and refuses one
 * that lacks them, by name, rather than installing into it and failing on
 * first use.
 *
 * <p>The same properties are what the store creates its own framework with
 * when nobody hands it one, so the two modes cannot drift apart.
 */
public final class DboFramework {

    /**
     * The launch property that carries the shared packages.
     *
     * <p>An application sharing packages of its own appends them to this
     * value, comma-separated, rather than replacing it: the store checks the
     * system bundle's exports, not the spelling of the property.
     */
    public static final String SHARED_PACKAGES = "org.osgi.framework.system.packages.extra";

    private static final Logger LOG = LoggerFactory.getLogger("dbo.spring");

    private final ClassLoader loader;
    private final Map<String, String> configuration;

    private BundleSet set;
    private String shared;

    /**
     * @param loader        where the bundle jars and the indexes are read from,
     *                      which is the application's own classloader
     * @param configuration what the store's activators read through
     *                      {@code BundleContext.getProperty} — the merged
     *                      {@link FrameworkContribution}s of whichever
     *                      assemblies the application holds
     */
    public DboFramework(ClassLoader loader, Map<String, String> configuration) {
        this.loader = loader;
        this.configuration = Map.copyOf(configuration);
    }

    /**
     * Every property a framework needs for the store to install into it.
     *
     * <p>The store's configuration and the shared packages, computed from the
     * manifests of the bundles on the classpath at the versions they declare.
     * Storage and cache settings are absent on purpose: they are the
     * framework owner's.
     */
    public synchronized Map<String, String> properties() {
        Map<String, String> all = new LinkedHashMap<>(configuration);
        all.put(SHARED_PACKAGES, sharedPackages());
        return all;
    }

    /** The bundles the assemblies on the classpath install, read once. */
    synchronized BundleSet bundles() {
        if (set == null) {
            set = BundleSet.on(loader);
        }
        return set;
    }

    /** The value of {@link #SHARED_PACKAGES}, computed once. */
    synchronized String sharedPackages() {
        if (shared == null) {
            BundleSet bundles = bundles();
            shared = SharedPackages.from(bundles.sharedWithTheApplication(), bundles.all(),
                    slf4jVersion());
        }
        return shared;
    }

    /** The shared packages by name, with the version each is exported at. */
    synchronized Map<String, String> sharedByName() {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String clause : sharedPackages().split(",")) {
            if (clause.isBlank()) {
                continue;
            }
            String[] parts = clause.split(";version=");
            byName.put(parts[0].trim(),
                    parts.length > 1 ? parts[1].replace("\"", "").trim() : null);
        }
        return byName;
    }

    /**
     * Refuses a framework that was not created with what the store needs.
     *
     * <p>Every shared package is looked for among the system bundle's exports
     * at the version this computes, and every configuration property is read
     * back through the framework. What is missing is named, all of it at once:
     * a refusal naming one gap sends whoever fixes it round the loop once per
     * property. A property set to another value is named without either value,
     * because some of them are secrets.
     */
    void refuseWhatItDoesNotCarry(Framework framework) {
        List<String> missing = new ArrayList<>();
        Map<String, Version> exported = new LinkedHashMap<>();
        BundleWiring system = framework.adapt(BundleWiring.class);
        if (system != null) {
            for (BundleCapability exports : system.getCapabilities("osgi.wiring.package")) {
                Object name = exports.getAttributes().get("osgi.wiring.package");
                Object version = exports.getAttributes().get("version");
                if (name != null) {
                    exported.put(name.toString(), version instanceof Version v ? v
                            : Version.emptyVersion);
                }
            }
        }
        sharedByName().forEach((name, version) -> {
            Version has = exported.get(name);
            Version wants = version == null ? Version.emptyVersion : Version.parseVersion(version);
            if (has == null) {
                missing.add(name + " is not exported by the system bundle");
            } else if (!has.equals(wants)) {
                missing.add(name + " is exported at " + has + " and the store's bundles are "
                        + "built against " + wants);
            }
        });
        configuration.forEach((name, value) -> {
            String has = framework.getBundleContext().getProperty(name);
            if (has == null) {
                missing.add(name + " is not set");
            } else if (!Objects.equals(has, value)) {
                missing.add(name + " is set to something other than what this application "
                        + "configured");
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the OSGi framework this application provides was "
                    + "not created with what the store needs, so the store was not installed "
                    + "into it. Create it with DboFramework.properties() among its launch "
                    + "properties. " + missing.size() + " missing:\n  "
                    + String.join("\n  ", missing));
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
    private static String slf4jVersion() {
        String version = Logger.class.getPackage() == null ? null
                : Logger.class.getPackage().getImplementationVersion();
        if (version == null) {
            LOG.warn("the slf4j API on the classpath does not say its version, so the container "
                    + "shares org.slf4j without one");
        }
        return version;
    }
}
