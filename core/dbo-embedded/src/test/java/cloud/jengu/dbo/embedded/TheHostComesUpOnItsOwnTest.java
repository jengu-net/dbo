package cloud.jengu.dbo.embedded;

import cloud.jengu.dbo.core.api.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host boots, shares one class space, and refuses a short set by name —
 * answered by this module about itself.
 *
 * <p>The set is this module's test-scope one: {@code dbo-core} alone, the
 * smallest bundle that resolves without another, with its exports as the
 * shared list. The assertions are the floor every assembly stands on, and
 * they belong beside the code that computes the package list, which is where
 * the characteristic defect would be made.
 */
class TheHostComesUpOnItsOwnTest {

    private EmbeddedRuntime runtime;

    @AfterEach
    void down() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    @DisplayName("the bundle the set names is found on the classpath, installed from a stream "
            + "and running")
    void theSetComesUp(@TempDir Path storage) {
        runtime = new EmbeddedRuntime(getClass().getClassLoader(), Map.of(), storage);
        runtime.start();

        assertTrue(runtime.running(), "the container is not ACTIVE after starting");
        assertEquals(Map.of("cloud.jengu.dbo.core", "running"), runtime.states());
    }

    @Test
    @DisplayName("the class the application compiles against and the class the container wires "
            + "to are the same class")
    void oneClassSpace(@TempDir Path storage) throws Exception {
        runtime = new EmbeddedRuntime(getClass().getClassLoader(), Map.of(), storage);
        runtime.start();

        Class<?> asTheContainerMeansIt = runtime.bundles().get("cloud.jengu.dbo.core")
                .loadClass(ObjectStore.class.getName());

        assertSame(ObjectStore.class, asTheContainerMeansIt,
                "the container's " + ObjectStore.class.getName() + " is a different class from "
                        + "the application's, so anything implementing it on one side is "
                        + "invisible to the other");
    }

    @Test
    @DisplayName("a bundle set naming something no jar carries is refused by name, rather than "
            + "failing later as a missing package in some other bundle")
    void aShortSetIsRefusedByName(@TempDir Path storage) {
        ClassLoader noJars = new ClassLoader(null) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) {
                return name.endsWith("bundles.index")
                        ? java.util.Collections.enumeration(java.util.List.of(
                                TheHostComesUpOnItsOwnTest.class
                                        .getResource("/META-INF/dbo/bundles.index")))
                        : java.util.Collections.emptyEnumeration();
            }
        };

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new EmbeddedRuntime(noJars, Map.of(), storage).start());

        assertTrue(refused.getMessage().contains("cloud.jengu.dbo.core"),
                "the refusal does not name what is missing: " + refused.getMessage());
    }
}
