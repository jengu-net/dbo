package cloud.jengu.dbo.spring.worker;

import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.transport.Origin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lane taken up after the application started, the way a site holds one
 * once somebody in the cloud enrolled it.
 *
 * <p>What is asserted is what the container's whiteboard holds, because that
 * is all a runner and a node beside it ever see: a lane to offer work from,
 * and an origin to keep a place up to date from. Nothing answers at the far
 * end, which is not in question here.
 */
class ALaneIsAttachedWhileTheApplicationRunsIT {

    private static final String TENANT = "haru";
    private static final String NAMED = "(dbo.lane.tenant=" + TENANT + ")";

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DboWorkerAutoConfiguration.class))
            .withUserConfiguration(AnApplication.class)
            .withPropertyValues("dbo.worker.identity.name=a-site");

    @Test
    @DisplayName("a lane attached while the application runs is held, keeps a place up to date "
            + "only while told to, and is let go when told")
    @Proving(DboPromises.PROC_A_LANE_IS_ATTACHED_WHILE_THE_APPLICATION_RUNS)
    void aLaneIsAttachedAndLetGo() {
        application.run(context -> {
            DboWorker worker = context.getBean(DboWorker.class);
            EmbeddedRuntime runtime = context.getBean(EmbeddedRuntime.class);
            assertTrue(runtime.lookup().all(Lane.class, NAMED).isEmpty());

            DboWorker.Attached attached = worker.attach(TENANT,
                    URI.create("http://127.0.0.1:9/t/haru/"), () -> "a token asked for each time");

            assertEquals(1, runtime.lookup().all(Lane.class, NAMED).size(),
                    "an attached lane is not where a runner looks for one");
            assertTrue(worker.lanes().contains(TENANT));
            assertTrue(runtime.lookup().all(Origin.class, NAMED).isEmpty(),
                    "a lane kept a place up to date without being told to");

            attached.sync(true);
            List<Origin> origins = runtime.lookup().all(Origin.class, NAMED);
            assertEquals(1, origins.size(), "turned on, the place has no origin to read");
            assertEquals(TENANT, origins.get(0).tenant());

            attached.sync(false);
            assertTrue(runtime.lookup().all(Origin.class, NAMED).isEmpty(),
                    "turned off, the place still reads its origin");
            assertEquals(1, runtime.lookup().all(Lane.class, NAMED).size(),
                    "turning the place off took the work away with it");

            attached.detach();
            assertTrue(runtime.lookup().all(Lane.class, NAMED).isEmpty(),
                    "a lane let go is still offered work from");
            assertFalse(worker.lanes().contains(TENANT));
        });
    }

    @Test
    @DisplayName("a worker given a keys directory holds the keys made or found there, says their "
            + "public halves for its enrolment, and every lane it attaches holds them; a worker "
            + "given none attaches a lane that refuses a secret before asking for one")
    @Proving(DboPromises.TEN_A_WORKER_HOLDS_ITS_KEYS_AND_REACHES_ITS_STEPS_SECRETS)
    void aWorkerHoldsItsKeys() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dbo-worker-keys");
        application.withPropertyValues("dbo.worker.identity.keys-dir=" + dir).run(context -> {
            DboWorker worker = context.getBean(DboWorker.class);
            cloud.jengu.dbo.core.api.seal.ParticipantKeys made =
                    cloud.jengu.dbo.core.api.seal.ParticipantKeys.adopt(dir);
            DboWorker.PublicKeys said = worker.publicKeys().orElseThrow(
                    () -> new AssertionError("a worker given keys says it holds none"));
            assertEquals(made.sealingKey(), said.sealing());
            assertEquals(made.signingKey(), said.signing());

            worker.attach(TENANT, URI.create("http://127.0.0.1:9/t/haru/"), () -> "t");
            Lane lane = context.getBean(EmbeddedRuntime.class).lookup().all(Lane.class, NAMED)
                    .get(0);
            RuntimeException asked = org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class, () -> lane.secret(null, "x.a"));
            assertFalse(String.valueOf(asked.getMessage()).contains("holds no key"),
                    "a lane attached by a worker holding keys holds none: " + asked);
        });
        application.run(context -> {
            DboWorker worker = context.getBean(DboWorker.class);
            assertTrue(worker.publicKeys().isEmpty());
            worker.attach(TENANT, URI.create("http://127.0.0.1:9/t/haru/"), () -> "t");
            Lane lane = context.getBean(EmbeddedRuntime.class).lookup().all(Lane.class, NAMED)
                    .get(0);
            RuntimeException asked = org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class, () -> lane.secret(null, "x.a"));
            assertTrue(String.valueOf(asked.getMessage()).contains("holds no key"),
                    asked.toString());
        });
    }

    @Configuration
    @EnableDboWorker
    static class AnApplication {
    }
}
