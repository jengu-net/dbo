package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §7.3 boundary rule as a ratchet: no PUBLIC api of the R4 personality
 * may mention a HAPI type. HAPI stays private to the personality; JSON and
 * core api types are the only things that cross.
 */
class ApiBoundaryTest {

    @Test
    @Proving(DboPromises.CONT_PRIVATE_DEPENDENCIES)
    void noPublicApiOfTheR4PersonalityExposesHapiTypes() throws Exception {
        scanPersonalityJar(Path.of(System.getProperty("dbo.fhir.r4.jar")));
    }

    @Test
    @Proving(DboPromises.CONT_PRIVATE_DEPENDENCIES)
    void noPublicApiOfTheR5PersonalityExposesHapiTypes() throws Exception {
        scanPersonalityJar(Path.of(System.getProperty("dbo.fhir.r5.jar")));
    }

    /**
     * The same rule, for EVERY bundle that exports a dbo package (#85).
     *
     * <p>The personality tests above are the rule's origin — HAPI must not
     * cross a personality's public API — and this is the rule itself: what a
     * bundle EXPORTS is what other bundles may call, so a public signature
     * there may name only types the container guarantees. A signature naming a
     * type from a package the bundle keeps private is not merely untidy: a
     * caller would have to import a package nothing exports, so the API as
     * published cannot be used from outside at all. It reads as callable and
     * is not.
     *
     * <p>Found by bnd during the #32 conversion and enforced here, because a
     * warning nobody fails on is a warning nobody reads.
     */
    private static final List<String> BUNDLES = List.of("dbo.core", "dbo.fhir.common",
            "dbo.postgres", "dbo.rest", "dbo.auth", "dbo.pdi", "dbo.policy", "dbo.work",
            "dbo.sync", "dbo.maintenance", "dbo.terminology", "dbo.subscriptions",
            "dbo.tenant", "dbo.tenant.k8s", "dbo.fhir.element", "dbo.fhir.stack",
            "dbo.fhir.r4", "dbo.fhir.r5");

    @Test
    void noExportedDboApiNamesATypeNothingExports() throws Exception {
        // What the CONTAINER can wire: every package any production bundle
        // exports. The stack exports HAPI, so a face naming a HAPI type in
        // its API is callable — untidy is a different argument from broken,
        // and this test makes the second one. The personality tests above
        // make the first, and stay stricter on purpose.
        Set<String> available = new java.util.HashSet<>();
        for (String bundle : BUNDLES) {
            String path = System.getProperty(bundle + ".jar");
            if (path != null) {
                available.addAll(exportsOf(Path.of(path)));
            }
        }
        List<String> leaks = new ArrayList<>();
        for (String bundle : BUNDLES) {
            String path = System.getProperty(bundle + ".jar");
            if (path == null) {
                continue; // a bundle this suite does not stage says nothing here
            }
            scanExported(Path.of(path), available, leaks);
        }
        assertEquals(List.of(), leaks,
                "an exported package's public API names a type its bundle keeps private — "
                        + "a caller would need to import a package nothing exports");
    }

    /** The packages one bundle publishes. */
    private static Set<String> exportsOf(Path jar) throws Exception {
        Set<String> exported = new java.util.HashSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            String header = jf.getManifest().getMainAttributes().getValue("Export-Package");
            if (header != null) {
                for (String clause : header.split(",(?=[a-zA-Z])")) {
                    exported.add(clause.split(";")[0].trim());
                }
            }
        }
        return exported;
    }

    /** Public signatures of classes in EXPORTED packages, held to what is wireable. */
    private void scanExported(Path jar, Set<String> available, List<String> leaks)
            throws Exception {
        Set<String> exported = exportsOf(jar);
        if (exported.isEmpty()) {
            return; // exports nothing: there is no outward API to hold
        }
        List<String> classNames = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().endsWith(".class") && !e.getName().contains("$")) {
                    classNames.add(e.getName().replace('/', '.').replaceAll("\\.class$", ""));
                }
            }
        }
        for (String name : classNames) {
            if (!exported.contains(name.substring(0, name.lastIndexOf('.')))) {
                continue;
            }
            Class<?> c;
            try {
                c = Class.forName(name);
            } catch (Throwable t) {
                continue; // not on this suite's classpath; the container test covers wiring
            }
            if (!Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                container(leaks, available, name + "#" + m.getName(), m.getReturnType());
                for (Class<?> p : m.getParameterTypes()) {
                    container(leaks, available, name + "#" + m.getName(), p);
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (!Modifier.isPublic(k.getModifiers())) continue;
                for (Class<?> p : k.getParameterTypes()) {
                    container(leaks, available, name + "#<init>", p);
                }
            }
        }
    }

    /**
     * What a public signature in an exported package may name: dbo's own
     * exported api, the JDK, and the OSGi framework — everything the
     * container guarantees is there for any caller.
     */
    private static void container(List<String> leaks, Set<String> available, String where,
            Class<?> type) {
        Class<?> t = type;
        while (t.isArray()) {
            t = t.getComponentType();
        }
        if (t.isPrimitive()) {
            return;
        }
        String n = t.getName();
        if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("org.osgi.")
                || n.startsWith("com.sun.net.httpserver.")
                || available.contains(n.substring(0, n.lastIndexOf('.')))) {
            return;
        }
        leaks.add(where + " -> " + n);
    }

    private void scanPersonalityJar(Path jar) throws Exception {
        List<String> classNames = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().endsWith(".class") && !e.getName().contains("$")) {
                    classNames.add(e.getName().replace('/', '.').replaceAll("\\.class$", ""));
                }
            }
        }
        assertTrue(classNames.size() >= 2, "expected the personality classes in the jar");

        List<String> leaks = new ArrayList<>();
        for (String name : classNames) {
            Class<?> c = Class.forName(name);
            if (!Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                check(leaks, name + "#" + m.getName(), m.getReturnType());
                for (Class<?> p : m.getParameterTypes()) {
                    check(leaks, name + "#" + m.getName(), p);
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (!Modifier.isPublic(k.getModifiers())) continue;
                for (Class<?> p : k.getParameterTypes()) {
                    check(leaks, name + "#<init>", p);
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers())) {
                    check(leaks, name + "." + f.getName(), f.getType());
                }
            }
        }
        assertEquals(List.of(), leaks, "HAPI types leaked across the personality boundary");
    }

    private static void check(List<String> leaks, String where, Class<?> type) {
        String n = type.getName();
        if (n.startsWith("ca.uhn.") || n.startsWith("org.hl7.")) {
            leaks.add(where + " -> " + n);
        }
    }
}
