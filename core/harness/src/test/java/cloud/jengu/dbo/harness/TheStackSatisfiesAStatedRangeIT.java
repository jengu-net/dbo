package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A consumer states the version of FHIR it was built against, and this bundle
 * either satisfies it or says so (#47).
 *
 * <p>A package exported without a version is exported at <b>0.0.0</b>, which is
 * in no stated range — and bnd computes a range for every consumer by default,
 * so most of them state one. Unversioned, this bundle cannot wire to a driver
 * SPI that names the HAPI it compiled against, and the arrangement where the
 * store owns the FHIR classes does not come up at all.
 *
 * <p>Both halves are asserted, because only one of them is a happy path: a
 * consumer on this HAPI resolves, and a consumer on another major is refused
 * <b>by name</b> rather than wired to classes it was not built for.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheStackSatisfiesAStatedRangeIT {

    static Framework framework;
    static BundleContext context;
    static Path work;

    @BeforeAll
    void up() throws Exception {
        work = Files.createTempDirectory("dbo-stack-ranges");
        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", work.resolve("felix").toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        framework = java.util.ServiceLoader.load(FrameworkFactory.class).iterator().next()
                .newFramework(config);
        framework.start();
        context = framework.getBundleContext();
        // The stack imports the logging API from the shared bundle rather than
        // embedding a copy — an embedded one splits org.slf4j in two and
        // surfaces as a LinkageError the first time something logs.
        // The logging arrangement the distribution ships, in its order: the
        // ServiceLoader mediator is a framework extension and attaches to the
        // system bundle, then the API, then the binding that provides its
        // serviceloader capability. Without the binding, slf4j-api does not
        // resolve and neither does anything that logs.
        context.installBundle("file:" + System.getProperty("spifly.jar"));
        context.installBundle("file:" + System.getProperty("slf4j.api.jar"));
        context.installBundle("file:" + System.getProperty("dbo.logging.jar")).start();
        // Installed, not started. What is being asserted is a wire, and a wire
        // is made at the consumer's resolution — starting the exporter would
        // unpack ninety megabytes of embedded stack into the framework cache
        // for nothing, in a JVM shared with every other container test.
        context.installBundle("file:" + System.getProperty("dbo.fhir.stack.jar"));
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10_000);
        }
        // The framework cache holds whatever it unpacked; a test that leaves it
        // behind charges the next one for it.
        try (java.util.stream.Stream<Path> paths = Files.walk(work)) {
            paths.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(File::delete);
        }
        // Released, not merely stopped. stop() ends the framework's threads;
        // it does not drop the object graph, and a static field keeps the
        // bundle CLASSLOADERS alive with everything their statics hold --
        // for the element bundle that is a full set of parsed FHIR
        // definitions, ~100-215MB per container. Measured: three stopped
        // frameworks and eleven bundle classloaders were still reachable
        // while ScimProvisioningIT ran, which touches no OSGi at all, and
        // the suite carried four extra definition contexts where three
        // exist to be held.
        framework = null;
        context = null;
    }

    /** A consumer bundle that imports one package at one range, and nothing else. */
    private Bundle consumer(String name, String importPackage) throws Exception {
        Path jar = work.resolve(name + ".jar");
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Bundle-ManifestVersion", "2");
        main.putValue("Bundle-SymbolicName", name);
        main.putValue("Bundle-Version", "1.0.0");
        main.putValue("Import-Package", importPackage);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // nothing inside: what is being tested is the manifest, and a class
            // would only prove the classloader after the wiring this asserts
        }
        return context.installBundle("file:" + jar.toFile().getAbsolutePath());
    }

    @Test
    @DisplayName("a consumer built against this HAPI resolves against the stack")
    void aConsumerOnThisHapiResolves() throws Exception {
        String hapi = System.getProperty("dbo.hapi.version", "8.10.1");
        String major = hapi.substring(0, hapi.indexOf('.'));
        String range = "[" + hapi + "," + (Integer.parseInt(major) + 1) + ")";

        Bundle onThisHapi = consumer("consumer-current",
                "ca.uhn.fhir.context;version=\"" + range + "\"");
        onThisHapi.start();

        assertEquals(Bundle.ACTIVE, onThisHapi.getState(),
                "the driver SPI that names the HAPI it compiled against is the case this "
                        + "exists for");
    }

    @Test
    @DisplayName("the HL7 core is exported at its own version, which is not HAPI's")
    void theTwoFamiliesHaveTwoNumbers() throws Exception {
        String core = System.getProperty("dbo.hl7.core.version", "6.10.2");
        String major = core.substring(0, core.indexOf('.'));
        Bundle onTheCore = consumer("consumer-core", "org.hl7.fhir.r5.model;version=\"["
                + core + "," + (Integer.parseInt(major) + 1) + ")\"");
        onTheCore.start();

        assertEquals(Bundle.ACTIVE, onTheCore.getState(),
                "ca.uhn.fhir.* and org.hl7.fhir.* are two families with two numbers, and a "
                        + "single constant would have made one of them wrong");
    }

    @Test
    @DisplayName("a consumer built against another major is refused, naming the package")
    void aConsumerOnAnotherMajorIsRefused() throws Exception {
        Bundle onAnotherMajor = consumer("consumer-old",
                "ca.uhn.fhir.context;version=\"[5.0.0,6)\"");

        BundleException refused = assertThrows(BundleException.class, onAnotherMajor::start);

        assertTrue(refused.getMessage().contains("ca.uhn.fhir.context"),
                "the refusal has to name the package, or a consumer is left with a bundle "
                        + "that will not start and no idea which import: " + refused.getMessage());
    }
}
