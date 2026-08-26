package cloud.jengu.dbo.promise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The citation half of the processor: a product's own proving annotation is
 * recognised by the {@code @Cites} meta-annotation alone, and every site is
 * indexed at compile time with the constants it names.
 */
class CitationIndexTest {

    private static final String PROMISES = """
            package fixture;
            import cloud.jengu.dbo.promise.Catalogue;
            import cloud.jengu.dbo.promise.Promise;
            @Catalogue(namespace = "REQ-FIX")
            public enum FixturePromises implements Promise {
                PLAIN_PROMISE, BODIED_PROMISE, QUIET_PROMISE;
                @Override public String text() { return name(); }
            }
            """;

    private static final String PROVING = """
            package fixture;
            import cloud.jengu.dbo.promise.Cites;
            import java.lang.annotation.*;
            @Cites
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
            public @interface Proving { FixturePromises[] value(); }
            """;

    private static final String DECOY = """
            package fixture;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
            public @interface Decoy { FixturePromises[] value(); }
            """;

    private static final String SITE = """
            package fixture;
            public class SomeTest {
                @Proving({FixturePromises.PLAIN_PROMISE, FixturePromises.BODIED_PROMISE})
                void provesTwoThings() {}
                @Decoy(FixturePromises.QUIET_PROMISE)
                void looksLikeACitationAndIsNot() {}
            }
            """;

    @Test
    @DisplayName("compiling a citing test writes the proofs index; the decoy is ignored")
    void citationsAreIndexedAtCompileTime() throws Exception {
        Path out = compiled();
        List<String> lines = Files.readAllLines(out.resolve(Proofs.INDEX));
        assertEquals(List.of("fixture.SomeTest#provesTwoThings="
                + "fixture.FixturePromises:PLAIN_PROMISE,BODIED_PROMISE"), lines,
                "the meta-annotation alone decides what counts as a citation");
    }

    @Test
    @DisplayName("the loaded index joins back to the enum constants")
    void proofsLoadResolvesConstants() throws Exception {
        Path out = compiled();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {out.toUri().toURL()},
                CitationIndexTest.class.getClassLoader())) {
            Proofs proofs = Proofs.load(loader);
            Object[] constants = Class.forName("fixture.FixturePromises", true, loader)
                    .getEnumConstants();
            assertTrue(proofs.cited((Promise) constants[0]));
            assertTrue(proofs.cited((Promise) constants[1]));
            assertFalse(proofs.cited((Promise) constants[2]),
                    "the decoy's constant is uncited — nothing recognised the annotation");
            assertEquals(java.util.Set.of("fixture.SomeTest#provesTwoThings"),
                    proofs.citing((Promise) constants[0]));
        }
    }

    private static Path compiled() throws Exception {
        Path out = Files.createTempDirectory("promise-cite");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, null)) {
            List<JavaFileObject> units = List.of(
                    source("fixture.FixturePromises", PROMISES),
                    source("fixture.Proving", PROVING),
                    source("fixture.Decoy", DECOY),
                    source("fixture.SomeTest", SITE));
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics,
                    List.of("-d", out.toString(),
                            "-cp", System.getProperty("java.class.path")),
                    null, units);
            task.setProcessors(List.of(new CatalogueProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        return out;
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return content;
            }
        };
    }
}
