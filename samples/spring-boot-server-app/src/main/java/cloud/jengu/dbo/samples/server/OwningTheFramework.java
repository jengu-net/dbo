package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.embedded.DboFramework;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The clinic's own OSGi framework, which the store installs into.
 *
 * <p>The clinic ships a driver for its ward thermometers as a bundle, and a
 * bundle performing a step belongs in the same class space as the runner that
 * offers it work. So when {@code clinic.framework.owned} is set, the
 * application creates the one framework itself, with the properties the store
 * names beside its own, installs its bundle, and publishes the framework as a
 * bean; the assemblies see the bean and install the store into it instead of
 * creating one.
 *
 * <p>The order is the application's: its bundle is installed and started
 * here, before the store's, and resolves against the packages the framework
 * shares from the start. The store takes its own bundles out again when the
 * context closes, and this bean's destroy method stops the framework after
 * that, because Spring closes a bean's dependants first.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "clinic.framework.owned", havingValue = "true")
public class OwningTheFramework {

    // --8<-- [start:framework]
    @Bean(destroyMethod = "stop")
    public Framework clinicFramework(DboFramework dbo) throws BundleException, IOException {
        Map<String, String> properties = new HashMap<>(dbo.properties());
        properties.put("org.osgi.framework.storage",
                Files.createTempDirectory("clinic-framework").toString());
        properties.put("org.osgi.framework.storage.clean", "onFirstInit");
        Framework framework = ServiceLoader.load(FrameworkFactory.class).findFirst()
                .orElseThrow().newFramework(properties);
        framework.start();

        try (InputStream driver = getClass().getResourceAsStream("/bundles/ward-thermometer.jar")) {
            framework.getBundleContext().installBundle("clinic:ward-thermometer", driver).start();
        }
        return framework;
    }
    // --8<-- [end:framework]
}
