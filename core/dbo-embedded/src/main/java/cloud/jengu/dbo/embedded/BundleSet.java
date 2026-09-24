package cloud.jengu.dbo.embedded;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

/**
 * What is on the classpath to install, found by reading it.
 *
 * <p>An assembly records the bundles it installs, in order, as a resource in
 * its own jar. The jars themselves arrive as ordinary dependencies, so they
 * are on the application's classpath — which is the whole mechanism: this
 * finds each one by reading manifests, and the framework installs it from a
 * stream.
 *
 * <p><b>Streams rather than paths, and that is not an implementation
 * detail.</b> Inside a Spring Boot fat jar a dependency is an entry within
 * another archive; it has a URL and no file path, so there is nothing to hand
 * {@code installBundle(String)}. Reading it as a stream works there and in an
 * exploded classpath alike, and an application should not have to be
 * repackaged to embed a store.
 *
 * <p><b>Two indexes are one framework.</b> An application holding both
 * assemblies contributes two lists, and they are unioned in first-seen order.
 * Two frameworks in one JVM would each hold a copy of every bundle, and for
 * the element bundle that is a parsed set of FHIR definitions held twice.
 */
public final class BundleSet {

    /** Where an assembly records what it installs, and in which order. */
    static final String INSTALL = "META-INF/dbo/bundles.index";

    /**
     * Where it records which PACKAGES it shares with the application.
     *
     * <p>Packages rather than bundles, and the difference is load-bearing. A
     * bundle can hold both an API a host implements against and an
     * implementation that reaches its own private content — {@code dbo-tenant}
     * holds exactly that — and only the first half can be supplied from
     * outside the framework. Sharing by jar would take the second half with
     * it and stop tenants coming up.
     */
    static final String SHARED = "META-INF/dbo/shared.index";

    private final List<String> order;
    private final Set<String> shared;
    private final Map<String, Found> found;

    private BundleSet(List<String> order, Set<String> shared, Map<String, Found> found) {
        this.order = List.copyOf(order);
        this.shared = Set.copyOf(shared);
        this.found = Map.copyOf(found);
    }

    /** One bundle: where to read it, and what its manifest says. */
    record Found(String symbolicName, URL at, Manifest manifest) {

        InputStream open() {
            try {
                return at.openStream();
            } catch (IOException unreadable) {
                throw new UncheckedIOException(symbolicName + " is on the classpath at " + at
                        + " and could not be read, so the container cannot install it",
                        unreadable);
            }
        }

        String exports() {
            String header = manifest.getMainAttributes().getValue("Export-Package");
            return header == null ? "" : header;
        }

        /**
         * Whether this one attaches to another bundle rather than running.
         *
         * <p>A fragment is never started — it has no activator and no
         * lifecycle of its own — and the ServiceLoader mediator is the
         * sharpest case: it is an extension of the SYSTEM bundle, so it
         * attaches at install and starting it throws. Read from the manifest
         * rather than listed anywhere, because a bundle already says which
         * kind it is.
         */
        boolean attachesRatherThanRuns() {
            return manifest.getMainAttributes().getValue("Fragment-Host") != null;
        }
    }

    /**
     * Reads the indexes, then finds the jar each one names.
     *
     * <p>A named bundle with no jar is refused here, by name. It would
     * otherwise be found much later and much further away: a bundle set short
     * one bundle fails as a resolution error naming a PACKAGE, in a bundle
     * that is not the missing one.
     */
    public static BundleSet on(ClassLoader loader) {
        List<String> order = linesOf(loader, INSTALL);
        if (order.isEmpty()) {
            throw new IllegalStateException("no " + INSTALL + " on the classpath: nothing here "
                    + "says which bundles to install. An application embeds the store by "
                    + "depending on one of the assemblies, and each of them carries one.");
        }
        Map<String, Found> byName = manifestsOn(loader, new LinkedHashSet<>(order));
        List<String> missing = new ArrayList<>();
        for (String name : order) {
            if (!byName.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the bundle set names " + missing + " and no jar on "
                    + "the classpath carries " + (missing.size() == 1 ? "that " : "those ")
                    + "symbolic name" + (missing.size() == 1 ? "" : "s")
                    + ". The container will not be started, because a set short a bundle fails "
                    + "later as a missing PACKAGE, in some other bundle.");
        }
        return new BundleSet(order, new LinkedHashSet<>(linesOf(loader, SHARED)), byName);
    }

