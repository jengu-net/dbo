package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.samples.server.ServerApplication;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import cloud.jengu.dbo.spring.test.DboTestContext;
import cloud.jengu.dbo.spring.test.WhatTheStoreStored;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 *
 * <p><b>And two ways a bean becomes a step</b>, which is the other half.
 * {@link AdmittingAPatient} fills a vacancy the tenant declared;
 * {@link MeasuringASpecimen} brings a declaration the tenant never had. The
 * second is ordered first, because what it proves about the step door is only
 * true while nothing has started working.
 */
@DboSpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({AdmittingAPatient.class, MeasuringASpecimen.class})
class ABeanOfThisApplicationPerformsTheWorkIT {

    private static final String TENANT = "hogwarts";

    /** What this application declared itself to be, in application.yaml. */
    private static final String THIS_WORKER = "sample-admissions-worker";

    private static final String STEP = "hogwarts.admission.admit";

    /** The step no tenant declared, which this application brought. */
    private static final String BROUGHT = MeasuringASpecimen.DECLARED.id().toString();

    /** A run records process and step apart, so a question about one names the step. */
    private static final String BARE = BROUGHT.substring(BROUGHT.lastIndexOf('.') + 1);

    @Autowired
    DboTestContext dbo;

    @Test
    @Order(2)
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


    @Test
    @Order(1)
    @DisplayName("a bean brings a capability the tenant never declared: the tenant's own step "
            + "door still refuses it, and the face takes a run of it the catalogue now holds")
    @Proving({DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
            DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING})
    void aBeanBringsTheStepItPerforms() throws Exception {
        assertTrue(dbo.until(TENANT, true, Duration.ofMinutes(6)),
                "the tenant never came up, so there is nothing to introduce a step to: "
                        + dbo.serving());

        // A cycle declares candidacy and a service carrying its own
        // declaration introduces it in the same breath. Nothing else is done
        // to make the tenant aware of it.
        dbo.startWorking();
        Proves.that(DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
                dbo.performing().containsKey(BROUGHT),
                "this application does not perform " + BROUGHT + ", so the bean that brought "
                        + "it reached nothing: " + dbo.performing());

        var specimen = dbo.write(TENANT, "Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"a sample taken on admission"}}""");
        assertTrue(specimen.accepted(),
                "the specimen this run is about was not accepted: " + specimen.body());

        // THE STEP DOOR IS BUILT FROM THE TENANT'S SPEC, and stays so. An
        // introduced capability is not a step the tenant offers to be
        // started: bringing one grants its bringer nothing, and the door says
        // as much by name.
        var atTheDoor = dbo.send(HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(TENANT) + "/step/" + BROUGHT))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"inputs\":{\"specimen\":\"Observation/" + specimen.idOrFail()
                                + "\"}}")), dbo.workToken(TENANT));
        Proves.that(DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING,
                atTheDoor.statusCode() == 404,
                "the tenant's own step door offered a step nobody installed, so introducing "
                        + "one is a way in rather than a capability: " + atTheDoor.statusCode()
                        + " " + atTheDoor.body());

        // The other door, and the tenant's own credential: a run naming the
        // process, the step and a reference per declared slot. The face takes
        // it because the catalogue it checks against holds the declaration
        // this application brought — which is the whole of what introduction
        // buys.
        long before = dbo.asking(TENANT).work().ofStep(BARE).by(THIS_WORKER).count();
        var authored = untilTheFaceTakesIt(specimen.idOrFail());
        Proves.that(DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION, authored.accepted(),
                "the face refused a run of the step this application introduced, so the "
                        + "declaration never reached the catalogue a run is checked against: "
                        + authored.body());

        Proves.that(DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
                untilPerformed(BARE, before, Duration.ofMinutes(3)),
                "no run of " + BROUGHT + " names " + THIS_WORKER + ", so a bean brought a "
                        + "capability, the tenant authored work of it, and the work never came "
                        + "back to whoever brought it");
    }

    /** Waits for a run of one step this application performed. */
    private boolean untilPerformed(String bareStep, long before, Duration give)
            throws InterruptedException {
        long giveUp = System.nanoTime() + give.toNanos();
        while (System.nanoTime() < giveUp) {
            if (dbo.asking(TENANT).work().ofStep(bareStep).by(THIS_WORKER).count() > before) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    /** A run of the brought step, as the face's own door takes one. */
    private static String taskOver(String specimenId) {
        return """
                {"resourceType":"Task","intent":"order","status":"requested",
                 "identifier":[{"system":"urn:dbo:run","value":"a-brought-assay"}],
                 "code":{"coding":[
                   {"system":"urn:dbo:process","code":"hogwarts.admission"},
                   {"system":"urn:dbo:step","code":"assay"}]},
                 "input":[{"type":{"coding":[
                     {"system":"urn:dbo:run:input","code":"specimen"}]},
                   "valueReference":{"reference":"Observation/%s"}}]}"""
                .formatted(specimenId);
    }


    /**
     * Authors the run, until the introduction it depends on has landed.
     *
     * <p>Polled rather than asserted once, because introduction is the
     * runner's doing and it happens beside a candidacy — on attach, and
     * otherwise at the end of a cycle that completed. A tenant of this size
     * takes a minute or two to finish coming up, and a worker attaching
     * meanwhile has its credential refused, so the first cycle to complete is
     * the first one that can carry the declaration. What is being asserted is
     * that the capability arrives, not how loaded the machine was.
     */
    private WhatTheStoreStored untilTheFaceTakesIt(String specimenId) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        WhatTheStoreStored authored = dbo.write(TENANT, "Task", taskOver(specimenId));
        while (!authored.accepted() && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            authored = dbo.write(TENANT, "Task", taskOver(specimenId));
        }
        return authored;
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
