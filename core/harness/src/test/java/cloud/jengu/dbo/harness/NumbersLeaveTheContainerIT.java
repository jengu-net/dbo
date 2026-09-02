package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exporter is found inside the container, not only on a classpath.
 *
 * <p>The seam looks its exporter up through ServiceLoader from inside its
 * own bundle, and inside OSGi that lookup finds nothing unless the framework
 * mediates it. So a runtime could install the exporter, resolve cleanly, and
 * discard every number in silence — the quietest form of this repository's
 * characteristic failure. This boots the production bundles the distribution
 * ships, points the seam at a collector through a framework property, and
 * asks the seam's own class what it found.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumbersLeaveTheContainerIT {

    static Framework framework;
    static HttpServer collector;
    static final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    void up() throws Exception {
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/metrics", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();

        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", FelixStorage.directory("dbo-telemetry-felix"));
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        config.put("dbo.telemetry.otlp.endpoint",
                "http://127.0.0.1:" + collector.getAddress().getPort());
        framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.start();
        BundleContext ctx = framework.getBundleContext();
        ctx.installBundle("file:" + System.getProperty("spifly.jar"));
        ctx.installBundle("file:" + System.getProperty("slf4j.api.jar"));
        ctx.installBundle("file:" + System.getProperty("dbo.logging.jar")).start();
        for (String prop : List.of("dbo.telemetry.jar", "dbo.telemetry.otlp.jar")) {
            ctx.installBundle("file:" + System.getProperty(prop)).start();
        }
        for (Bundle bundle : ctx.getBundles()) {
            if (bundle.getState() != Bundle.ACTIVE && bundle.getState() != Bundle.RESOLVED) {
                bundle.start();
            }
        }
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10_000);
        }
        collector.stop(0);
    }

    @Test
    @DisplayName("inside the container the seam finds the installed exporter, and a number "
            + "emitted there reaches the collector the framework property named")
    @Proving(DboPromises.OPS_NUMBERS_LEAVE_THE_NODE)
    void theSeamFindsTheExporterInsideTheContainer() throws Exception {
        Bundle seam = null;
        for (Bundle bundle : framework.getBundleContext().getBundles()) {
            if ("cloud.jengu.dbo.telemetry".equals(bundle.getSymbolicName())) {
                seam = bundle;
            }
        }
        assertTrue(seam != null && seam.getState() == Bundle.ACTIVE, "the seam bundle is up");
        Class<?> telemetry = seam.loadClass("cloud.jengu.dbo.telemetry.Telemetry");
        Object installed = telemetry.getMethod("installed").invoke(null);
        assertNotEquals("cloud.jengu.dbo.telemetry.Telemetry$Discarding",
                installed.getClass().getName(),
                "the lookup inside the bundle found nothing, so every number a deployment "
                        + "configured an exporter for would be discarded in silence");
        assertEquals("cloud.jengu.dbo.telemetry.otlp.OtlpTelemetry", installed.getClass().getName());

        Class<?> labels = seam.loadClass("cloud.jengu.dbo.telemetry.Labels");
        Object none = labels.getMethod("none").invoke(null);
        Method counted = telemetry.getMethod("counted", String.class, long.class, labels);
        counted.invoke(installed, "dbo.container.proof", 7L, none);
        Object accepted = installed.getClass().getMethod("flush").invoke(installed);
        assertEquals(Boolean.TRUE, accepted, "the collector the framework property named took it");
        assertTrue(received.stream().anyMatch(b -> b.contains("\"name\":\"dbo.container.proof\"")
                        && b.contains("\"asInt\":\"7\"")),
                "the number left the container: " + received);
    }
}