    /** Every bundle, in the order the assemblies asked for. */
    List<Found> inInstallOrder() {
        List<Found> all = new ArrayList<>(order.size());
        for (String name : order) {
            all.add(found.get(name));
        }
        return all;
    }

    /** The packages the application and the container hold as one. */
    Set<String> sharedWithTheApplication() {
        return shared;
    }

    /** Every bundle's manifest, for reading what a package is exported at. */
    List<Found> all() {
        return inInstallOrder();
    }

    /**
     * Every manifest on the classpath, kept if the set names it.
     *
     * <p>One pass over {@code META-INF/MANIFEST.MF}, which every jar has and
     * most are not bundles. First one wins: two jars claiming one symbolic
     * name is a duplicated dependency, and taking the first is what the
     * classloader would do with their classes anyway.
     */
    private static Map<String, Found> manifestsOn(ClassLoader loader, Set<String> wanted) {
        Map<String, Found> byName = new LinkedHashMap<>();
        try {
            Enumeration<URL> manifests = loader.getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                URL at = manifests.nextElement();
                Manifest manifest;
                try (InputStream open = at.openStream()) {
                    manifest = new Manifest(open);
                } catch (IOException unreadable) {
                    // A manifest that will not open is somebody else's jar
                    // having a bad day. It is only interesting if it was one
                    // of ours, and the refusal above says so by name.
                    continue;
                }
                String symbolic = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
                if (symbolic == null) {
                    continue;
                }
                symbolic = symbolic.split(";")[0].trim();
                if (wanted.contains(symbolic) && !byName.containsKey(symbolic)) {
                    byName.put(symbolic, new Found(symbolic, jarOf(at), manifest));
                }
            }
        } catch (IOException noClasspath) {
            throw new UncheckedIOException("the classpath could not be read, so the bundles "
                    + "the container installs cannot be found", noClasspath);
        }
        return byName;
    }

    /**
     * The archive a manifest was read from.
     *
     * <p>A manifest URL is {@code <something>!/META-INF/MANIFEST.MF}; the jar
     * is what precedes the last separator. It stays a URL — a nested entry
     * has no file path, and reaching for one is exactly what makes an
     * embedding work from an exploded classpath and fail from a fat jar.
     */
    private static URL jarOf(URL manifest) {
        String at = manifest.toString();
        int entry = at.lastIndexOf("!/");
        if (entry < 0) {
            return manifest;
        }
        String archive = at.substring(0, entry);
        if (archive.startsWith("jar:")) {
            archive = archive.substring("jar:".length());
        }
        try {
            return java.net.URI.create(archive).toURL();
        } catch (java.net.MalformedURLException | IllegalArgumentException notAnArchive) {
            return manifest;
        }
    }

    private static List<String> linesOf(ClassLoader loader, String resource) {
        List<String> lines = new ArrayList<>();
        try {
            Enumeration<URL> each = loader.getResources(resource);
            while (each.hasMoreElements()) {
                try (InputStream open = each.nextElement().openStream()) {
                    for (String line : new String(open.readAllBytes(), StandardCharsets.UTF_8)
                            .split("\n")) {
                        String named = line.trim();
                        // First seen wins, so an application holding both
                        // assemblies installs the shared bundles once, in the
                        // order the first one asked for.
                        if (!named.isEmpty() && !lines.contains(named)) {
                            lines.add(named);
                        }
                    }
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(resource + " is on the classpath and could not be "
                    + "read", unreadable);
        }
        return lines;
    }
}
