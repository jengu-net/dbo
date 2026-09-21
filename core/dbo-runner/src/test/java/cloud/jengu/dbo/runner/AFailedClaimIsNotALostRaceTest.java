package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim that came back empty and a claim that failed are opposite facts:
 * somebody else is holding the run, or nobody could find out. They were one
 * outcome at this call site, and what a caller saw either way was a runner
 * that performed nothing — which is what a quiet tenant looks like.
 *
 * <p>Found from the outside: an external participant was offered the run it
 * had introduced a step for, could not take it, and reported silence.
 */
class AFailedClaimIsNotALostRaceTest {

    @Test
    @DisplayName("a claim that fails says why, and the cycle goes on to the next run")
    @Proving(DboPromises.PROC_A_FAULT_THE_CALLER_IS_NOT_TOLD_IS_STILL_RECORDED)
    void aFailedClaimSaysWhyAndTheCycleGoesOn() {
        RecordingLogs.clear();

        ProvingLane offered = ProvingLane.offering("a.process.step")
                .with("thing", "Basic", "{\"resourceType\":\"Basic\"}").lane();

        // The store is there and the run is offered; taking it is what fails.
        java.util.concurrent.atomic.AtomicBoolean refusing =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Lane unreachable = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    if ("claim".equals(method.getName()) && refusing.get()) {
                        throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                                "a-tenant: claim did not complete (500)");
                    }
                    return method.invoke(offered, arguments);
                });

        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(1))
                .register(new StepService() {
                    @Override
                    public String step() {
                        return "a.process.step";
                    }

                    @Override
                    public Outcome perform(Work work) {
                        return Outcome.done(Map.of("did", 1L));
                    }
                })
                .attach(unreachable);

        assertEquals(0, runner.cycle(), "a run nobody could claim was reported as performed");
        String logged = String.join("\n", RecordingLogs.events());
        assertTrue(logged.contains("claim failed"),
                "the claim failed and the cycle said nothing: " + logged);
        assertTrue(logged.contains("500"),
                "the cycle said a claim failed and not why: " + logged);

        // And the cycle is not over: the same runner takes the run once the
        // store answers again, which a throw that escaped the loop would have
        // cost a whole beat.
        refusing.set(false);
        assertEquals(1, runner.cycle(), "the run was never taken after the store came back");
        runner.close();
    }
}
