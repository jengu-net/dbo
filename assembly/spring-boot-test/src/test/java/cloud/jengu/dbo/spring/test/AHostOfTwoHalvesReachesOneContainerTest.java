package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.embedded.FrameworkContribution;
import cloud.jengu.dbo.spring.server.DboServerAutoConfiguration;
import cloud.jengu.dbo.spring.worker.DboWorkerAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application that serves tenants and performs their work reaches one
 * container, with both halves' configuration in it.
 *
 * <p><b>Here because it cannot be anywhere else.</b> Each assembly's own tests
 * boot that assembly, so neither can see a host made of both — which is
 * exactly how a whole half's configuration came to be discarded without a test
 * noticing. The worker is a test dependency of this module for that one
 * reason.
 *
 * <p>No container is started. A runtime bean of this test's own satisfies the
 * condition both assemblies declare theirs under, so what is asserted is the
 * wiring — that each half contributes, and that a collected view holds both —
 * rather than Felix, which the container tests already cover.
 */
class AHostOfTwoHalvesReachesOneContainerTest {

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DboServerAutoConfiguration.class, DboWorkerAutoConfiguration.class))
            // Never started: the bean exists so that neither assembly builds
            // the real one, because booting a framework is not what is in
            // question here.
            .withBean(EmbeddedRuntime.class, () -> new EmbeddedRuntime(
                    AHostOfTwoHalvesReachesOneContainerTest.class.getClassLoader(), Map.of(),
                    EmbeddedRuntime.storageUnder(
                            Path.of(System.getProperty("java.io.tmpdir")), "dbo-wiring-only")))
            .withPropertyValues(
                    // own-port, and the worker left still: what is under test is
                    // which configuration reaches the container, and both the
                    // servlet mount and a running lane would want a container that
                    // had actually booted.
                    "dbo.mount=own-port",
                    "dbo.worker.auto-start=false",
                    "dbo.tenants.directory=/a/world",
                    "dbo.auth.kek=a-key",
                    // The value that was being discarded, and the one the
                    // defect was measured against.
                    "dbo.worker.poll=500ms",
                    "dbo.worker.identity.name=a-worker");

    @Test
    @DisplayName("both halves of one host contribute, and the collected view carries the "
            + "performing half's dials beside the serving half's world")
    void bothHalvesContribute() {
        application.run(host -> {
            Map<String, FrameworkContribution> pieces =
                    host.getBeansOfType(FrameworkContribution.class);
            assertEquals(2, pieces.size(),
                    "a host of two halves contributed " + pieces.size() + " configurations, so "
                            + "one of them reaches the container by luck: " + pieces.keySet());

            Map<String, String> container = FrameworkContribution.merged(pieces.values());
            assertEquals("500", container.get("dbo.runner.poll.millis"),
                    "the worker's configured poll is not what the container would be told, "
                            + "which is the defect: read, converted, and dropped with the bean "
                            + "nobody created — " + container);
            assertEquals("/a/world", container.get("dbo.tenant.dir"),
                    "the serving half's world is missing: " + container);
            assertTrue(container.containsKey("dbo.tenant.auth.kek"),
                    "the serving half's key is missing, and it is the one property without which "
                            + "a tenant cannot come up at all: " + container);
        });
    }

    @Test
    @DisplayName("one container, however many halves the host has")
    void thereIsOneContainer() {
        application.run(host -> assertEquals(1,
                host.getBeansOfType(EmbeddedRuntime.class).size(),
                "a host of two halves holds more than one container, and each holds a copy of "
                        + "every bundle: " + host.getBeansOfType(EmbeddedRuntime.class).keySet()));
    }
}
