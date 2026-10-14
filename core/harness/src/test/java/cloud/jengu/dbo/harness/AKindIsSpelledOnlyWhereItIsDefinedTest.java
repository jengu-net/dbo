package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ratchet: a tenant's kind is spelled in one place.
 *
 * <p>Behaviour that depends on what kind of tenant this is was once re-derived
 * at each call site, and where the derivation was wrong nothing said so. The
 * runtime asks the facts a tenant publishes instead; a kind is a label for
 * something outside to select by. A kind spelled anywhere but its registry is
 * a comparison growing back under a new spelling, so it fails here.
 */
class AKindIsSpelledOnlyWhereItIsDefinedTest {

    /**
     * A comparison against a kind, however it is written: an equals either
     * way round, a case label, a membership test. Not the spelling alone —
     * {@code dbo.tenant} and {@code dbo.face} are also the runtime's logger
     * names, and naming a logger asks nothing about any tenant.
     */
    private static final Pattern A_KIND = Pattern.compile(
            "(equals(IgnoreCase)?\\(\\s*|case\\s+|contains\\(\\s*)\"dbo\\.(tenant|face|projection)\""
                    + "|\"dbo\\.(tenant|face|projection)\"\\s*\\.equals");
    private static final String REGISTRY = "TenantKinds.java";

    @Test
    @DisplayName("no production source but the kinds' own registry compares a tenant with a "
            + "dbo. kind")
    @Proving(DboPromises.TEN_A_TENANT_SAYS_WHAT_KIND_IT_IS)
    void aKindIsSpelledOnce() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/dbo-operator"))) {
            root = root.getParent();
        }
        assertEquals(true, root != null, "no repository above the working directory");
        Path repository = root;
        List<String> spelled = new ArrayList<>();
        for (Path sources : List.of(repository.resolve("core"), repository.resolve("samples"),
                repository.resolve("assembly"))) {
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sources)) {
                files.filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> file.toString().contains("/src/main/"))
                        .filter(file -> !file.getFileName().toString().equals(REGISTRY))
                        .forEach(file -> {
                            try {
                                if (A_KIND.matcher(Files.readString(file)).find()) {
                                    spelled.add(repository.relativize(file).toString());
                                }
                            } catch (IOException unreadable) {
                                spelled.add(file + " (unreadable)");
                            }
                        });
            }
        }
        assertEquals(List.of(), spelled, "a dbo. kind is spelled outside " + REGISTRY
                + ": ask the fact the code needs, or name the kind through TenantKinds");
    }
}
