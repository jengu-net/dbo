package cloud.jengu.dbo.harness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Every signature a consumer of this store can compile against, as one sorted
 * list.
 *
 * <p>The bundles export deliberately and {@code ApiBoundaryTest} already holds
 * what may appear in an export. What neither answers is whether what crosses
 * still <b>means</b> what it meant: remove a method from an exported package
 * and the version does not move, because the version comes from the build.
 * Nothing objects, and the failure arrives at a consumer as a
 * {@code NoSuchMethodError} at runtime rather than as a refusal at build time.
 *
 * <p>So the surface is recorded and the recording is reviewed. A change that
 * removes a line is a change that breaks somebody who compiled against the
 * line before it; a change that only adds lines cannot. That distinction is
 * the whole product, and it is why this records signatures rather than a
 * digest — a hash tells you something moved, and the question is always which.
 *
 * <p>Members are public and protected both: protected is API to anybody who
 * subclasses, and a store that publishes an abstract type publishes what
 * extending it requires.
 */
final class ApiLedger {

    /** Every bundle that exports a package of this store's own. */
    static final List<String> BUNDLES = List.of("dbo.core", "dbo.postgres", "dbo.auth",
            "dbo.pdi", "dbo.policy", "dbo.work", "dbo.runner", "dbo.stream", "dbo.sync", "dbo.maintenance",
            "dbo.terminology", "dbo.subscriptions", "dbo.rest", "dbo.scim", "dbo.telemetry",
            "dbo.promises", "dbo.tenant", "dbo.tenant.k8s", "dbo.fhir.common",
            "dbo.fhir.element", "dbo.fhir.r4", "dbo.fhir.r5");

    private ApiLedger() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.writeString(out, render(), StandardCharsets.UTF_8);
        System.out.println("api ledger recorded: " + out);
    }

    static String render() throws Exception {
        StringBuilder text = new StringBuilder("""
                # The exported API of this store, one signature per line.
                #
                # GENERATED — do not edit. Re-record with:
                #     ./gradlew :core:harness:apiLedger
                #
                # A REMOVED line breaks anything compiled against it; an added line
                # cannot. Read the diff that way. Removing one before there is a
                # release somebody relies on is fine and normal — recording it is
                # what makes it a decision rather than an accident.
                """);
        for (String line : signatures()) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** The whole surface, sorted, ready to compare. */
    static List<String> signatures() throws Exception {
        Set<String> lines = new TreeSet<>();
        for (String bundle : BUNDLES) {
            String path = System.getProperty(bundle + ".jar");
            if (path == null) {
                // Never "skip quietly": a ledger missing a bundle records a
                // smaller surface and passes, which is the shape of a ratchet
                // that guards nothing.
                throw new IllegalStateException("no jar staged for " + bundle
                        + " — the ledger would record a surface smaller than the real one");
            }
            collect(Path.of(path), lines);
        }
        return List.copyOf(lines);
    }

    private static void collect(Path jar, Set<String> into) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            Set<String> exported = new TreeSet<>();
            String header = file.getManifest().getMainAttributes().getValue("Export-Package");
            if (header != null) {
                for (String clause : header.split(",(?=[a-zA-Z])")) {
                    String pkg = clause.split(";")[0].trim();
                    if (pkg.startsWith("cloud.jengu.dbo")) {
                        exported.add(pkg);
                    }
                }
            }
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                String binary = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                int dot = binary.lastIndexOf('.');
                if (dot < 0 || !exported.contains(binary.substring(0, dot))) {
                    continue;
                }
                Class<?> type = Class.forName(binary, false,
                        ApiLedger.class.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    describe(type, into);
                }
            }
        }
    }

    private static void describe(Class<?> type, Set<String> into) {
        String name = type.getCanonicalName() != null ? type.getCanonicalName() : type.getName();
        into.add("%-70s %s".formatted(name, kindOf(type)));
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (visible(c.getModifiers()) && !c.isSynthetic()) {
                into.add("%-70s new(%s)".formatted(name, parameters(c.getParameterTypes())));
            }
        }
        for (Method m : type.getDeclaredMethods()) {
            if (visible(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) {
                into.add("%-70s %s(%s):%s".formatted(name, m.getName(),
                        parameters(m.getParameterTypes()), simple(m.getReturnType())));
            }
        }
        for (Field f : type.getDeclaredFields()) {
            if (visible(f.getModifiers()) && !f.isSynthetic()) {
                into.add("%-70s %s:%s".formatted(name, f.getName(), simple(f.getType())));
            }
        }
    }

    /** An interface's members are API whether or not they say public. */
    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String kindOf(Class<?> type) {
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isRecord()) {
            return "record";
        }
        if (type.isInterface()) {
            return type.isAnnotation() ? "annotation" : "interface";
        }
        return Modifier.isAbstract(type.getModifiers()) ? "abstract class" : "class";
    }

    private static String parameters(Class<?>[] types) {
        return java.util.Arrays.stream(types).map(ApiLedger::simple)
                .collect(Collectors.joining(","));
    }

    /** Own types in full, everything else by simple name — the diff has to be readable. */
    private static String simple(Class<?> type) {
        String name = type.getCanonicalName() != null ? type.getCanonicalName() : type.getName();
        return name.startsWith("cloud.jengu.dbo") ? name
                : name.substring(name.lastIndexOf('.') + 1);
    }

    /** The lines one side has and the other does not. */
    static List<String> missingFrom(List<String> expected, List<String> actual) {
        List<String> gone = new ArrayList<>(expected);
        gone.removeAll(actual);
        return gone;
    }
}
