package cloud.jengu.dbo.embedded;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application that owns its OSGi framework hands it over, and the store
 * lives in it as a guest.
 *
 * <p>Each framework here is the test's: created with whatever properties the
 * case is about, holding a bundle of the application's own, started before the
 * store is asked to come up. What the store may do to it is install, start,
 * stop and uninstall its own set; what it may never do is stop the framework,
 * and that is asserted after every close rather than once.
 */
class AnApplicationMayOwnTheContainerTest {

    /** The application's own bundle, which the store must leave where it found it. */
    private static final String THE_APPLICATIONS = "example.application.driver";

    private Framework framework;
    private EmbeddedRuntime runtime;

    @AfterEach
    void down() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
        if (framework != null) {
            framework.stop();
            framework.waitForStop(30_000);
        }
    }

    @Test
    @Proving(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER)
    @DisplayName("the store installs into a framework the application created with its "
            + "properties, in one class space with the application")
    void theStoreInstallsIntoTheApplicationsFramework(@TempDir Path storage) throws Exception {
        DboFramework dbo = new DboFramework(getClass().getClassLoader(),
                Map.of("dbo.example.dial", "7"));
        framework = started(storage, dbo.properties());
        Bundle mine = install(bundle(THE_APPLICATIONS, null, null));
        mine.start();

        runtime = new EmbeddedRuntime(dbo, framework);
        runtime.start();

        assertTrue(runtime.running(), "the store is not running in the application's framework");
        assertEquals(Map.of("cloud.jengu.dbo.core", "running"), runtime.states());
        Bundle core = runtime.bundles().get("cloud.jengu.dbo.core");
        assertSame(framework, frameworkOf(core),
                "the store's bundle went into some other framework than the one it was handed");
        assertSame(ObjectStore.class, core.loadClass(ObjectStore.class.getName()),
                "the store's bundle holds its own copy of an API class, so the application's "
                        + "framework is two class spaces");
        assertEquals("7", core.getBundleContext().getProperty("dbo.example.dial"),
                "what the application configured did not reach the store's bundles");
    }

    @Test
    @Proving(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER)
    @DisplayName("closing takes out the store's bundles and leaves the application's framework "
            + "running with the application's own bundle in it")
    void closingLeavesTheFrameworkRunning(@TempDir Path storage) throws Exception {
        DboFramework dbo = new DboFramework(getClass().getClassLoader(), Map.of());
        framework = started(storage, dbo.properties());
        Bundle mine = install(bundle(THE_APPLICATIONS, null, null));
        mine.start();
        runtime = new EmbeddedRuntime(dbo, framework);
        runtime.start();
        Bundle core = runtime.bundles().get("cloud.jengu.dbo.core");

        runtime.close();

        assertEquals(Bundle.ACTIVE, framework.getState(),
                "the store stopped a framework it did not create");
        assertEquals(Bundle.ACTIVE, mine.getState(),
                "the application's own bundle is no longer running after the store left");
        assertEquals(Bundle.UNINSTALLED, core.getState(),
                "the store's bundle is still in the application's framework after close");
        assertTrue(Arrays.stream(framework.getBundleContext().getBundles())
                        .noneMatch(b -> "cloud.jengu.dbo.core".equals(b.getSymbolicName())),
                "the application's framework still lists the store's bundle");

        // And the store can come back into the same framework, which is what a
        // context restarted inside one JVM does.
        runtime = new EmbeddedRuntime(dbo, framework);
        runtime.start();
        assertTrue(runtime.running(), "the store could not come back into the framework it left");
    }

    @Test
    @Proving(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER)
    @DisplayName("a framework created without the store's properties is refused, naming every "
            + "shared package and property it lacks, and nothing is installed into it")
    void aFrameworkWithoutThePropertiesIsRefusedByName(@TempDir Path storage) throws Exception {
        DboFramework dbo = new DboFramework(getClass().getClassLoader(),
                Map.of("dbo.example.dial", "7"));
        framework = started(storage, Map.of());
        runtime = new EmbeddedRuntime(dbo, framework);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                runtime::start);

        for (String pkg : dbo.sharedByName().keySet()) {
            assertTrue(refused.getMessage().contains(pkg + " is not exported"),
                    "the refusal does not name " + pkg + ": " + refused.getMessage());
        }
        assertTrue(refused.getMessage().contains("dbo.example.dial is not set"),
                "the refusal does not name the property: " + refused.getMessage());
        assertEquals(1, framework.getBundleContext().getBundles().length,
                "something was installed into a framework the store refused");
        assertEquals(Bundle.ACTIVE, framework.getState());
    }

    @Test
    @Proving(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER)
    @DisplayName("an application bundle exporting a shared package the store would wire to "
            + "instead is refused, naming the bundle and the package")
    void aConflictingExportIsRefusedByName(@TempDir Path storage) throws Exception {
        DboFramework dbo = new DboFramework(getClass().getClassLoader(), Map.of());
        framework = started(storage, dbo.properties());
        // A version the store's import range accepts, higher than the
        // application's own copy: the resolver's preference, not a mistake in
        // the range.
        Bundle conflicting = install(bundle(THE_APPLICATIONS,
                "cloud.jengu.dbo.core.api;version=0.9.0", null));
        conflicting.start();
        runtime = new EmbeddedRuntime(dbo, framework);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                runtime::start);

        assertTrue(refused.getMessage().contains(THE_APPLICATIONS),
                "the refusal does not name the bundle in the way: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("cloud.jengu.dbo.core.api"),
                "the refusal does not name the package: " + refused.getMessage());
        assertFalse(runtime.running());
        assertEquals(Bundle.ACTIVE, framework.getState(),
                "refusing stopped the application's framework");
        assertTrue(Arrays.stream(framework.getBundleContext().getBundles())
                        .noneMatch(b -> "cloud.jengu.dbo.core".equals(b.getSymbolicName())),
                "a refused store left its bundle behind in the application's framework");
    }

    @Test
    @Proving(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER)
    @DisplayName("an application bundle that does not resolve fails the store's start with the "
            + "bundle and the package nothing provides, rather than a container that started "
            + "and in which it does nothing")
    void anUnresolvedApplicationBundleIsNamed(@TempDir Path storage) throws Exception {
        DboFramework dbo = new DboFramework(getClass().getClassLoader(), Map.of());
        framework = started(storage, dbo.properties());
        install(bundle(THE_APPLICATIONS, null, "example.nobody.exports.this"));
        runtime = new EmbeddedRuntime(dbo, framework);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                runtime::start);

        assertTrue(refused.getMessage().contains(THE_APPLICATIONS)
                        && refused.getMessage().contains("example.nobody.exports.this"),
                "the refusal does not name the bundle and what it lacks: "
                        + refused.getMessage());
        assertEquals(Bundle.ACTIVE, framework.getState());
    }

    @Test
    @DisplayName("a framework that is not started is refused rather than started by a party "
            + "that does not own it")
    void anUnstartedFrameworkIsRefused(@TempDir Path storage) throws Exception {
        DboFramework dbo = new DboFramework(getClass().getClassLoader(), Map.of());
        Map<String, String> config = new HashMap<>(dbo.properties());
        config.put("org.osgi.framework.storage", storage.toString());
        framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.init();
        runtime = new EmbeddedRuntime(dbo, framework);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                runtime::start);

        assertTrue(refused.getMessage().contains("not started"), refused.getMessage());
        assertEquals(Bundle.STARTING, framework.getState(),
                "the store moved a framework it does not own");
    }

    private Framework started(Path storage, Map<String, String> properties)
            throws BundleException {
        Map<String, String> config = new HashMap<>(properties);
        config.put("org.osgi.framework.storage", storage.toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        Framework created = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        created.start();
        return created;
    }

    private Bundle install(byte[] jar) throws BundleException {
        return framework.getBundleContext().installBundle("test:" + THE_APPLICATIONS,
                new ByteArrayInputStream(jar));
    }

    private static Framework frameworkOf(Bundle bundle) {
        return (Framework) bundle.getBundleContext().getBundle(0);
    }

    /** A bundle that is a manifest and nothing else: wiring is about headers. */
    private static byte[] bundle(String symbolicName, String exports, String imports)
            throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Bundle-ManifestVersion", "2");
        main.putValue("Bundle-SymbolicName", symbolicName);
        main.putValue("Bundle-Version", "1.0.0");
        if (exports != null) {
            main.putValue("Export-Package", exports);
        }
        if (imports != null) {
            main.putValue("Import-Package", imports);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }
}
