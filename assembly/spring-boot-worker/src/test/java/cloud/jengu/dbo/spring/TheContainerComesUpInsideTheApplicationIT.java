package cloud.jengu.dbo.spring;

import cloud.jengu.dbo.runner.StepService;
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
 * The container comes up inside an ordinary JVM, and shares its classes.
 *
 * <p>This is the assembly's floor. Everything above it — a bean that is a
 * step, a lane read from configuration, a service injected back — rests on
 * two facts, and neither is visible to a build: that the bundles this jar
 * names are ON the classpath and installable from it, and that a class the
 * application holds and a class the container holds are the SAME class.
 *
 * <p><b>The second one is the whole of the arrangement.</b> A Spring bean
 * implements {@code StepService} and the runner's whiteboard, inside the
 * framework, tracks {@code StepService}. Two classes by two classloaders and
 * the bean is never seen, or is seen and fails a cast — which compiles,
 * resolves, and dies on first use. Asserting that every bundle reached ACTIVE
 * says nothing about it: a container where they are two classes starts
 * perfectly.
 *
 * <p>No store, no face, no database, and that absence is part of the claim.
 * A driver contributes a step with five bundles installed, and the container
 * proves the same set from the other side.
 */
class TheContainerComesUpInsideTheApplicationIT {

    private EmbeddedRuntime runtime;

    @AfterEach
    void down() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    @DisplayName("every bundle this assembly names is found on the classpath and installed "
            + "from a stream — the five that run, and the mediator that attaches — with no "
            + "store, no face and no database among them")
    void theBundlesThisAssemblyNamesComeUp(@TempDir Path storage) {
        runtime = up(storage);

        assertTrue(runtime.running(), "the container is not ACTIVE after starting");
        // Read through the report an application would read, rather than
        // through the Bundles behind it: a claim about what the container is
        // doing should be made in the words it says it in.
        Map<String, String> states = runtime.states();
        assertEquals(6, states.size(),
                "installed " + states.keySet() + ", and this assembly names six: five of its "
                        + "own and the ServiceLoader mediator they resolve through");
        // The mediator ATTACHES; it is an extension of the system bundle and
        // has no lifecycle of its own. Asserting it runs would be asserting
        // the wrong thing about the one entry here that is not ours.
        assertEquals("attached", states.get("org.apache.aries.spifly.dynamic.framework.extension"),
                "the mediator is '"
                        + states.get("org.apache.aries.spifly.dynamic.framework.extension")
                        + "'. Nothing that finds a provider through ServiceLoader resolves "
                        + "without it — the telemetry exporter is the one in this set — so a "
                        + "container missing it comes up short a bundle or not at all");
        states.forEach((name, state) -> {
            if (!name.startsWith("org.apache.aries")) {
                assertEquals("running", state,
                        name + " is '" + state + "', so the container took it and could not "
                                + "run it");
            }
        });
        assertTrue(states.keySet().stream()
                        .noneMatch(name -> name.contains("postgres") || name.contains("tenant")
                                || name.contains("fhir")),
                "a worker installed " + states.keySet() + ", and what it is FOR is "
                        + "performing work with no store anywhere near it");
    }

    @Test
    @DisplayName("the class the application compiles against and the class the container wires "
            + "to are the same class, which is what lets a bean be an extension point")
    void theApplicationAndTheContainerHoldOneClass() throws Exception {
        runtime = up(java.nio.file.Files.createTempDirectory("dbo-one-class"));

        org.osgi.framework.Bundle runner = runtime.bundles().get("cloud.jengu.dbo.runner");
        Class<?> asTheContainerMeansIt = runner.loadClass(StepService.class.getName());

        assertSame(StepService.class, asTheContainerMeansIt,
                "the container's " + StepService.class.getName() + " is a different class from "
                        + "the application's, so a bean implementing it is invisible to the "
                        + "whiteboard — which is a container that starts perfectly and performs "
                        + "nothing, with nothing in any log to say why");
    }

    @Test
    @DisplayName("a bundle set naming something no jar carries is refused by name, rather than "
            + "failing later as a missing package in some other bundle")
    void aSetShortABundleIsRefusedByName(@TempDir Path storage) {
        ClassLoader noJars = new ClassLoader(null) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) {
                // The indexes, and no manifests at all: every bundle the set
                // names is missing, which is the shape of a dependency that
                // was excluded rather than one that was never asked for.
                return name.endsWith("bundles.index")
                        ? java.util.Collections.enumeration(java.util.List.of(
                                TheContainerComesUpInsideTheApplicationIT.class
                                        .getResource("/META-INF/dbo/bundles.index")))
                        : java.util.Collections.emptyEnumeration();
            }
        };

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new EmbeddedRuntime(noJars, Map.of(), storage).start());

        assertTrue(refused.getMessage().contains("cloud.jengu.dbo.runner"),
                "the refusal does not name what is missing, so whoever reads it has to go and "
                        + "find out: " + refused.getMessage());
    }

    private static EmbeddedRuntime up(Path storage) {
        EmbeddedRuntime starting = new EmbeddedRuntime(
                TheContainerComesUpInsideTheApplicationIT.class.getClassLoader(),
                // The runner's only dials, and a poll this test never waits on
                // — nothing here offers it work.
                Map.of("dbo.runner.poll.millis", "50"),
                storage);
        starting.start();
        return starting;
    }
}
