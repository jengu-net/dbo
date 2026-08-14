package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REQ-DBO-CONT-EMBEDDED-IN-JVM groundwork: the production jars are valid OSGi
 * bundles that install, resolve and start in an in-JVM Felix. (Container
 * wiring — tenant service sets — is a later slice; this proves packaging.)
 */
class FelixPackagingIT {

    @Test
    void coreAndPostgresJarsResolveAsBundlesInFelix() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", Files.createTempDirectory("dbo-felix").toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");

        Framework framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.start();
        try {
            BundleContext ctx = framework.getBundleContext();
            Bundle core = ctx.installBundle("file:" + System.getProperty("dbo.core.jar"));
            Bundle postgres = ctx.installBundle("file:" + System.getProperty("dbo.postgres.jar"));
            core.start();
            postgres.start();
            assertEquals(Bundle.ACTIVE, core.getState(), "dbo-core did not resolve/start");
            assertEquals(Bundle.ACTIVE, postgres.getState(), "dbo-postgres did not resolve/start");
            // and the API is loadable through the bundle wiring
            postgres.loadClass("cloud.jengu.dbo.postgres.PgObjectStore");
            core.loadClass("cloud.jengu.dbo.core.api.ObjectStore");
        } finally {
            framework.stop();
            framework.waitForStop(10_000);
        }
    }
}
