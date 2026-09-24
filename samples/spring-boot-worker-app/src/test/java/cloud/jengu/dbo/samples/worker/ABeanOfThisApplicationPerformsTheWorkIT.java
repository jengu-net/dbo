package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.samples.server.ServerApplication;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.proving.Proves;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bean of this application performs a tenant's work, over the lane.
 *
 * <p>The assembly's own test proves a bean implementing {@code StepService}
 * has its work performed, over an in-process lane with no store and no tenant.
 * This proves the thing an integrator is asking instead: that THIS
 * application, configured the way its {@code application.yaml} configures it,
 * takes work from a real tenant and answers.
 *
 * <p><b>Asserted on WHO performed it, not on whether it ran.</b> The step
 * service is a bean in this context, so the container's whiteboard can see it
 * — and a run driven locally and one delivered over the lane look identical
 * from inside the step. What tells them apart is the executor the run names,
 * which is the identity this application declared: a run recorded against
 * {@code sample-admissions-worker} was performed by this worker, and by
 * nothing else that happened to be in the room.
 *
 * <p><b>One JVM, two applications, and the HTTP is real.</b> The serving
 * application and this one are separate processes in a deployment; collapsing
 * them into one context is a testing economy, not the shape. What is not
 * collapsed is the lane — {@code DboWorker} builds an {@code HttpLane} and
 * nothing else, so the work still leaves over a port and comes back.
 */
@DboSpringBootTest
@ActiveProfiles("test")
@SpringBootTest(classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(AdmittingAPatient.class)
class ABeanOfThisApplicationPerformsTheWorkIT {

    private static final String TENANT = "hogwarts";

    /** What this application declared itself to be, in application.yaml. */
    private static final String THIS_WORKER = "sample-admissions-worker";

    private static final String STEP = "hogwarts.admission.admit";

    @Autowired
    DboTestContext dbo;

    @Test
    @DisplayName("work asked of a tenant is performed by the bean in this application, and the "
            + "run names this application as the executor that did it")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void aBeanPerformsTheWork() throws Exception {
        assertTrue(dbo.until(TENANT, true, Duration.ofMinutes(6)),
                "the tenant never came up, so there is no work to perform: " + dbo.serving());

        // The container found the step service, which is the half an
        // application controls: a bean, and an interface.
        Proves.that(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
                dbo.performing().containsKey(STEP),
                "this application does not perform " + STEP + ", so a bean implementing "
                        + "StepService reached nothing: " + dbo.performing());

        // Something to admit. The slot takes a Patient, so one has to exist
        // before a run can name it.
        var patient = dbo.write(TENANT, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"RL-7001"}]}""");
        assertTrue(patient.accepted(),
                "the patient this run is about was not accepted: " + patient.body());

        long before = dbo.asking(TENANT).work().by(THIS_WORKER).count();

        String started = startARun(patient.idOrFail());
        assertTrue(started.contains("\"run\""),
                "the store did not start a run: " + started);

        // And now it is asked for, which is the whole of what this
        // application does: the runner polls the lane, takes the work,
        // performs it in the bean above and answers.
        dbo.startWorking();
        Proves.that(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE, untilPerformed(before),
                "no run names " + THIS_WORKER + " as its executor, so either the lane delivered "
                        + "nothing or something else performed it — and a step that ran is not "
                        + "the same claim as a step this application ran");
    }

    /** Asks the tenant for a run of the step this application performs. */
    private String startARun(String patientId) {
        var request = HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(TENANT) + "/step/" + STEP))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"inputs\":{\"patient\":\"Patient/" + patientId + "\"}}"));
        // THE WORK CREDENTIAL, which is not the records one: a token
        // admitted at this surface is refused by the tenant's records door,
        // and holding one is deliberately not holding the store.
        var answered = dbo.send(request, dbo.workToken(TENANT));
        // 201: asking for a run CREATES one, and what comes back names it.
        assertEquals(201, answered.statusCode(),
                "the tenant would not start a run of " + STEP + ": " + answered.body());
        return answered.body();
    }

    /**
     * Waits for a run this application performed.
     *
     * <p>Counted rather than matched by id: what is being asserted is that a
     * run names this executor, and the run's own id is the store's to choose.
     */
    private boolean untilPerformed(long before) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < giveUp) {
            if (dbo.asking(TENANT).work().by(THIS_WORKER).count() > before) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
