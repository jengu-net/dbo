package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bundle asks for slf4j by generation, so a second one cannot be wired in
 * quietly.
 *
 * <p>An unversioned {@code Import-Package} wires to whatever the framework
 * happens to hold. In this store's own distribution that is the staged
 * slf4j-api and there is nothing else it could be; installed into a host that
 * already provides slf4j, it is whichever copy the resolver prefers — and the
 * loser is not the one that fails, it is the one whose binding is not behind
 * the winner.
 *
 * <p>Which makes this the store's characteristic failure wearing a different
 * hat: nothing throws, every bundle resolves, work proceeds, and the log is
 * empty. A bundle in that state is not quiet, it is inaudible, and from
 * outside those are the same thing. A range does not decide who wins, but it
 * makes a mismatch a resolution failure somebody can read instead of a silence
 * somebody has to debug.
 */
class EverySlf4jImportNamesItsGenerationTest {

    @Test
    @DisplayName("every bundle that imports slf4j says which generation it means")
    @Proving(DboPromises.CONT_IMPORTS_ARE_COMPUTED_OR_CHECKED)
    void noBundleImportsSlf4jUnversioned() throws Exception {
        List<String> unversioned = new ArrayList<>();
        int importers = 0;
        List<Path> jars = bundles();

        for (Path jar : jars) {
            try (JarFile bundle = new JarFile(jar.toFile())) {
                Manifest manifest = bundle.getManifest();
                if (manifest == null) {
                    continue;
                }
                String header = manifest.getMainAttributes().getValue("Import-Package");
                if (header == null) {
                    continue;
                }
                boolean imports = false;
                for (String clause : clauses(header)) {
                    String pkg = clause.split(";")[0].trim();
                    if (!pkg.equals("org.slf4j") && !pkg.startsWith("org.slf4j.")) {
                        continue;
                    }
                    imports = true;
                    if (!clause.contains("version=")) {
                        unversioned.add(jar.getFileName() + " imports " + pkg
                                + " with no version");
                    }
                }
                if (imports) {
                    importers++;
                }
            }
        }

        // Without this the test passes on a staging mistake: no jars, no
        // importers, no findings, green. The thing being guarded is invisible
        // in exactly the way the defect is.
        assertTrue(jars.size() > 10, "almost no bundles staged, so this guards nothing: " + jars);
        assertTrue(importers > 0,
                "no bundle imports slf4j at all, which is not true of this store and means "
                        + "the manifests were not read");

        assertFalse(!unversioned.isEmpty(),
                "an unversioned slf4j import wires to whatever the framework holds. In a host "
                        + "that provides its own slf4j that is a second API bundle with a "
                        + "different binding behind it, and the symptom is a bundle that logs "
                        + "nothing while working perfectly:\n  "
                        + String.join("\n  ", new TreeSet<>(unversioned)));
    }

    /** Every staged bundle of this store's own, including the embedded stack. */
    private static List<Path> bundles() {
        List<Path> jars = new ArrayList<>();
        for (String key : new TreeSet<>(System.getProperties().stringPropertyNames())) {
            if (key.endsWith(".reach.jar") || key.equals("dbo.fhir.stack.jar")) {
                jars.add(Path.of(System.getProperty(key)));
            }
        }
        return jars;
    }

    /** Clauses split on commas outside a quoted attribute value — a range holds one. */
    private static List<String> clauses(String header) {
        List<String> clauses = new ArrayList<>();
        StringBuilder clause = new StringBuilder();
        boolean quoted = false;
        for (char c : header.toCharArray()) {
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
        clauses.add(clause.toString());
        return clauses;
    }
}
