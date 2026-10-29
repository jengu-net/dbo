package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lane let go while the far side is already gone.
 *
 * <p>The first withdrawal threw out of the detach, into the framework
 * stopping the host, which logged it as an error, and the steps after it were
 * never offered back at all.
 */
class AHostStoppingAsksTheGoneFarSideOnceTest {

    @Test
    @DisplayName("a lane let go with its far side gone is asked once, says what was not "
            + "delivered, and lets the host go on stopping")
    @Proving(DboPromises.PROC_A_HOST_STOPPING_WITHDRAWS_WHILE_ITS_LANES_STILL_CARRY)
    void aGoneFarSideIsAskedOnce() {
        RecordingLogs.clear();
        AtomicInteger asked = new AtomicInteger();
        Lane gone = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> switch (
                        method.getName()) {
                    case "tenant" -> "a-tenant";
                    case "toString" -> "a lane whose far side is gone";
                    case "wakeups" -> java.util.Optional.empty();
                    case "identity" -> new cloud.jengu.dbo.work.Executor("ward-worker", "1",
                            "the ward", cloud.jengu.dbo.work.Scope.BASELINE);
                    case "declare", "introduce" -> null;
                    case "withdraw" -> {
                        asked.incrementAndGet();
                        throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                                "a-tenant: no door is open on the stream");
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(1))
                .register(StepService.performing("ward.round.check", work -> Outcome.done()))
                .register(StepService.performing("ward.round.report", work -> Outcome.done()))
                .attach(gone);

        runner.detach(gone);

        assertEquals(1, asked.get(), "a far side already gone was asked for every step");
        assertTrue(RecordingLogs.events().stream().anyMatch(event -> event.startsWith(
                        "INFO lane detached: tenant={}, withdrawals not delivered={} "
                                + "[a-tenant, 2]")),
                "the lane's line does not say what was not delivered: " + RecordingLogs.events());
        runner.close();
    }
}
