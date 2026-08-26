package cloud.jengu.dbo.promise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor is proven the way it runs: a fixture catalogue compiled
 * through it, and the registry loading what the compilation left behind.
 */
class CatalogueProcessorTest {

    private static final String FIXTURE = """
            package fixture;
            import cloud.jengu.dbo.promise.Catalogue;
            import cloud.jengu.dbo.promise.Promise;
            @Catalogue(namespace = "REQ-FIX")
            public enum FixturePromises implements Promise {
                COMPILED_PROMISE;
                @Override public String text() { return "registered at compile time"; }
            }
            """;

    @Test
    @DisplayName("compiling an annotated catalogue leaves the registration index in the output")
    void registrationIsWrittenAtCompileTime() throws Exception {
        Path out = compiled("fixture.FixturePromises", FIXTURE);
        Path index = out.resolve(Registry.INDEX);
        assertTrue(Files.exists(index), "the processor writes " + Registry.INDEX);
        assertEquals(List.of("fixture.FixturePromises"), Files.readAllLines(index));
    }

    @Test
    @DisplayName("the registry loads the compiled catalogue whole, from the index alone")
    void registryLoadsWhatCompilationLeftBehind() throws Exception {
        Path out = compiled("fixture.FixturePromises", FIXTURE);
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] {out.toUri().toURL()},
                CatalogueProcessorTest.class.getClassLoader())) {
            Registry registry = Registry.load(loader);
            assertEquals(1, registry.catalogues().size());
            Registry.Model model = registry.model();
            assertEquals(List.of("REQ-FIX-COMPILED-PROMISE"),
                    model.promises().stream().map(model::codeOf).toList());
        }
    }

    @Test
    @DisplayName("@Catalogue on a non-enum is a compile error, not a runtime surprise")
    void nonEnumIsACompileError() throws Exception {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok = compile("fixture.NotAnEnum", """
                package fixture;
                import cloud.jengu.dbo.promise.Catalogue;
                @Catalogue(namespace = "REQ-FIX")
                public class NotAnEnum {}
                """, Files.createTempDirectory("promise-proc"), diagnostics);
        assertFalse(ok, "a class is not a catalogue");
        assertTrue(diagnostics.getDiagnostics().stream()
                        .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                                && d.getMessage(null).contains("enums only")),
                diagnostics.getDiagnostics().toString());
    }

    private static Path compiled(String name, String source) throws IOException {
        Path out = Files.createTempDirectory("promise-proc");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        assertTrue(compile(name, source, out, diagnostics),
                diagnostics.getDiagnostics().toString());
        return out;
    }

    private static boolean compile(String name, String source, Path out,
            DiagnosticCollector<JavaFileObject> diagnostics) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, null)) {
            JavaFileObject unit = new SimpleJavaFileObject(
                    URI.create("string:///" + name.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return source;
                }
            };
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics,
                    List.of("-d", out.toString(),
                            "-cp", System.getProperty("java.class.path")),
                    null, List.of(unit));
            task.setProcessors(List.of(new CatalogueProcessor()));
            return task.call();
        }
    }
}
