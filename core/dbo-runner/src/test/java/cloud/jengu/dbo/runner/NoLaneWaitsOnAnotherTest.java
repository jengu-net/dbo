package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Declarations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site's runner holds a lane to the tenant in its cloud, over the WAN, and a
 * lane to its own place of that tenant, in process: two lanes, one tenant
 * code. Neither may wait on the other, and neither may stand in for the other.
 */
class NoLaneWaitsOnAnotherTest {

    private static final String CLOUD_STEP = "edge.fleet.report";
    private static final String LOCAL_STEP = "ward.round.check";

    @Test
    @DisplayName("two lanes to one tenant are both served, and letting one go leaves the other's "
            + "declarations and work standing")
    @Proving(DboPromises.PROC_NO_LANE_WAITS_ON_ANOTHER)
    void twoLanesToOneTenantStayTwo() {
        ProvingLane cloud = ProvingLane.offering(LOCAL_STEP).lane();
        ProvingLane here = ProvingLane.offering(LOCAL_STEP).lane();
        assertEquals(cloud.tenant(), here.tenant(), "the two lanes are meant to share a tenant");
        Said toCloud = new Said();
        Said toHere = new Said();
        Lane cloudLane = saying(cloud, toCloud);
        Lane hereLane = saying(here, toHere);
        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(1))
                .register(StepService.performing(LOCAL_STEP, work -> Outcome.done()))
                .attach(cloudLane)
                .attach(hereLane);

        assertEquals(2, runner.cycle(), "a lane to the same tenant stood in for the other");
        assertEquals(1, cloud.performed(), "the lane to the cloud was not served");
        assertEquals(1, here.performed(), "the lane to the place was not served");

        runner.detach(cloudLane);
        here.offerAgain();

        assertEquals(List.of(LOCAL_STEP), toCloud.withdrawn,
                "letting the cloud's lane go did not withdraw what was declared on it");
        assertEquals(List.of(), toHere.withdrawn,
                "letting the cloud's lane go withdrew what was declared on the place's");
        assertEquals(1, runner.cycle(), "the place's lane went with the cloud's");
        assertEquals(2, here.performed(), "the place's lane went with the cloud's");
        runner.close();
    }

    @Test
    @DisplayName("a step performing over one lane does not delay a run over another")
    @Proving(DboPromises.PROC_NO_LANE_WAITS_ON_ANOTHER)
    void aSlowLaneDelaysNoOther() throws Exception {
        CountDownLatch cloudHolding = new CountDownLatch(1);
        CountDownLatch cloudMayFinish = new CountDownLatch(1);
        CountDownLatch localDone = new CountDownLatch(1);
        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofMillis(50))
                .register(StepService.performing(CLOUD_STEP, work -> {
                    // A round to the cloud that has not come back.
                    cloudHolding.countDown();
                    try {
                        cloudMayFinish.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return Outcome.done();
                }))
                .register(StepService.performing(LOCAL_STEP, work -> {
                    localDone.countDown();
                    return Outcome.done();
                }))
                .attach(ProvingLane.offering(CLOUD_STEP).lane())
                .start();
        try {
            assertTrue(cloudHolding.await(10, TimeUnit.SECONDS),
                    "the cloud's step never began, so nothing here was ever waited behind");

            runner.attach(ProvingLane.offering(LOCAL_STEP).lane());

            assertTrue(localDone.await(10, TimeUnit.SECONDS),
                    "the place's work waited behind a step performing for the cloud");
        } finally {
            cloudMayFinish.countDown();
            runner.close();
        }
    }

    /** What a runner said to one lane about its candidacy. */
    private static final class Said {
        final List<String> withdrawn = new CopyOnWriteArrayList<>();
    }

    /** The lane, taking declarations as a tenant does and keeping what was withdrawn. */
    private static Lane saying(ProvingLane lane, Said said) {
        return (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "declare" -> {
                            return null;
                        }
                        case "withdraw" -> {
                            Declarations.Declared declared = (Declarations.Declared) arguments[0];
                            said.withdrawn.add(declared.process() + "." + declared.step());
                            return null;
                        }
                        default -> {
                            try {
                                return method.invoke(lane, arguments);
                            } catch (InvocationTargetException thrown) {
                                throw thrown.getCause();
                            }
                        }
                    }
                });
    }
}
