package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.samples.server.ServerApplication;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker inside the deployment is carried by the store's own substrate.
 *
 * <p>The same application and the same bean as the test beside this one, with
 * one word changed: the lane names no base, so it is carried by the database
 * the serving half already runs on rather than by a port. Everything else —
 * a credential against an enrolment, a URL both halves point at — follows from
 * that and is derived.
 *
 * <p><b>What it proves is that a runner cannot tell.</b> The bean is unchanged,
 * the step is unchanged, and the work arrives and is reported the same way. A
 * lane has carriers precisely so that where a worker runs is a deployment's
 * decision rather than an application's.
 *
 * <p><b>And no port is involved in the work.</b> The serving half still opens
 * one, because a tenant's records door is HTTP and this test writes through it
 * — but nothing the worker does goes over it, which is what a participant
 * beside the store should never have needed.
 *
 * <p><b>Its world is released when this class ends.</b> {@code DboSpringBootTest}
 * says to keep {@code dbo.test.*} the same across a module's tests so one
 * context serves them all, and the class beside this one is the case that
 * cannot: the carrier is the single thing it varies, so the two configurations
 * differ and Spring builds a second context without closing the first. Two
 * tenant managers then serve one world over one database — which the store
 * permits, and which this machine does not carry: the second manager's tenants
 * do not finish coming up, and the class that waits for one reports a tenant
 * that never arrived rather than the contention that kept it.
 *
 * <p>So each of the two releases its own, and one world is alive at a time.
 * The cost is a bring-up neither shares, which they were never going to share.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DboSpringBootTest
@ActiveProfiles("substrate")
@SpringBootTest(classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(AdmittingAPatient.class)
class TheWorkArrivesOverTheSubstrateIT {

    private static final String TENANT = "hogwarts";

    private static final String THIS_WORKER = "sample-admissions-worker";

    private static final String STEP = "hogwarts.admission.admit";

    @Autowired
    DboTestContext dbo;

    @Test
    @DisplayName("work asked of a tenant reaches a bean in this application over the substrate, "
            + "with no lane over a port anywhere in it")
    @Proving(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS)
    void theWorkArrivesWithoutAPort() throws Exception {
        assertTrue(dbo.until(TENANT, true, Duration.ofMinutes(6)),
                "the tenant never came up, so there is no work to perform: " + dbo.serving());

        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                dbo.performing().containsKey(STEP),
                "this application does not perform " + STEP + ", so a bean implementing "
                        + "StepService reached nothing: " + dbo.performing());

        var patient = dbo.write(TENANT, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"RL-8001"}]}""");
        assertTrue(patient.accepted(),
                "the patient this run is about was not accepted: " + patient.body());

        long before = dbo.asking(TENANT).work().by(THIS_WORKER).count();

        var asked = dbo.send(HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(TENANT) + "/step/" + STEP))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"inputs\":{\"patient\":\"Patient/" + patient.idOrFail() + "\"}}")),
                dbo.workToken(TENANT));
        assertEquals(201, asked.statusCode(),
                "the tenant would not start a run of " + STEP + ": " + asked.body());

        // The work is asked for through the tenant's door, as work always is,
        // and comes back over a plane that carries no token at all.
        dbo.startWorking();
        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS, untilPerformed(before),
                "no run names " + THIS_WORKER + ", so a lane with no base reached nothing — "
                        + "and a worker beside the store is exactly the one that should not have "
                        + "needed a port");
    }

    private boolean untilPerformed(long before) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            if (dbo.asking(TENANT).work().by(THIS_WORKER).count() > before) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }
}
