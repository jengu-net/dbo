package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a runner says each cycle: its counts per step under {@code dbo.runner},
 * what its contributors add under their own names, and a declaration only
 * when there is something new to declare.
 */
class TheRunnerReportsItsCountsInItsHeartbeatTest {

    private static final String STEP = "lab.result.verify";

    /** What the worker says about the line it routes, nested. */
    private static final HeartbeatStatistics LINE = new HeartbeatStatistics() {
        @Override
        public String namespace() {
            return "example.line";
        }

        @Override
        public Map<String, Object> statistics() {
            return Map.of("behind", List.of(Map.of("id", "line-1", "queued", 2L)));
        }
    };

    @Test
    @DisplayName("each cycle's heartbeat carries the runner's counts per step under dbo.runner, "
            + "beside a contributor's own namespace")
    @Proving(DboPromises.PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT)
    void theCountsRideTheHeartbeat() {
        ProvingLane lane = ProvingLane.offering(STEP).lane();
        StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
                .register(StepService.performing(STEP, work -> Outcome.done(Map.of())))
                .contributing(LINE)
                .attach(lane);

        runner.cycle();

        assertEquals(ProvingLane.Ended.CLOSED, lane.ended());
        Map<String, Object> last = lane.heartbeats().get(lane.heartbeats().size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) ((Map<String, Object>)
                last.get(StepRunner.RUNNER_STATISTICS)).get(STEP);
        assertEquals(1L, counts.get("performed"), "the step's work is not counted: " + last);
        assertEquals(0L, counts.get("failed"));
        assertTrue(counts.containsKey("meanMillis"), "no mean duration: " + counts);
        assertEquals(LINE.statistics(), last.get("example.line"),
                "the contributor's statistics are not under its namespace: " + last);
    }

    @Test
    @DisplayName("a failure's reason is the last error the heartbeat reports")
    @Proving(DboPromises.PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT)
    void theLastFailureIsItsReason() {
        ProvingLane lane = ProvingLane.offering(STEP).lane();
        new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
                .register(StepService.performing(STEP,
                        work -> Outcome.failed("the line did not answer")))
                .attach(lane)
                .cycle();

        Map<String, Object> last = lane.heartbeats().get(lane.heartbeats().size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) ((Map<String, Object>)
                last.get(StepRunner.RUNNER_STATISTICS)).get(STEP);
        assertEquals(1L, counts.get("failed"));
        assertEquals("the line did not answer", counts.get("lastError"));
    }

    @Test
    @DisplayName("a contributor claiming the store's namespace is refused, by name")
    @Proving(DboPromises.PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT)
    void dboIsTheStores() {
        HeartbeatStatistics squatting = new HeartbeatStatistics() {
            @Override
            public String namespace() {
                return "dbo.mine";
            }

            @Override
            public Map<String, Object> statistics() {
                return Map.of();
            }
        };

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
                        .contributing(squatting));

        assertTrue(refused.getMessage().contains("'dbo.' is the store's"), refused.getMessage());
    }

    @Test
    @DisplayName("a declaration is said once and again only when it did not land, while every "
            + "cycle heartbeats")
    @Proving(DboPromises.PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT)
    void aDeclarationIsNotASignOfLife() {
        List<String> said = new ArrayList<>();
        boolean[] refusing = {true};
        Lane lane = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "tenant":
                            return "hospital";
                        case "identity":
                            return new Executor("verifier", "1", "example.lab",
                                    Scope.BASELINE);
                        case "wakeups":
                            return java.util.Optional.empty();
                        case "poll":
                            return List.of();
                        case "releaseLapsed":
                            return 0;
                        case "declare":
                            said.add("declare");
                            if (refusing[0]) {
                                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                                        "hospital: not answering yet");
                            }
                            return null;
                        case "heartbeat":
                            said.add("heartbeat");
                            return null;
                        default:
                            return null;
                    }
                });
        StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
                .register(StepService.performing(STEP, work -> Outcome.done(Map.of())))
                .attach(lane);
        runner.cycle();
        refusing[0] = false;
        runner.cycle();
        runner.cycle();
        runner.cycle();

        assertEquals(List.of(
                        "declare", "declare", "heartbeat",
                        "declare", "heartbeat",
                        "heartbeat",
                        "heartbeat"),
                said, "attaching declares, a declaration that did not land is said again, and "
                        + "one that landed is not repeated");
    }
}
