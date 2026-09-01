package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one bundle whose imports are still written by hand must declare
 * everything its embedded stack actually reaches for.
 *
 * <p>Every other bundle lets bnd compute `Import-Package` from bytecode, so
 * its imports cannot drift from its code. The shared HL7/HAPI stack cannot:
 * bnd over sixty-five embedded jars would demand hundreds of packages the
 * container has no intention of providing, so the list is curated. Curated is
 * fine; unchecked is not, and this is the failure mode that bites hardest
 * here — a package left out resolves perfectly, because OSGi resolves what a
 * bundle DECLARES, and throws `NoClassDefFoundError` the first time something
 * reaches the code path that needs it. Booting the container proves nothing
 * about a path the boot does not take.
 *
 * <p>Scope is the packages the framework must wire and the JDK will not hand
 * over by itself: `javax.*`, `org.w3c.*`, `org.xml.*`, `org.ietf.*` and slf4j.
 * Only `java.*` is boot-delegated, so these are exactly the ones whose absence
 * is silent until it is not. Third-party packages HAPI can optionally
 * integrate with are deliberately out of scope — they are absent by design and
 * their code paths are never entered.
 *
 * <p>This asserts coverage, never minimality. An entry nothing references may
 * still be reached reflectively, and removing it to tidy the list is how a
 * curated list becomes wrong.
 */
class TheStackImportsWhatItReachesForTest {

    /** What the container must wire, because the JDK does not boot-delegate it. */
    private static final Set<String> WATCHED =
            Set.of("javax", "org/w3c", "org/xml", "org/ietf", "org/slf4j");

    @Test
    @DisplayName("every framework-wired package the embedded stack reaches for is imported")
    void theCuratedListCoversTheBytecode() throws Exception {
        Path jar = Path.of(System.getProperty("dbo.fhir.stack.jar"));

        Set<String> provided = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        Set<String> declared;

        try (JarFile bundle = new JarFile(jar.toFile())) {
            declared = declaredImports(bundle.getManifest());
            for (var entries = bundle.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    provided.add(packageOf(entry.getName()));
                    try (InputStream in = bundle.getInputStream(entry)) {
                        collectReferences(in.readAllBytes(), referenced);
                    }
                } else if (entry.getName().endsWith(".jar")) {
                    try (InputStream in = bundle.getInputStream(entry)) {
                        scanEmbedded(in.readAllBytes(), provided, referenced);
                    }
                }
            }
        }

        assertTrue(provided.size() > 500,
                "the scan found almost no classes, so it is guarding nothing: "
                        + provided.size() + " packages");
        assertTrue(declared.size() > 10,
                "no curated import list was read from the manifest: " + declared);

        Set<String> undeclared = new TreeSet<>();
        for (String reference : referenced) {
            String pkg = packageOf(reference);
            if (watched(pkg) && !provided.contains(pkg) && !declared.contains(dotted(pkg))) {
                undeclared.add(dotted(pkg));
            }
        }

        assertTrue(undeclared.isEmpty(),
                "the stack reaches for packages it neither carries nor imports. Each one "
                        + "resolves cleanly and throws NoClassDefFoundError the first time "
                        + "its code path is taken, which no container test will catch unless "
                        + "it happens to take that path. Add them to Import-Package in "
                        + "core/dbo-fhir-stack/build.gradle.kts, optional where the container "
                        + "does not provide them:\n  " + String.join("\n  ", undeclared));
    }

    private static void scanEmbedded(byte[] jarBytes, Set<String> provided, Set<String> referenced)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) {
                    provided.add(packageOf(entry.getName()));
                    collectReferences(zip.readAllBytes(), referenced);
                }
            }
        }
    }

    private static boolean watched(String pkg) {
        return WATCHED.stream().anyMatch(w -> pkg.equals(w) || pkg.startsWith(w + "/"));
    }

    private static String packageOf(String slashName) {
        int slash = slashName.lastIndexOf('/');
        return slash < 0 ? "" : slashName.substring(0, slash);
    }

    private static String dotted(String slashPackage) {
        return slashPackage.replace('/', '.');
    }

    private static Set<String> declaredImports(Manifest manifest) {
        Set<String> out = new HashSet<>();
        String header = manifest.getMainAttributes().getValue("Import-Package");
        if (header == null) {
            return out;
        }
        // Split on commas that are not inside a quoted attribute value, then
        // take the package name off the front of each clause.
        boolean quoted = false;
        StringBuilder clause = new StringBuilder();
        for (char c : header.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                out.add(clause.toString().split(";")[0].trim());
                clause.setLength(0);
            } else {
                clause.append(c);
            }
        }
        out.add(clause.toString().split(";")[0].trim());
        return out;
    }

    /**
     * The class names in a class file's constant pool. Reading the pool is the
     * whole of what is needed: a package that appears nowhere in it cannot be
     * reached by that class, and one that appears may be.
     */
    private static void collectReferences(byte[] classFile, Set<String> into) {
        ByteBuffer b = ByteBuffer.wrap(classFile);
        if (b.remaining() < 10 || b.getInt() != 0xCAFEBABE) {
            return;
        }
        b.getInt(); // minor and major version
        int count = Short.toUnsignedInt(b.getShort());
        Map<Integer, String> utf8 = new HashMap<>();
        Set<Integer> classes = new HashSet<>();
        for (int i = 1; i < count; i++) {
            int tag = Byte.toUnsignedInt(b.get());
            switch (tag) {
                case 1 -> {
                    byte[] text = new byte[Short.toUnsignedInt(b.getShort())];
                    b.get(text);
                    utf8.put(i, new String(text, java.nio.charset.StandardCharsets.UTF_8));
                }
                case 7 -> classes.add(Short.toUnsignedInt(b.getShort()));
                case 8, 16, 19, 20 -> b.getShort();
                case 15 -> {
                    b.get();
                    b.getShort();
                }
                case 5, 6 -> {
                    b.getLong();
                    i++; // longs and doubles occupy two constant pool slots
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> b.getInt();
                default -> {
                    return; // an unknown tag means the walk is lost; say nothing
                }
            }
        }
        for (int index : classes) {
            String name = utf8.get(index);
            if (name == null) {
                continue;
            }
            // array descriptors: [[Ljavax/xml/Foo; → javax/xml/Foo
            int start = 0;
            while (start < name.length() && name.charAt(start) == '[') {
                start++;
            }
            if (start < name.length() && name.charAt(start) == 'L') {
                start++;
            }
            String cleaned = name.substring(start);
            if (cleaned.endsWith(";")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            if (cleaned.indexOf('/') > 0) {
                into.add(cleaned);
            }
        }
    }
}
