package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lane whose door is still coming up when the lane starts.
 *
 * <p>Every start of a place warned once that the door on the stream did not
 * answer, about the first thing each cycle asks, while the start was otherwise
 * normal: the lane had asked before its origin's door was up. A warning on
 * every start is read as nothing, and then so is the one that matters.
 */
class ADoorNotUpYetIsNotAFaultTest {

    @Test
    @DisplayName("a door that has not answered yet is quiet for a few cycles, and said after")
    @Proving(DboPromises.PROC_A_FAULT_THE_CALLER_IS_NOT_TOLD_IS_STILL_RECORDED)
    void aDoorNotUpYetIsQuietThenSaid() {
        RecordingLogs.clear();
        Lane absent = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> switch (
                        method.getName()) {
                    case "tenant" -> "a-tenant";
                    case "toString" -> "a lane with no door yet";
                    case "wakeups" -> java.util.Optional.empty();
                    default -> throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                            "a-tenant: the door on the stream did not answer 'release-lapsed'");
                });
        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(1))
                .attach(absent);

        for (int cycle = 0; cycle < StepRunner.QUIET_AT_START; cycle++) {
            assertEquals(0, runner.cycle());
        }
        assertEquals(0, warnings(), "a door still coming up was warned about: "
                + RecordingLogs.events());

        runner.cycle();
        assertEquals(1, warnings(), "a door that never came up was never said");
        assertTrue(String.join("\n", RecordingLogs.events()).contains("release-lapsed"));
        runner.close();
    }

    private static long warnings() {
        return RecordingLogs.events().stream()
                .filter(event -> event.startsWith("WARN lane cycle failed")).count();
    }
}
