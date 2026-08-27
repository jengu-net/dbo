package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REQ-DBO-CORE-PARAMETERIZED-SQL as a ratchet: no class in dbo-postgres may
 * reference {@code createStatement} — raw java.sql.Statement is the vehicle
 * for concatenated SQL, and PreparedStatement covers everything including
 * DDL. A constant-pool scan keeps the rule mechanical instead of a review
 * note.
 */
class SqlDisciplineTest {

    @Test
    @Proving(DboPromises.CORE_PARAMETERIZED_SQL)
    void noClassInDboPostgresUsesRawStatements() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String prop : List.of("dbo.postgres.jar", "dbo.terminology.jar", "dbo.sync.jar", "dbo.maintenance.jar")) {
            scanJar(Path.of(System.getProperty(prop)), offenders);
        }
        assertEquals(List.of(), offenders, "raw Statement usage detected");
    }

    private static void scanJar(Path jar, List<String> offenders) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    String bytesAsLatin1 = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    if (bytesAsLatin1.contains("createStatement")) {
                        offenders.add(e.getName());
                    }
                }
            }
        }
    }

    /** dbo-core stays framework-free: its jar must not reference SQL, JSON libs, or frameworks at all. */
    @Test
    @Proving(DboPromises.CONT_FRAMEWORK_FREE_CORE)
    void dboCoreReferencesNoExternalLibraries() throws Exception {
        Path jar = Path.of(System.getProperty("dbo.core.jar"));
        List<String> offenders = new ArrayList<>();
        List<String> forbidden = List.of(
                "org/springframework", "com/fasterxml", "org/osgi", "java/sql", "jakarta/");
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    for (String f : forbidden) {
                        if (bytes.contains(f)) {
                            offenders.add(e.getName() + " -> " + f);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }
}
