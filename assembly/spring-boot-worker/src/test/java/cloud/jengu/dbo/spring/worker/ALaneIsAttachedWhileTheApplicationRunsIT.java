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

    @Configuration
    @EnableDboWorker
    static class AnApplication {
    }
}
