package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
    void noPublicApiOfTheR4PersonalityExposesHapiTypes() throws Exception {
        scanPersonalityJar(Path.of(System.getProperty("dbo.fhir.r4.jar")));
    }

    @Test
    void noPublicApiOfTheR5PersonalityExposesHapiTypes() throws Exception {
        scanPersonalityJar(Path.of(System.getProperty("dbo.fhir.r5.jar")));
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
