package cloud.jengu.dbo.embedded;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The packages the application and the container hold as one class space.
 *
 * <p>A Spring bean implements {@code StepService}; the runner's whiteboard,
 * inside the framework, tracks {@code StepService}. If those are two classes
 * loaded by two classloaders the bean is never seen, or is seen and fails a
 * cast — the defect that compiles, resolves and dies on first use.
 *
 * <p>The cure is not an adapter and not reflection. Every bundle here carries
 * a <b>substitutable</b> export: bnd emits an {@code Import-Package} for a
 * package the bundle also exports. So when the system bundle exports that
 * package, the resolver wires the import to the system bundle — bundle zero
 * wins a tie — and the bundle's own export goes unused. Every bundle in the
 * framework and the application outside it then hold the same class.
 *
 * <p><b>Computed, never written down.</b> A hand-written package list drifts
 * the first time a package is added to an exported bundle, and the failure is
 * a wiring that silently prefers the bundle's own copy. These are read from
 * the {@code Export-Package} headers of the jars actually on the classpath,
 * at the versions those jars actually declare.
 */
final class SharedPackages {

    private SharedPackages() {
    }

    /**
     * The value for {@code org.osgi.framework.system.packages.extra}.
     *
     * @param bundles the bundles whose exports are shared — a subset, and the
     *                subset is the point: a fat bundle carries its stack in
     *                nested jars no application classloader can see, so
     *                exporting its packages would resolve and then fail on
     *                first use
     * @param slf4j   the version of {@code org.slf4j} the application's own
     *                binding is behind, or null where it has none
     */
    /**
     * @param shared  the packages to hold as one class space, by name
     * @param bundles every bundle, so each shared package's version and its
     *                {@code uses:} can be read off whichever one exports it
     */
    static String from(java.util.Set<String> shared, List<BundleSet.Found> bundles,
            String slf4j) {
        Map<String, String> versions = new LinkedHashMap<>();
        java.util.List<String> unexported = new ArrayList<>(shared);
        for (BundleSet.Found bundle : bundles) {
            for (String clause : clausesOf(bundle.exports())) {
                String name = nameOf(clause);
                if (name.isEmpty() || !shared.contains(name)) {
                    continue;
                }
                unexported.remove(name);
                versions.putIfAbsent(name, versionIn(clause));
            }
        }
        if (!unexported.isEmpty()) {
            throw new IllegalStateException("the assembly shares " + unexported + " and no "
                    + "bundle in its set exports " + (unexported.size() == 1 ? "it" : "them")
                    + ", so the container would offer the application packages that nothing "
                    + "provides");
        }
        refuseAnOpenSet(shared, bundles);
        if (slf4j != null) {
            // The whole of the logging bridge. The application already has a
            // binding, and two providers of org.slf4j in one framework is a
            // race rather than a posture — so no binding is installed and
            // every line a bundle logs is made by the application's own
            // LoggerFactory, in its appenders, at its levels.
            versions.put("org.slf4j", slf4j);
            versions.put("org.slf4j.event", slf4j);
            versions.put("org.slf4j.helpers", slf4j);
            versions.put("org.slf4j.spi", slf4j);
        }
        List<String> declared = new ArrayList<>(versions.size());
        versions.forEach((name, version) -> declared.add(
                version == null ? name : name + ";version=\"" + version + "\""));
        return String.join(",", declared);
    }

    /**
     * Refuses a shared set that does not include what its own API refers to.
     *
     * <p>Sharing a package means the application's copy of its classes wins
     * for everybody. So if a shared type's signature mentions a type from a
     * package that is NOT shared, there are two of that second class — one
     * loaded beside the shared type and one inside the bundle that exports
     * it — and anything passing an instance between them meets
     * {@code IncompatibleClassChangeError} or a cast that cannot succeed.
     *
     * <p>That is not hypothetical. Sharing the authority without the guard
     * seam it implements produced exactly that, on the first guarded read,
     * as a fatal inside an OperationOutcome — a container that had started
     * perfectly and could not serve a credential it had just minted.
     *
     * <p>bnd records the relation: an export clause carries {@code uses:=}
     * naming the packages its own signatures refer to. So the set can check
     * itself, and does, at boot rather than on the first request that crosses
     * the line. Only this store's own packages are checked; a shared type
     * mentioning a JDK type is the ordinary case and the JDK is one class
     * space already.
     */
    private static void refuseAnOpenSet(java.util.Set<String> shared,
            List<BundleSet.Found> bundles) {
        java.util.SortedSet<String> missing = new java.util.TreeSet<>();
        for (BundleSet.Found bundle : bundles) {
            for (String clause : clausesOf(bundle.exports())) {
                if (!shared.contains(nameOf(clause))) {
                    continue;
                }
                for (String used : usesIn(clause)) {
                    if (used.startsWith("cloud.jengu.dbo.") && !shared.contains(used)) {
                        missing.add(used + " (used by " + nameOf(clause) + ")");
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the shared package set is not closed over what its "
                    + "own API refers to, so some of these types would exist twice — once "
                    + "beside the application and once inside a bundle — and passing one "
                    + "between them fails at the first call that crosses: " + missing
                    + ". Add the bundles exporting them to the assembly's shared list.");
        }
    }

    /** The package a clause is about. */
    private static String nameOf(String clause) {
        int semicolon = clause.indexOf(';');
        return (semicolon < 0 ? clause : clause.substring(0, semicolon)).trim();
    }

    /** The packages a clause says its own signatures refer to. */
    private static List<String> usesIn(String clause) {
        for (String attribute : clause.split(";")) {
            String said = attribute.trim();
            if (said.startsWith("uses:=")) {
                String names = said.substring("uses:=".length()).replace("\"", "").trim();
                return List.of(names.split(","));
            }
        }
        return List.of();
    }

    /**
     * An {@code Export-Package} header, split into clauses.
     *
     * <p>Commas separate clauses and also separate items inside a quoted
     * attribute — {@code uses:="a,b,c"} is one clause containing two commas,
     * and splitting on the comma alone produces three broken ones. So quotes
     * are tracked.
     */
    private static List<String> clausesOf(String header) {
        List<String> clauses = new ArrayList<>();
        StringBuilder clause = new StringBuilder();
        boolean quoted = false;
        for (int at = 0; at < header.length(); at++) {
            char c = header.charAt(at);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                clauses.add(clause.toString());
                clause.setLength(0);
            } else {
                clause.append(c);
            }
        }
        if (!clause.isEmpty()) {
            clauses.add(clause.toString());
        }
        return clauses;
    }

    /** The clause's own version, or null where it declared none. */
    private static String versionIn(String clause) {
        for (String attribute : clause.split(";")) {
            String said = attribute.trim();
            if (said.startsWith("version=")) {
                return said.substring("version=".length()).replace("\"", "").trim();
            }
        }
        return null;
    }
}
