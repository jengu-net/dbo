package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboTestContext;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-EDGE-ROUNDTRIP, walked in Rowling Land, the sample world.
 *
 * <p>Hogwarts records care and sends its assays out. Meristem runs the bench
 * that performs them: one service, in a JVM that is not the store's and holds
 * no database of its own, reaching the hospital over a lane with a credential
 * the hospital issued.
 *
 * <p>What Meristem does not build is a copy of the store's work model. The
 * bench declares the step it performs, takes work over the lane, reports what
 * it got done, and hands back what it could not. Everything about who may take
 * what, what a report may say, and what happened afterwards belongs to the
 * tenant.
 *
 * <p><b>The hospital is shared with every other story running</b>, and with
 * the sample worker that performs admissions there. So the bench's process,
 * its steps, its client and the task scopes all carry this story's prefix and
 * run mark, and every assertion is about the run the previous leg made.
 */
@AUserStory
class WorkLeavesTheClinicAndComesBackIT {

    private static final String HOSPITAL = "hogwarts";

    /** The clinic whose lane the worker holds over the substrate. */
    private static final String CLINIC = "st-jerome";

    private static final StoryNames NAMES = StoryNames.of(DboStories.EDGE_ROUNDTRIP);

    /**
     * The process the bench's steps belong to, as {@code <module>.<process>}: the
     * module is this story's and this run's.
     */
    private static final String PROCESS = NAMES.prefix() + "-" + NAMES.run() + ".lab";
    private static final String ASSAY = PROCESS + ".assay";
    private static final String REVIEW = PROCESS + ".review";

    /** The bench's client, and the name its executor gives itself, which must agree. */
    private static final String BENCH = NAMES.value("meristem");
    private static final String SECRET = "a-secret-for-" + BENCH;

    /**
     * What the bench performs: one named input slot, an order of milestones,
     * and the verbs a report may use. Closing is in it; a step whose closure
     * is somebody's judgement would leave it out.
     */
    private static final StepDeclaration ASSAY_STEP =
            StepDeclaration.of(ASSAY, "2.1", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen")
                    .reaching("received", "measured", "reported")
                    .containing("open", "close");

    /** Its closure is a person's act: the bench prepares and somebody signs. */
    private static final StepDeclaration REVIEW_STEP =
            StepDeclaration.of(REVIEW, "1.0", WorkModel.DOMAIN).containing("open");

    /** A step whose inputs travel sealed to whoever performs it. */
    private static final StepDeclaration SEALED_STEP =
            StepDeclaration.of(PROCESS + ".sealed", "1.0", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen");
    /** A step of two inputs, whose openings the analyser signs into one chain. */
    private static final StepDeclaration CHAINED_STEP =
            StepDeclaration.of(PROCESS + ".chained", "1.0", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen")
                    .taking("order", "https://meristem.example/shape/order");
    /** A step a gateway takes on behalf of the benches behind it. */
    private static final StepDeclaration ROUTED_STEP =
            StepDeclaration.of(PROCESS + ".routed", "1.0", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen");
    private static final String GATEWAY = NAMES.value("gateway");
    private static final String KEYED_BENCH = NAMES.value("bench-7");
    private static final String KEYLESS_BENCH = NAMES.value("bench-9");
    private final java.security.KeyPair edgeSealing =
            cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();
    private final java.security.KeyPair edgeSigning =
            cloud.jengu.dbo.core.api.seal.SigningKey.newKeyPair();
    private HttpLane gateway;
    private static final String ANALYSER = NAMES.value("analyser");
    private static final String COURIER = NAMES.value("courier");
    private final java.security.KeyPair analyser =
            cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();
    private final java.security.KeyPair analyserSigning =
            cloud.jengu.dbo.core.api.seal.SigningKey.newKeyPair();

    /**
     * A ward of the story's own, offering one step that nothing performs.
     *
     * <p>A run's context answers only while somebody holds the run, and the
     * sample worker performs every step the world declares within a poll —
     * so a context over one of those closes before a leg can read through it.
     * A step nobody performs stays held by the door that started it until
     * whoever started it says the work is done.
     */
    private static final String WARD = NAMES.tenant("ward");
    private static final String FETCH = PROCESS + ".fetch";

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    org.springframework.core.env.Environment environment;

    /** The worker application asking for an admission. */
    @Autowired
    cloud.jengu.dbo.samples.worker.AskingForAnAdmission admitting;

    /** And hearing what the work came to. */
    @Autowired
    cloud.jengu.dbo.samples.worker.HearingBack hearing;

    /** The clinic asking for somebody to be recorded, at whichever tenant. */
    @Autowired
    cloud.jengu.dbo.samples.server.AskingForARegistration registering;

    /** The lanes the clinic's worker holds, as the application configured them. */
    @Autowired
    cloud.jengu.dbo.spring.worker.DboWorkerProperties lanes;

    /** The clinic's application, reading the hospital's work as it changes. */
    @Autowired
    cloud.jengu.dbo.samples.server.WatchingTheWork watching;

    /** The clinic asking for a result to be reviewed. */
    @Autowired
    cloud.jengu.dbo.samples.server.AskingForAReview reviewing;

    /** A nurse at a ward screen, taking what waits for a person. */
    @Autowired
    cloud.jengu.dbo.samples.server.TakingATask taking;

    /** The clinic's application, asking how a run it started stands. */
    @Autowired
    cloud.jengu.dbo.spring.worker.DboInitiator initiator;

    private ATenantsDoor hospital;
    private ObjectStore engine;
    private Runs runs;
    private HttpLane bench;
    private String specimen;
    private String runKey;

    @BeforeAll
    void theBenchAndTheHospital() {
        hospital = new ATenantsDoor(dbo, HOSPITAL);
        engine = tenants.store(HOSPITAL).orElseThrow();
        runs = new Runs(engine);

        // The bench's credential is bounded to the one step it performs, and
        // its executor names itself with that client id: a credential bounded
        // to steps may work only as itself, so a bench free to spell any name
        // could read the inputs of runs it was never entitled to.
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        authority.ensureClient(BENCH, SECRET, List.of("work/" + ASSAY));
        bench = HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"), () -> benchToken(authority),
                HOSPITAL, BENCH, new Executor(BENCH, "2.1", "example.meristem", Scope.BASELINE));

        // An analyser that offered keys at enrolment, and a courier that
        // offered none: what each holds decides how its work arrives.
        authority.ensureClient(ANALYSER, ANALYSER + "-secret",
                List.of("work/" + SEALED_STEP.id(), "work/" + CHAINED_STEP.id()),
                cloud.jengu.dbo.core.api.seal.ParticipantKey.of(analyser.getPublic()),
                cloud.jengu.dbo.core.api.seal.SigningKey.of(analyserSigning.getPublic()));
        authority.ensureClient(COURIER, COURIER + "-secret", List.of("work/" + SEALED_STEP.id()));
        HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"), () -> participantToken(COURIER),
                HOSPITAL, COURIER, executor(COURIER)).introduce(SEALED_STEP);
        HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"), () -> participantToken(ANALYSER),
                HOSPITAL, ANALYSER, executor(ANALYSER)).introduce(CHAINED_STEP);

        // A gateway holding the claim for benches behind it: one offered keys,
        // one did not.
        authority.ensureClient(GATEWAY, GATEWAY + "-secret", List.of("work/" + ROUTED_STEP.id()));
        authority.ensureClient(KEYED_BENCH, KEYED_BENCH + "-secret", List.of(),
                cloud.jengu.dbo.core.api.seal.ParticipantKey.of(edgeSealing.getPublic()),
                cloud.jengu.dbo.core.api.seal.SigningKey.of(edgeSigning.getPublic()));
        authority.ensureClient(KEYLESS_BENCH, KEYLESS_BENCH + "-secret", List.of());
        gateway = HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> participantToken(GATEWAY), HOSPITAL, GATEWAY, executor(GATEWAY));
        gateway.introduce(ROUTED_STEP);
        gateway.routes(List.of(
                cloud.jengu.dbo.work.Trackable.routed(KEYED_BENCH, "analyser", GATEWAY,
                        Map.of("power", "on")),
                cloud.jengu.dbo.work.Trackable.routed(KEYLESS_BENCH, "analyser", GATEWAY,
                        Map.of("power", "on"))));

        var written = dbo.write(HOSPITAL, "Observation", """
                {"resourceType":"Observation","status":"registered",
                 "code":{"text":"%s"}}""".formatted(NAMES.value("specimen")));
        assertTrue(written.accepted(), "the specimen was not accepted: " + written.body());
        specimen = written.idOrFail();

        // Declared now and asserted on last, so it comes up while the bench
        // works rather than in front of the legs that read through a run.
        dbo.declare(WARD, """
                {"code":"%s","face":"r4","audit":{"level":"writes"},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"%s","slots":{"patient":"Reference(Patient)"}}]}"""
                .formatted(WARD, FETCH));
    }

    @AfterAll
    void theWardIsWithdrawn() {
        dbo.retract(WARD);
    }

    private static String benchToken(TenantAuthority authority) {
        if (authority.token(BENCH, SECRET, null)
                instanceof TenantAuthority.TokenResult.Issued minted) {
            return minted.accessToken();
        }
        throw new IllegalStateException("the hospital would not issue the bench a token");
    }

    // ── the bench says what it can do ──

    @Test
    @Order(1)
    @DisplayName("the bench declares the step it performs and the hospital learns it, without "
            + "anybody having installed a bundle into the hospital")
    @Proving({DboPromises.PROC_STEP_DECLARES_ITSELF, DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
            DboPromises.PROC_STEP_DECLARES_ITS_SLOTS, DboPromises.PROC_MILESTONES_ARE_DECLARED})
    void theBenchIntroducesWhatItPerforms() {
        bench.introduce(ASSAY_STEP);
        bench.introduce(REVIEW_STEP);

        // Introducing the same id with the same definition again is the
        // ordinary case: a bench restarts, and a restart is not a collision.
        bench.introduce(ASSAY_STEP);
    }

    @Test
    @Order(2)
    @DisplayName("introducing a step grants the bench nothing: it still may not author work, "
            + "because stating an obligation is the tenant's act and not a runner's")
    @Proving({DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING,
            DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE})
    void introducingAStepGrantsNothing() {
        HttpResponse<String> authored = postTask(task(NAMES.value("by-the-bench"), specimen),
                benchToken(tenants.authority(HOSPITAL).orElseThrow()));

        Proves.that(DboPromises.PROC_INTRODUCTION_GRANTS_NOTHING,
                authored.statusCode() == 401 || authored.statusCode() == 403,
                "a participation credential authored work on the surface, so the thing that "
                        + "performs work can also decide there is work: " + authored.body());
    }

    // ── the hospital states the obligation ──

    @Test
    @Order(3)
    @DisplayName("the hospital authors the assay on its own surface, and what is stored is a "
            + "run rather than a document beside one")
    @Proving({DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE, DboPromises.PROC_RUN_HAS_A_RECORD,
            DboPromises.PROC_TASK_CARRIES_THE_INPUTS, DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS})
    void theHospitalAuthorsTheAssay() {
        // The run's key is the step it is of and the scope the author gave it:
        // an order number is only unique within the work it is an order for.
        String order = NAMES.value("order-4711");
        runKey = ASSAY + "/" + order;
        HttpResponse<String> posted = postTask(task(order, specimen), dbo.token(HOSPITAL));
        assertEquals(201, posted.statusCode(), posted.body());

        Run run = runs.byKey(runKey).orElseThrow(
                () -> new AssertionError("the posted task did not become a run"));
        Proves.that(DboPromises.PROC_RUN_HAS_A_RECORD,
                ASSAY.equals(run.process() + "." + run.step()),
                "the run does not name the step the task asked for: " + run);
        Proves.that(DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS,
                Map.of("specimen", cloud.jengu.dbo.work.RunSlot.referring(
                        "Observation/" + specimen)).equals(run.inputs()),
                "the declared slot is not filled by what the task named: " + run.inputs());
    }

    @Test
    @Order(4)
    @DisplayName("a task naming a slot the step never declared is refused by name, rather "
            + "than stored with a field nobody will read")
    @Proving({DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS, DboPromises.PROC_STEP_DECLARES_ITS_SLOTS})
    void anUndeclaredSlotIsRefusedByName() {
        HttpResponse<String> undeclared = postTask(
                task(PROCESS, "assay", NAMES.value("reagent-order"), "reagent", specimen),
                dbo.token(HOSPITAL));

        assertTrue(undeclared.statusCode() >= 400 && undeclared.statusCode() < 500,
                "an undeclared slot was accepted: " + undeclared.body());
        Proves.that(DboPromises.PROC_STEP_DECLARES_ITS_SLOTS,
                undeclared.body().contains("reagent"),
                "the refusal does not name the slot that does not exist, so the author learns "
                        + "that something was wrong and not what to correct: "
                        + undeclared.body());
    }

    // ── the bench takes it ──

    @Test
    @Order(5)
    @DisplayName("the bench is offered only what its credential covers, and taking anything "
            + "else is refused rather than quietly returning nothing")
    @Proving({DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
            DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED})
    void whatItMayTakeIsTheIntersection() {
        // Polled the way a runner polls: repeatedly. A poll reads the next
        // chunk of the tenant's work feed from this participant's cursor and
        // filters by step afterwards, and the hospital's feed carries every
        // other story's work too, so this one may not be in the first chunk.
        List<Run> offered = new ArrayList<>();
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (offered.stream().noneMatch(r -> runKey.equals(r.key()))
                && System.nanoTime() < giveUp) {
            offered.addAll(bench.poll(Set.of("assay"), 50));
        }
        Proves.that(DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
                offered.stream().anyMatch(r -> runKey.equals(r.key())),
                "the assay it is entitled to was not offered: " + offered);

        // A run of the step it did NOT get a credential for.
        String signing = NAMES.value("signing-1");
        postTask(task(PROCESS, "review", signing, null, null), dbo.token(HOSPITAL));
        Run review = runs.byKey(REVIEW + "/" + signing).orElseThrow();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bench.claim(review, Duration.ofMinutes(5)));
        Proves.that(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED,
                refused.getMessage().contains("review"),
                "a lane that silently offered nothing would look exactly like a lane with no "
                        + "work; this one should name the step it refused: "
                        + refused.getMessage());
    }

    @Test
    @Order(6)
    @DisplayName("taking it makes the run say it is in progress, who holds it, under what "
            + "version of the step, and which executor took it")
    @Proving({DboPromises.PROC_A_RUN_KEEPS_STATUS_CLAIMANT_AND_ELIGIBILITY_APART,
            DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
            DboPromises.PROC_RUN_NAMES_THE_STEP_VERSION, DboPromises.PROC_EXECUTOR_DECLARES_ITSELF})
    void takingItSaysWhoHoldsIt() {
        Run waiting = runs.byKey(runKey).orElseThrow();
        Run taken = bench.claim(waiting, Duration.ofMinutes(5)).orElseThrow(
                () -> new AssertionError("the bench could not take work it was entitled to"));

        Proves.that(DboPromises.PROC_A_RUN_KEEPS_STATUS_CLAIMANT_AND_ELIGIBILITY_APART,
                taken.status() == cloud.jengu.dbo.work.Status.IN_PROGRESS
                        && taken.automation() && taken.assignment().role() == null
                        && taken.awaits(java.time.Instant.now())
                                == cloud.jengu.dbo.work.Awaits.OWNER,
                "automation is running it now, and the run says " + taken.status()
                        + " automation=" + taken.automation() + " " + taken.assignment());
        Proves.that(DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
                BENCH.equals(taken.assignment().executor().name()),
                "the run does not name what took it, so a decision cannot be reproduced: "
                        + taken.assignment());
        Proves.that(DboPromises.PROC_RUN_NAMES_THE_STEP_VERSION,
                "2.1".equals(taken.stepVersion()),
                "the run does not say under which version of the declaration it is held, and "
                        + "reproducing a decision needs the definition as well as the runner: "
                        + taken.stepVersion());
    }

    @Test
    @Order(7)
    @DisplayName("the specimen arrives with the work, and nothing else does: the bench asks "
            + "for a run, never for a reference of its own choosing")
    @Proving(DboPromises.PROC_INPUTS_ARRIVE_WITH_THE_WORK)
    void theInputsArriveWithTheWork() {
        Run held = runs.byKey(runKey).orElseThrow();
        Map<String, List<StoredObject>> inputs = bench.inputs(held);

        assertTrue(inputs.containsKey("specimen"),
                "the slot the step declared was not resolved by the party that holds the "
                        + "objects: " + inputs.keySet());
        Proves.that(DboPromises.PROC_INPUTS_ARRIVE_WITH_THE_WORK,
                specimen.equals(inputs.get("specimen").get(0).id()),
                "what arrived is not the document the task named: " + inputs);
    }

    @Test
    @Order(8)
    @DisplayName("progress names the milestone the step declared, so how far along it is "
            + "is derived rather than asserted differently by every runner")
    @Proving(DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE)
    void progressNamesADeclaredMilestone() {
        Run held = runs.byKey(runKey).orElseThrow();
        bench.milestone(held, "measured", Map.of("read", 2L), Duration.ofMinutes(5));

        Run at = runs.byKey(runKey).orElseThrow();
        assertEquals("measured", at.milestone().name(), "the run says where it got to");
        Proves.that(DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE,
                at.milestone().position() == 2 && at.milestone().total() == 3,
                "the position is not derived from the step's declared order: "
                        + at.milestone());
    }

    // ── and says honestly how it went ──

    @Test
    @Order(9)
    @DisplayName("a step whose closure is somebody's judgement refuses a bench reporting "
            + "done, by name, whatever its credential says")
    @Proving(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS)
    void aVerbTheStepDoesNotDeclareIsRefused() {
        Run signing = runs.byKey(REVIEW + "/" + NAMES.value("signing-1")).orElseThrow();

        // Refused as its own kind, not as a generic failure: a caller that
        // cannot tell "this step does not admit that verb" from "the store did
        // not answer" retries something that will never succeed.
        Runs.NotAnAction refused = assertThrows(Runs.NotAnAction.class,
                () -> new Runs(engine, cloud.jengu.dbo.core.process.Steps.of(REVIEW_STEP))
                        .closed(signing));
        Proves.that(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS,
                refused.getMessage().contains("close"),
                "the refusal does not name the verb: " + refused.getMessage());
    }

    @Test
    @Order(10)
    @DisplayName("the assay fails and is released with its reason rather than closed, "
            + "because a run that says done because nobody answered is a lie")
    @Proving({DboPromises.PROC_FAILURE_IS_RELEASED, DboPromises.PROC_DONE_MEANS_DONE})
    void aFailureIsReleasedNotClosed() {
        Run held = runs.byKey(runKey).orElseThrow();
        bench.released(held, "the control sample was out of range",
                cloud.jengu.dbo.work.Failure.UNKNOWN);

        Run handed = runs.byKey(runKey).orElseThrow();
        Proves.that(DboPromises.PROC_DONE_MEANS_DONE, handed.open(),
                "released is not handed back, so nobody can take it again");
        Proves.that(DboPromises.PROC_FAILURE_IS_RELEASED, !handed.automation()
                        && handed.status() == cloud.jengu.dbo.work.Status.READY,
                "a failure the step never said would pass went back to automation rather than "
                        + "to people: " + handed.status() + " automation=" + handed.automation());
        Proves.that(DboPromises.PROC_FAILURE_IS_RELEASED,
                "the control sample was out of range".equals(handed.assignment().note()),
                "the reason is not on the record for whoever takes it next: "
                        + handed.assignment());
        assertEquals("measured", handed.milestone().name(),
                "the milestone did not survive the hand-back, so the next taker starts from "
                        + "the beginning rather than from a fact");
    }

    @Test
    @Order(11)
    @DisplayName("the run's envelope says what state it is in and never what it was over, "
            + "because progress is read by people who may not read the subject")
    @Proving(DboPromises.PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT)
    void theEnvelopeDisclosesStateAndNotSubject() {
        var envelope = WorkModel.registrations().get(0).extractor().extract(WorkModel.TYPE,
                engine.get(WorkModel.TYPE, runs.byKey(runKey).orElseThrow().id())
                        .orElseThrow().payload());

        assertTrue(envelope.paths().containsKey("status")
                        && envelope.paths().containsKey("eligible")
                        && envelope.paths().containsKey("step"),
                "state has to be queryable, or the list an operator opens cannot exist: "
                        + envelope.paths().keySet());
        Proves.that(DboPromises.PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT,
                !envelope.paths().toString().contains(specimen),
                "the envelope names the document the run was over, so anybody entitled to "
                        + "read progress learns which specimen it was: " + envelope.paths());
    }

    @Test
    @Order(12)
    @DisplayName("what the bench did is in the hospital's trail, attributed to the bench "
            + "rather than to the store's own machinery")
    @Proving({DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES, DboPromises.WF_HOPS_AUDITED})
    void theTrailTellsWhatTheBenchDid() {
        HttpResponse<String> trail = hospital.get("/AuditEvent?agent=" + BENCH);
        assertEquals(200, trail.statusCode(), trail.body());
        Proves.that(DboPromises.WF_HOPS_AUDITED,
                !dbo.says(trail).at("entry.resource.id").isEmpty(),
                "the bench's hops are not in the trail under the bench's name: "
                        + trail.body());
    }

    // ── a lane is a lane wherever it is held ──

    @Test
    @Order(13)
    @DisplayName("a runner the hospital never installed drives a lane it holds over HTTP, does "
            + "the work, and every verb — the milestone included — lands on the hospital's "
            + "own record")
    @Proving({DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
            DboPromises.PROC_STEP_SERVICE_EMBEDDABLE})
    void theRunnerCannotTellItIsOverHttp() {
        String validate = PROCESS + ".validate";
        Run work = runs.pipeline(PROCESS, "validate", PROCESS + "/validate/over-http",
                List.of(WorkModel.DOMAIN));
        java.util.concurrent.atomic.AtomicReference<cloud.jengu.dbo.runner.Work> received =
                new java.util.concurrent.atomic.AtomicReference<>();
        String runner = NAMES.value("runner-over-http");
        cloud.jengu.dbo.runner.Lane lane = participantLane(runner,
                participant(runner, "work/" + validate));

        try (var stepRunner = new cloud.jengu.dbo.runner.StepRunner(Duration.ofMinutes(5),
                Duration.ofMillis(50))) {
            stepRunner.register(new cloud.jengu.dbo.runner.StepService() {
                @Override
                public String step() {
                    return validate;
                }

                @Override
                public java.util.Optional<StepDeclaration> declaration() {
                    // Nothing installed this step, so it reaches the catalogue
                    // only by crossing as an introduction.
                    return java.util.Optional.of(
                            StepDeclaration.of(validate, "1.0", WorkModel.DOMAIN));
                }

                @Override
                public cloud.jengu.dbo.runner.Outcome perform(cloud.jengu.dbo.runner.Work handed) {
                    received.set(handed);
                    handed.progress().milestone("validated", Map.of("read", 2L));
                    return cloud.jengu.dbo.runner.Outcome.done(Map.of("validated", 1L));
                }
            });
            stepRunner.attach(lane);
            long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (received.get() == null && System.nanoTime() < giveUp) {
                stepRunner.cycle();
            }
        }

        assertTrue(received.get() != null, "the service was never handed the work");
        Run after = runs.byId(work.id()).orElseThrow();
        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                !after.open() && Long.valueOf(1L).equals(after.tally().get("validated")),
                "the run is not closed with its tally on the hospital's record: " + after);
        // The verb that must never be inherited: a lane that degraded it to a
        // bare checkpoint would pass everything above and drop the one thing
        // the step said about itself.
        Proves.that(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE,
                "validated".equals(after.milestone().name()),
                "the milestone did not survive the wire: " + after.milestone());
    }

    @Test
    @Order(14)
    @DisplayName("what a lane reaches is its credential's: a bounded participant is offered "
            + "nothing else, refused with the lane's own reason, and may work only as itself")
    @Proving({DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
            DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED})
    void theCredentialDecidesTheReach() {
        String elsewhere = NAMES.value("bench-elsewhere");
        String token = participant(elsewhere, "work/" + PROCESS + ".something-else");
        Run work = runs.pipeline(PROCESS, "validate", PROCESS + "/validate/refused",
                List.of(WorkModel.DOMAIN));
        cloud.jengu.dbo.runner.Lane bounded = participantLane(elsewhere, token);

        assertTrue(bounded.poll(Set.of("validate"), 10).isEmpty(),
                "poll offered work outside the entitlement");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bounded.claim(work, Duration.ofMinutes(5)));
        Proves.that(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED,
                refused.getMessage().contains("not entitled"),
                "the lane's own reason did not cross intact: " + refused.getMessage());

        // The executor identity is what a claim is recorded under and what
        // `inputs` checks against, so a bounded credential free to spell any
        // name could read the inputs of runs it never claimed.
        String itself = NAMES.value("bench-itself");
        String own = participant(itself, "work/" + ASSAY);
        cloud.jengu.dbo.runner.Lane impersonating = HttpLane.to(
                URI.create(dbo.at(HOSPITAL) + "/work"), () -> own, HOSPITAL, itself,
                new Executor(NAMES.value("someone-else"), "1.0", "example.meristem",
                        Scope.BASELINE));
        IllegalStateException asSomebodyElse = assertThrows(IllegalStateException.class,
                () -> impersonating.poll(Set.of("assay"), 10));
        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                asSomebodyElse.getMessage().contains("may work as itself"),
                "a bounded credential worked as somebody else: " + asSomebodyElse.getMessage());

        // And a credential with no participation scope reaches no lane: 403,
        // not 404, because the surface is mounted and guarded.
        String readsOnly = NAMES.value("reads-only");
        cloud.jengu.dbo.runner.Lane none = participantLane(readsOnly,
                participant(readsOnly, "system/*.read"));
        IllegalStateException noScope = assertThrows(IllegalStateException.class,
                () -> none.poll(Set.of("validate"), 10));
        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                noScope.getMessage().contains("403")
                        && noScope.getMessage().contains("participation scope"),
                "the lane did not say which grant is missing: " + noScope.getMessage());
    }

    @Test
    @Order(15)
    @DisplayName("a host serving a lane for somebody else may narrow it to what that "
            + "participant was granted, and may not widen it past its own credential")
    @Proving({DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
            DboPromises.PROC_CLAIM_IS_THE_INTERSECTION})
    void aHostMayNarrowTheLaneItServesButNeverWidenIt() {
        Run work = runs.pipeline(PROCESS, "validate", PROCESS + "/validate/narrowed",
                List.of(WorkModel.DOMAIN));

        // The hospital's own credential, serving a lane on behalf of a bench
        // that was granted some other step. The reach on the lane is the
        // bench's, not the host's.
        String host = dbo.workToken(HOSPITAL);
        String narrowedBench = NAMES.value("bench-narrowed");
        cloud.jengu.dbo.runner.Lane narrowed = HttpLane.boundedTo(
                URI.create(dbo.at(HOSPITAL) + "/work"), () -> host, HOSPITAL, narrowedBench,
                new Executor(narrowedBench, "1.0", "example.meristem", Scope.BASELINE),
                Set.of(PROCESS + ".a-different-step"));
        Proves.that(DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
                narrowed.poll(Set.of("validate"), 10).isEmpty(),
                "the narrowing did not take effect at the store");
        assertThrows(IllegalStateException.class,
                () -> narrowed.claim(work, Duration.ofMinutes(5)),
                "a claim outside the narrowing was not refused");

        // The other direction: what is asked for is intersected with what the
        // credential covers, never substituted for it.
        String asking = NAMES.value("bench-asking-for-more");
        String token = participant(asking, "work/" + PROCESS + ".something-else");
        cloud.jengu.dbo.runner.Lane widened = HttpLane.boundedTo(
                URI.create(dbo.at(HOSPITAL) + "/work"), () -> token, HOSPITAL, asking,
                new Executor(asking, "1.0", "example.meristem", Scope.BASELINE),
                Set.of(PROCESS + ".validate"));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> widened.claim(work, Duration.ofMinutes(5)));
        Proves.that(DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
                refused.getMessage().contains("not entitled"),
                "asking for a step the credential does not cover granted something: "
                        + refused.getMessage());
    }

    @Test
    @Order(16)
    @DisplayName("a refusal and a store that did not answer are different exceptions, because "
            + "a bench must stop asking for one and keep asking for the other")
    @Proving(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED)
    void aRefusalIsNotAnUnansweredCall() throws Exception {
        String readsOnly = NAMES.value("reads-only-too");
        String token = participant(readsOnly, "system/*.read");
        IllegalStateException settled = assertThrows(IllegalStateException.class,
                () -> participantLane(readsOnly, token).poll(Set.of("validate"), 10));
        Proves.that(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED,
                !(settled instanceof cloud.jengu.dbo.core.api.StoreUnreachableException),
                "a decision about the caller read as transient: " + settled.getMessage());

        // Nothing is listening: the verb is retryable, and a bench that read
        // this as a refusal would stop taking work it is entitled to.
        int dead;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            dead = free.getLocalPort();
        }
        String offline = NAMES.value("bench-offline");
        cloud.jengu.dbo.runner.Lane unreachable = HttpLane.to(
                URI.create("http://127.0.0.1:" + dead + "/t/" + HOSPITAL + "/work"),
                () -> token, HOSPITAL, offline,
                new Executor(offline, "1.0", "example.meristem", Scope.BASELINE));
        Proves.that(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED,
                assertThrows(RuntimeException.class,
                        () -> unreachable.poll(Set.of("validate"), 10))
                        instanceof cloud.jengu.dbo.core.api.StoreUnreachableException,
                "a store that never spoke read as a decision about somebody");
    }

    @Test
    @Order(17)
    @DisplayName("a run of a step a participant brought reaches the lane whichever door it was "
            + "authored through, and a claim the step does not admit is refused as settled")
    @Proving(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED)
    void bothDoorsReachTheLaneAndAnInadmissibleClaimIsARefusal() {
        // Brought rather than installed, with no slots, so a run needs no
        // inputs; introduced by the participant as it starts working.
        String countersign = PROCESS + ".countersign";
        StepDeclaration brought = StepDeclaration.of(countersign, "1", WorkModel.DOMAIN);
        String probe = NAMES.value("two-door-probe");
        String token = participant(probe, cloud.jengu.dbo.auth.Scopes.WORK);
        cloud.jengu.dbo.runner.Lane lane = HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> token, HOSPITAL, probe,
                new Executor(probe, "1", HOSPITAL, Scope.organisation(HOSPITAL)));
        lane.introduce(brought);

        // One door: the face's document door. The other: minted directly.
        String throughTheFace = NAMES.value("through-the-face");
        HttpResponse<String> authored = postTask(task(PROCESS, "countersign", throughTheFace,
                null, null), dbo.token(HOSPITAL));
        assertTrue(authored.statusCode() / 100 == 2,
                "the face would not take a run of a step the participant introduced: "
                        + authored.statusCode() + " " + authored.body());
        Run minted = runs.pipeline(PROCESS, "countersign",
                PROCESS + "/countersign/" + NAMES.value("minted-directly"),
                List.of(WorkModel.DOMAIN));

        List<String> offered = new ArrayList<>();
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!(offered.contains(minted.key())
                && offered.stream().anyMatch(k -> k.endsWith(throughTheFace)))
                && System.nanoTime() < giveUp) {
            lane.poll(Set.of("countersign"), 50).forEach(r -> offered.add(r.key()));
        }
        assertTrue(offered.contains(minted.key()) && offered.stream()
                        .anyMatch(k -> k.endsWith(throughTheFace)),
                "a run of the same step was offered through one door and not the other: "
                        + offered);

        // An organisation performing the step it brought is the baseline, and
        // the step never opened itself to being varied, so this claim cannot
        // be admitted. The question is how that arrives.
        String notAdmitted = NAMES.value("not-admitted-probe");
        String other = participant(notAdmitted, cloud.jengu.dbo.auth.Scopes.WORK);
        cloud.jengu.dbo.runner.Lane refusing = HttpLane.to(
                URI.create(dbo.at(HOSPITAL) + "/work"), () -> other, HOSPITAL, notAdmitted,
                new Executor(notAdmitted, "1", HOSPITAL, Scope.organisation(HOSPITAL)));
        Run waiting = runs.pipeline(PROCESS, "countersign",
                PROCESS + "/countersign/" + NAMES.value("not-admitted"),
                List.of(WorkModel.DOMAIN));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> refusing.claim(waiting, Duration.ofMinutes(1)));
        Proves.that(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED,
                !(refused instanceof cloud.jengu.dbo.core.api.StoreUnreachableException)
                        && refused.getMessage().contains("does not admit")
                        && refused.getMessage().contains("countersign"),
                "an inadmissible claim did not arrive as a refusal naming what it refuses: "
                        + refused.getMessage());
    }

    // ── what a participant may open is what it holds the key to ──

    @Test
    @Order(18)
    @DisplayName("a participant enrolled with a key: the hospital records the public half under "
            + "its own thumbprint, only the private half opens what is wrapped to it, a new key "
            + "rotates the version, and a private half or a wrong kind is refused")
    @Proving(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT)
    void whatAParticipantHoldsDecidesWhatItMayOpen() throws Exception {
        var analyser = cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();
        var offered = cloud.jengu.dbo.core.api.seal.ParticipantKey.of(analyser.getPublic());
        String analyserId = NAMES.value("analyser-7");

        HttpResponse<String> enrolled = enrol(analyserId, offered.render());
        assertEquals(200, enrolled.statusCode(), enrolled.body());
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                enrolled.body().contains("\"kid\":\"" + offered.kid() + "\""),
                "the version the store will wrap to is not named by the key's own thumbprint: "
                        + enrolled.body());
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        var recorded = authority.participantKey(analyserId);
        assertEquals(java.util.Optional.of(offered), recorded,
                "the key offered is not the key recorded");
        String record = clientRecord(analyserId);
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                record.contains("\"crv\":\"X25519\"") && !record.contains("\"d\":"),
                "the record is not the public half alone: " + record);

        byte[] dataKey = new byte[32];
        new java.security.SecureRandom().nextBytes(dataKey);
        var wrapped = cloud.jengu.dbo.core.api.seal.KeyWrap.wrap(dataKey, recorded.get());
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                java.util.Arrays.equals(dataKey, cloud.jengu.dbo.core.api.seal.KeyWrap.unwrap(
                        cloud.jengu.dbo.core.api.seal.KeyWrap.Wrapped.parse(wrapped.render()),
                        analyser.getPrivate())),
                "the participant, holding the private half, could not open what was wrapped "
                        + "to it after it travelled as text");
        var impostor = cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();
        assertThrows(java.security.GeneralSecurityException.class,
                () -> cloud.jengu.dbo.core.api.seal.KeyWrap.unwrap(wrapped,
                        impostor.getPrivate()),
                "a carrier holding the record and the wrap but no private half opened it");

        // A new key rotates the version, and the old wrap still says which
        // it was made to.
        var second = cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();
        assertEquals(200, enrol(analyserId,
                cloud.jengu.dbo.core.api.seal.ParticipantKey.of(second.getPublic()).render())
                .statusCode());
        var current = authority.participantKey(analyserId).orElseThrow();
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                !current.kid().equals(wrapped.kid()),
                "re-enrolling with a new key did not rotate the version");
        assertThrows(java.security.GeneralSecurityException.class,
                () -> cloud.jengu.dbo.core.api.seal.KeyWrap.unwrap(wrapped, second.getPrivate()),
                "the new key opened what was wrapped to the old one");

        // Enrolled without a key: nothing to wrap to, and the store says so.
        String router = NAMES.value("router-1");
        assertEquals(200, enrol(router, null).statusCode());
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                authority.participantKey(router).isEmpty(),
                "a participant that offered no key has one recorded anyway");

        // The private half, or the wrong kind of key, is refused and recorded
        // nowhere.
        String leakingId = NAMES.value("analyser-9");
        HttpResponse<String> leaked = enrol(leakingId, "{\"kty\":\"OKP\",\"crv\":\"X25519\","
                + "\"x\":\"hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo\",\"d\":\"never\"}");
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                leaked.statusCode() == 400 && leaked.body().contains("only the public half")
                        && clientRecords(leakingId).isEmpty(),
                "an enrolment carrying the private half was not refused, or left a record: "
                        + leaked.body());
        HttpResponse<String> wrongKind = enrol(leakingId,
                "{\"kty\":\"RSA\",\"n\":\"AQAB\",\"e\":\"AQAB\"}");
        Proves.that(DboPromises.PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT,
                wrongKind.statusCode() == 400 && wrongKind.body().contains("X25519"),
                "a key of the wrong kind was not refused by name: " + wrongKind.body());
    }

    // ── and what travels is sealed to whoever may open it ──

    @Test
    @Order(19)
    @DisplayName("the wire carries the manifest readable and the payload sealed; the analyser "
            + "opens it with the key it holds, and the opening lands on the document's trail "
            + "while the hop lands on the task")
    @Proving({DboPromises.PROC_WORK_TRAVELS_SEALED,
            DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES})
    void aCarrierReadsTheManifestAndNotThePayload() throws Exception {
        String marker = NAMES.value("specimen-plaintext");
        var written = dbo.write(HOSPITAL, "Observation", """
                {"resourceType":"Observation","status":"registered",
                 "code":{"text":"%s"}}""".formatted(marker));
        assertTrue(written.accepted(), written.body());
        String sealedSpecimen = written.idOrFail();
        Run run = runs.of(SEALED_STEP, cloud.jengu.dbo.work.RunKind.PIPELINE,
                NAMES.value("sealed-out"), Map.of("specimen", "Observation/" + sealedSpecimen));

        HttpLane lane = HttpLane.holding(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> participantToken(ANALYSER), HOSPITAL, ANALYSER, executor(ANALYSER),
                analyser.getPrivate(), analyserSigning.getPrivate());
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();

        String wire = sealedVerbRaw(held);
        Proves.that(DboPromises.PROC_WORK_TRAVELS_SEALED,
                wire.contains("\"step\":\"" + SEALED_STEP.id() + "\"")
                        && wire.contains("\"run\":\"" + held.key() + "\"")
                        && wire.contains("\"specimen\":[\"Observation/" + sealedSpecimen + "\"]"),
                "the manifest is not readable, and routing on it is its job: " + wire);
        Proves.that(DboPromises.PROC_WORK_TRAVELS_SEALED,
                !wire.contains(marker) && wire.contains("ECDH-ES+A256GCM"),
                "the payload is readable on the wire, or its key is not wrapped to the "
                        + "analyser, so whatever carries this run can read it: " + wire);

        Map<String, List<StoredObject>> inputs = lane.inputs(held);
        Proves.that(DboPromises.PROC_WORK_TRAVELS_SEALED,
                new String(inputs.get("specimen").get(0).payload(),
                        java.nio.charset.StandardCharsets.UTF_8).contains(marker),
                "the analyser, holding the private half, could not read the document");

        List<String> onTheDocument = entries("Observation", sealedSpecimen);
        List<String> opened = onTheDocument.stream()
                .filter(e -> e.contains("\"code\":\"access\"")).toList();
        Proves.that(DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                opened.size() == 1 && opened.get(0).contains("\"run\":\"" + held.key() + "\"")
                        && opened.get(0).contains("\"by\":\"" + ANALYSER + "\"")
                        && onTheDocument.stream()
                                .noneMatch(e -> e.contains("\"interaction\":\"read\"")),
                "the document's trail does not hold exactly the one opening, naming the run "
                        + "and who opened it: " + onTheDocument);
        Proves.that(DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
                entries(WorkModel.TYPE, held.id()).stream()
                        .anyMatch(e -> e.contains("\"code\":\"travel\"")),
                "the hop is not on the task, where the journey belongs");
    }

    @Test
    @Order(20)
    @DisplayName("what a participant holds decides how its work arrives: a keyed one is refused "
            + "the clear verb, and a keyless one is refused the sealed verb, each by name")
    @Proving(DboPromises.PROC_WORK_TRAVELS_SEALED)
    void whatAParticipantHoldsDecides() {
        var written = dbo.write(HOSPITAL, "Observation", """
                {"resourceType":"Observation","status":"registered","code":{"text":"%s"}}"""
                .formatted(NAMES.value("plain-specimen")));
        String plain = written.idOrFail();

        Run forTheAnalyser = runs.of(SEALED_STEP, cloud.jengu.dbo.work.RunKind.PIPELINE,
                NAMES.value("keyed-asks-clear"), Map.of("specimen", "Observation/" + plain));
        HttpLane keyed = HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> participantToken(ANALYSER), HOSPITAL, ANALYSER, executor(ANALYSER));
        Run heldByAnalyser = keyed.claim(forTheAnalyser, Duration.ofMinutes(5)).orElseThrow();
        IllegalStateException clearRefused = assertThrows(IllegalStateException.class,
                () -> keyed.inputs(heldByAnalyser));
        Proves.that(DboPromises.PROC_WORK_TRAVELS_SEALED,
                clearRefused.getMessage().contains("travel sealed"),
                "a participant that offered a key received its inputs in the clear: "
                        + clearRefused.getMessage());

        Run forTheCourier = runs.of(SEALED_STEP, cloud.jengu.dbo.work.RunKind.PIPELINE,
                NAMES.value("keyless-asks-sealed"), Map.of("specimen", "Observation/" + plain));
        HttpLane keyless = HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> participantToken(COURIER), HOSPITAL, COURIER, executor(COURIER));
        Run heldByCourier = keyless.claim(forTheCourier, Duration.ofMinutes(5)).orElseThrow();
        IllegalStateException sealRefused = assertThrows(IllegalStateException.class,
                () -> keyless.sealed(heldByCourier));
        Proves.that(DboPromises.PROC_WORK_TRAVELS_SEALED,
                sealRefused.getMessage().contains("nothing to seal to"),
                "a seal to a participant that offered no key was not refused by name: "
                        + sealRefused.getMessage());
    }

    // ── and what the analyser did is a chain nobody can quietly shorten ──

    @Test
    @Order(21)
    @DisplayName("claimed, opened twice, closed: every link commits to the one before, the "
            + "first to the task, the openings are signed by the analyser, and the result "
            + "carries the head")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aCleanRunClosesOnItsChain() {
        Run run = twoInputRun("clean");
        HttpLane lane = analysersLane();
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        assertEquals(2, lane.inputs(held).size(), "both documents were not opened");
        lane.closed(held);

        assertEquals(cloud.jengu.dbo.work.Status.COMPLETED,
                runs.byKey(held.key()).orElseThrow().status(),
                "the run did not close on a chain with no hole");
        List<Map<String, String>> chain = chainOf(held);
        Proves.that(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                chain.size() == 3 && "travel".equals(chain.get(0).get("code"))
                        && cloud.jengu.dbo.work.RunChain.root(held)
                                .equals(chain.get(0).get("previous"))
                        && ANALYSER.equals(chain.get(0).get("to"))
                        && chain.get(0).get("link").equals(chain.get(1).get("previous"))
                        && chain.get(1).get("link").equals(chain.get(2).get("previous")),
                "the hop and the two openings do not each commit to the one before, the first "
                        + "to the task: " + chain);
        Proves.that(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                cloud.jengu.dbo.core.api.seal.SigningKey.of(analyserSigning.getPublic())
                        .verifies(chain.get(2).get("link").getBytes(
                                java.nio.charset.StandardCharsets.UTF_8),
                                chain.get(2).get("signature")),
                "an opening is not signed by the analyser, checkably by anybody holding the "
                        + "public half it enrolled with: " + chain);
    }

    @Test
    @Order(22)
    @DisplayName("a suppressed middle link is exposed by the next: the opening that commits to "
            + "it is refused, naming the link the store never received, and the run stays owed")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aSuppressedLinkIsExposedByTheNext() {
        Run run = twoInputRun("suppressed");
        HttpLane lane = analysersLane();
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        String head = lane.sealed(held).manifest().head();

        String suppressed = cloud.jengu.dbo.work.RunChain.accessLink(head, held.key(),
                reference(held, "specimen"), ANALYSER);
        String next = cloud.jengu.dbo.work.RunChain.accessLink(suppressed, held.key(),
                reference(held, "order"), ANALYSER);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.opened(held, reference(held, "order"),
                        new cloud.jengu.dbo.work.RunChain.Link("access", suppressed, next,
                                ANALYSER, reference(held, "order"), sign(next))));
        Proves.that(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                refused.getMessage().contains(suppressed)
                        && refused.getMessage().contains("missing before")
                        && chainOf(held).size() == 1
                        && runs.byKey(held.key()).orElseThrow().open(),
                "a link committing to one the store never received was not refused by name, "
                        + "or something landed after the hop, or the run stopped being owed: "
                        + refused.getMessage());
    }

    @Test
    @Order(23)
    @DisplayName("a result whose head does not match the trail's is refused by name, a result "
            + "with no head is refused, and the run stays owed with its openings on record")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aMismatchedHeadIsRefusedAndTheRunStaysOwed() {
        Run run = twoInputRun("mismatch");
        HttpLane lane = analysersLane();
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        lane.inputs(held);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.closed(held, "not-the-head"));
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> lane.closed(held, null));
        Proves.that(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                refused.getMessage().contains("not-the-head")
                        && refused.getMessage().contains("the trail's head is")
                        && none.getMessage().contains("carries none"),
                "a close committing to the wrong head, or to none, was not refused by name: "
                        + refused.getMessage() + " / " + none.getMessage());
        Proves.that(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                runs.byKey(held.key()).orElseThrow().open()
                        && chainOf(held).stream()
                                .filter(e -> "access".equals(e.get("code"))).count() == 2,
                "the run closed without a result, or its openings are not on record");
    }

    // ── a gateway carries work it cannot read ──

    @Test
    @Order(24)
    @DisplayName("the router claims, names its edge as the recipient, cannot open what it "
            + "carries, forwards the edge's signed opening, and closes on the chain the edge left")
    @Proving({DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
            DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK})
    void theRouterHoldsTheClaimAndTheEdgeHoldsTheKey() throws Exception {
        String marker = NAMES.value("routed-plaintext");
        Run held = gateway.claim(routedRun("routed", marker), Duration.ofMinutes(5)).orElseThrow();

        cloud.jengu.dbo.work.SealedWork work = gateway.sealed(held, List.of(KEYED_BENCH));
        Proves.that(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                work.manifest().recipients().equals(List.of(KEYED_BENCH))
                        && !work.payload().get(0).wrapped().containsKey(GATEWAY),
                "the work was not sealed past the router to the edge it named: "
                        + work.manifest().recipients() + " "
                        + work.payload().get(0).wrapped().keySet());
        assertThrows(java.security.GeneralSecurityException.class,
                () -> work.payload().get(0).open(GATEWAY, edgeSealing.getPrivate()),
                "the router is among those the payload was sealed to");

        StoredObject opened = work.payload().get(0).open(KEYED_BENCH, edgeSealing.getPrivate());
        assertTrue(new String(opened.payload(), java.nio.charset.StandardCharsets.UTF_8)
                .contains(marker), "the edge, holding its key, could not read the specimen");
        String reference = work.payload().get(0).reference();
        String previous = work.manifest().head();
        String link = cloud.jengu.dbo.work.RunChain.accessLink(previous, held.key(), reference,
                KEYED_BENCH);
        String head = gateway.opened(held, reference, new cloud.jengu.dbo.work.RunChain.Link(
                "access", previous, link, KEYED_BENCH, reference,
                cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                        link.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        edgeSigning.getPrivate())));
        gateway.closed(held, head);
        assertEquals(cloud.jengu.dbo.work.Status.COMPLETED,
                runs.byKey(held.key()).orElseThrow().status(), "the run did not close");

        List<Map<String, String>> chain = chainOf(held);
        Proves.that(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK,
                chain.stream().map(e -> e.get("code")).toList()
                        .equals(List.of("travel", "travel", "access"))
                        && GATEWAY.equals(chain.get(0).get("to"))
                        && KEYED_BENCH.equals(chain.get(1).get("to"))
                        && chain.get(1).get("link").equals(chain.get(2).get("previous"))
                        && KEYED_BENCH.equals(chain.get(2).get("by")),
                "the chain is not the store's hop to the router, the router's forward to the "
                        + "edge, and the edge's own opening: " + chain);
        Proves.that(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                cloud.jengu.dbo.core.api.seal.SigningKey.of(edgeSigning.getPublic()).verifies(
                        chain.get(2).get("link").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        chain.get(2).get("signature")),
                "the opening is not signed with the edge's key");
    }

    @Test
    @Order(25)
    @DisplayName("a router seals only past itself to what it declared behind it, and only to a "
            + "routee that offered a key; an opening it carries is its routee's or nobody's")
    @Proving(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM)
    void aRouterSealsToItsOwnEdgesOnly() {
        Run held = gateway.claim(routedRun("strangers", NAMES.value("strangers")),
                Duration.ofMinutes(5)).orElseThrow();
        String stranger = NAMES.value("somebody-else");

        IllegalStateException notBehind = assertThrows(IllegalStateException.class,
                () -> gateway.sealed(held, List.of(stranger)));
        IllegalStateException keyless = assertThrows(IllegalStateException.class,
                () -> gateway.sealed(held, List.of(KEYLESS_BENCH)));
        IllegalStateException itself = assertThrows(IllegalStateException.class,
                () -> gateway.sealed(held));
        String reference = held.inputs().get("specimen").one();
        IllegalStateException forged = assertThrows(IllegalStateException.class,
                () -> gateway.opened(held, reference, new cloud.jengu.dbo.work.RunChain.Link(
                        "access", cloud.jengu.dbo.work.RunChain.root(held), "x", stranger,
                        reference, "sig")));
        Proves.that(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                notBehind.getMessage().contains("has not declared '" + stranger + "' behind it")
                        && keyless.getMessage().contains("'" + KEYLESS_BENCH + "' offered no key")
                        && itself.getMessage().contains("'" + GATEWAY + "' offered no key")
                        && forged.getMessage().contains("cannot report an opening of its"),
                "the router sealed or reported for somebody it may not: "
                        + notBehind.getMessage() + " / " + keyless.getMessage() + " / "
                        + itself.getMessage() + " / " + forged.getMessage());
        assertTrue(chainOf(held).stream().noneMatch(e -> KEYLESS_BENCH.equals(e.get("to"))),
                "a refused seal handed something on");
    }

    @Test
    @Order(26)
    @DisplayName("a router whose edge never answers waits, the claim lapses, the run reads "
            + "released and still owed, and a late report is refused")
    @Proving({DboPromises.PROC_DONE_MEANS_DONE, DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM})
    void aWedgedEdgeLetsTheClaimLapse() {
        // Held for nothing, so the claim has lapsed by the time anybody looks:
        // the router still holds it — nobody has acted on the run — until the
        // housekeeping below hands it back.
        Run held = gateway.claim(routedRun("wedged", NAMES.value("wedged")),
                Duration.ZERO).orElseThrow();
        gateway.sealed(held, List.of(KEYED_BENCH));
        gateway.releaseLapsed();

        Run released = runs.byKey(held.key()).orElseThrow();
        Proves.that(DboPromises.PROC_DONE_MEANS_DONE,
                released.open()
                        && released.assignment() != null
                        && released.assignment().executor() == null
                        && String.valueOf(released.assignment().note()).contains("lapsed"),
                "the run does not read released and still owed, with why: "
                        + released.assignment());
        // Refused as a claim that is no longer the router's, across the wire,
        // which is what tells a runner to drop the work rather than release it.
        assertThrows(cloud.jengu.dbo.work.Runs.NotHeld.class,
                () -> gateway.closed(held, cloud.jengu.dbo.work.RunChain.root(held)),
                "a report after the claim lapsed closed the run");
        Proves.that(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                runs.byKey(held.key()).orElseThrow().open(),
                "the run stopped being owed after a late report");
    }

    // ── and one run is one trace, wherever it is performed ──

    @Test
    @Order(27)
    @DisplayName("a run's trace context crosses the lane with it, a run nobody traced arrives "
            + "with none invented for it, and no context is ever a metric dimension")
    @Proving(DboPromises.PROC_TRACE_RIDES_THE_LANE)
    void theTraceRidesTheLane() {
        // A W3C traceparent: the shape a collector expects, opaque to the store.
        String upstream = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String tracedStep = PROCESS + ".traced";
        String tracer = NAMES.value("one-run-chain");
        cloud.jengu.dbo.runner.Lane remote = participantLane(tracer,
                participant(tracer, "work/" + tracedStep));
        remote.introduce(StepDeclaration.of(tracedStep, "1.0", WorkModel.DOMAIN));

        Run traced = runs.traced(runs.pipeline(PROCESS, "traced", PROCESS + "/traced/traced",
                List.of(WorkModel.DOMAIN)), upstream);
        Run untraced = runs.pipeline(PROCESS, "traced", PROCESS + "/traced/untraced",
                List.of(WorkModel.DOMAIN));

        Map<String, Run> arrived = new java.util.HashMap<>();
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (arrived.size() < 2 && System.nanoTime() < giveUp) {
            remote.poll(Set.of("traced"), 50).forEach(r -> arrived.put(r.key(), r));
        }
        Run overTheWire = arrived.get(traced.key());
        Run plain = arrived.get(untraced.key());
        assertTrue(overTheWire != null && plain != null, "both runs did not reach the lane: "
                + arrived.keySet());
        Proves.that(DboPromises.PROC_TRACE_RIDES_THE_LANE,
                overTheWire.traceContext().equals(java.util.Optional.of(upstream)),
                "the far end was handed work with no context, so its spans have nothing to "
                        + "hang under: " + overTheWire.traceContext());
        Proves.that(DboPromises.PROC_TRACE_RIDES_THE_LANE, plain.traceContext().isEmpty(),
                "a run nobody traced came back carrying a context minted here, which would "
                        + "replace a caller's real chain with a root that looks authoritative");
        for (cloud.jengu.dbo.telemetry.Label label : cloud.jengu.dbo.telemetry.Label.values()) {
            String name = label.name().toLowerCase(java.util.Locale.ROOT);
            Proves.that(DboPromises.PROC_TRACE_RIDES_THE_LANE,
                    !name.contains("trace") && !name.contains("correlation")
                            && !name.contains("key"),
                    "an identifier became a metric dimension, giving every run its own time "
                            + "series: " + label);
        }
    }

    // ── the application that asked hears back ──

    @Test
    @Order(28)
    @DisplayName("the worker application asks for an admission, and the run answers it — and "
            + "only it — with how the work ended and what the step produced")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void theRunAnswersTheApplicationThatAskedForIt() {
        String admission = cloud.jengu.dbo.samples.worker.AskingForAnAdmission.STEP;
        var patient = dbo.write(HOSPITAL, "Patient", """
                {"resourceType":"Patient","name":[{"family":"%s"}]}""".formatted(
                NAMES.value("admitted")));
        assertTrue(patient.accepted(), "the patient was not accepted: " + patient.body());

        cloud.jengu.dbo.spring.worker.DboInitiator.Started started =
                admitting.admit(HOSPITAL, "Patient/" + patient.idOrFail());
        String run = started.runOrFail();
        long watched = watching.seen(HOSPITAL);

        // Performed by the worker's own admitting bean, which closes the run
        // with what it counted; the application that asked waits for that.
        cloud.jengu.dbo.spring.worker.DboInitiator.Answer answer =
                hearing.settled(HOSPITAL, started, Duration.ofMinutes(3));
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR, answer.answered(),
                "the run did not answer the application that asked for it: " + answer);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
                "completed".equals(answer.state()),
                "the run's answer never said the admission was done: " + answer.body());
        Map<?, ?> task = cloud.jengu.dbo.samples.worker.HearingBack.task(answer);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
                "Task".equals(task.get("resourceType")) && run.equals(task.get("id"))
                        && String.valueOf(task.get("identifier")).contains(started.key()),
                "the answer is not the run, named by its id and its key: " + answer.body());
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
                String.valueOf(task.get("input")).contains("Patient/" + patient.idOrFail()),
                "the answer does not say what the run was over: " + answer.body());
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
                cloud.jengu.dbo.samples.worker.HearingBack.counted(answer, "admitted")
                        .equals(java.util.Optional.of(1L)),
                "the answer does not carry what the step counted: " + answer.body());
        // And the clinic's application read the run move as it moved, on the
        // hospital's work stream, without asking anybody about it.
        assertTrue(untilWatchedPast(watched),
                "the clinic's application read none of the run's changes: "
                        + watching.seen(HOSPITAL));

        // Anybody else is told the run is not there: another client that may
        // act in work, the tenant's own records credential, and nobody at all.
        String elsewhere = participant(NAMES.value("another-asker"), "work/" + admission);
        HttpResponse<String> another = dbo.send(HttpRequest.newBuilder(
                URI.create(dbo.at(HOSPITAL) + "/run/" + run)).GET(), elsewhere);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR, another.statusCode() == 404,
                "a client that did not ask for the run was answered by it: "
                        + another.statusCode() + " " + another.body());
        HttpResponse<String> records = dbo.send(HttpRequest.newBuilder(
                URI.create(dbo.at(HOSPITAL) + "/run/" + run)).GET(), dbo.token(HOSPITAL));
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR, records.statusCode() == 404,
                "a credential that may not act in work was told something other than that the "
                        + "run is not there: " + records.statusCode() + " " + records.body());
        HttpResponse<String> nobody = dbo.send(HttpRequest.newBuilder(
                URI.create(dbo.at(HOSPITAL) + "/run/" + run)).GET(), null);
        assertEquals(401, nobody.statusCode(), "no credential at all: " + nobody.body());
    }

    private boolean untilWatchedPast(long before) {
        long giveUp = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (watching.seen(HOSPITAL) <= before && System.nanoTime() < giveUp) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return watching.seen(HOSPITAL) > before;
    }

    // ── a result the hospital writes ──

    /** The person both result legs are about, unique to this run of the story. */
    private final String arriving = NAMES.value("arriving");

    /** The patient the first registration produced, for the leg that tries again. */
    private String registered;

    @Test
    @Order(29)
    @DisplayName("the worker application asks for somebody to be registered, giving the person; "
            + "its step answers with the person and their stay, the hospital writes both, and "
            + "the answer names what was written")
    @Proving({DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT,
            DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR})
    void aResultIsWrittenByTheHospital() {
        cloud.jengu.dbo.spring.worker.DboInitiator.Started started =
                admitting.register(HOSPITAL, person(arriving));
        cloud.jengu.dbo.spring.worker.DboInitiator.Answer answer =
                hearing.settled(HOSPITAL, started, Duration.ofMinutes(3));
        Proves.that(DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT,
                "completed".equals(answer.state()),
                "the registration did not complete: " + answer.body());

        List<String> produced = cloud.jengu.dbo.samples.worker.HearingBack.produced(answer);
        String patient = produced.stream().filter(p -> p.startsWith("Patient/")).findFirst()
                .orElse(null);
        String stay = produced.stream().filter(p -> p.startsWith("Encounter/")).findFirst()
                .orElse(null);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
                patient != null && stay != null && produced.size() == 2,
                "the answer does not name the person and the stay it produced: " + produced);
        registered = patient.split("/")[1];

        // Held by the hospital now, read on the hospital's own records
        // credential — which the application that asked does not hold.
        HttpResponse<String> held = dbo.read(HOSPITAL, "Patient", registered);
        Proves.that(DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT,
                held.statusCode() == 200,
                "the patient the answer names is not a record: " + held.statusCode() + " "
                        + held.body());
        HttpResponse<String> encounter = dbo.read(HOSPITAL, "Encounter",
                stay.split("/")[1]);
        Proves.that(DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT,
                encounter.statusCode() == 200
                        && encounter.body().contains("Patient/" + registered),
                "the stay is not about the person written beside it — the reference between "
                        + "the two did not resolve inside one commit: " + encounter.body());
        // Found by the run that admitted it — the search the next leg relies
        // on to show that a refused result left nothing behind.
        HttpResponse<String> byRun = dbo.search(HOSPITAL, "Encounter",
                "identifier=urn:dbo:run|" + started.key(), "TREAT");
        assertEquals(List.of(stay.split("/")[1]), dbo.says(byRun).at("entry.resource.id"),
                "the stay is not found by the run that admitted it: " + byRun.body());
    }

    @Test
    @Order(30)
    @DisplayName("registering the same person again is refused by the hospital: the run ends "
            + "failed with the hospital's reason, nothing of the result is written, and nobody "
            + "takes it again")
    @Proving(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN)
    void aRefusedResultEndsTheRun() throws InterruptedException {
        assertTrue(registered != null, "the leg before registered nobody");
        cloud.jengu.dbo.spring.worker.DboInitiator.Started again =
                admitting.register(HOSPITAL, person(arriving));
        String run = again.runOrFail();
        cloud.jengu.dbo.spring.worker.DboInitiator.Answer answer =
                hearing.settled(HOSPITAL, again, Duration.ofMinutes(3));
        Proves.that(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN,
                "failed".equals(answer.state()),
                "a result the hospital refused did not end the run as failed: " + answer.body());
        Proves.that(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN,
                answer.body().contains("OperationOutcome") && answer.body().contains("urn:rl:nid"),
                "the answer does not carry the hospital's reason: " + answer.body());

        // ALL OR NONE: the stay this result carried is identified by its run,
        // and the hospital holds no stay of this run — the refusal of the
        // person took the encounter written beside it with it.
        HttpResponse<String> stays = dbo.search(HOSPITAL, "Encounter",
                "identifier=urn:dbo:run|" + again.key(), "TREAT");
        Proves.that(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN,
                stays.statusCode() == 200 && !dbo.says(stays).has("entry"),
                "part of a refused result was written: " + stays.body());

        // And it stays ended. A released run would be taken again within a
        // poll or two; an ended one is still failed, at the same version.
        String before = String.valueOf(((Map<?, ?>) ((Map<?, ?>) cloud.jengu.dbo.core.wire
                .RecordWire.read(answer.body())).get("meta")).get("versionId"));
        Thread.sleep(2_000);
        cloud.jengu.dbo.spring.worker.DboInitiator.Answer later = hearing.now(HOSPITAL, run);
        String after = String.valueOf(((Map<?, ?>) ((Map<?, ?>) cloud.jengu.dbo.core.wire
                .RecordWire.read(later.body())).get("meta")).get("versionId"));
        Proves.that(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN,
                "failed".equals(later.state()) && before.equals(after),
                "a refused run was taken again: " + before + " then " + later.body());
    }

    // ── what a run reaches, and for how long ──

    /** The run the porter started over one patient, and the base url it reads through. */
    private String porterRun;
    private String porterContext;
    private String porterPatient;

    @Test
    @Order(31)
    @DisplayName("a credential that may act in work reads nothing directly, and inside a run it "
            + "reads the patient the run was started over and nothing else, whatever its type")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
    void aRunReachesWhatItWasStartedOver() {
        assertTrue(dbo.until(WARD, true, Duration.ofMinutes(10)),
                "the ward never came up: " + dbo.serving());
        porterPatient = dbo.write(WARD, "Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Weasley\"}]}")
                .idOrFail();
        String other = dbo.write(WARD, "Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Granger\"}]}")
                .idOrFail();
        String porter = dbo.workToken(WARD);

        // The refusal comes first: a credential that could read the record
        // directly would make everything after it a formality.
        HttpResponse<String> direct = dbo.get(dbo.at(WARD) + "/fhir/Patient/" + porterPatient,
                porter);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS,
                direct.statusCode() == 401 || direct.statusCode() == 403,
                "a work credential read a record outside any run: " + direct.statusCode());

        HttpResponse<String> started = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(WARD) + "/step/" + FETCH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"inputs\":{\"patient\":\"Patient/" + porterPatient + "\"}}")),
                porter);
        assertEquals(201, started.statusCode(), started.body());
        porterRun = dbo.says(started).one("run").orElseThrow();
        String path = dbo.says(started).one("context").orElseThrow();
        // A path rather than a url: what a node is bound to is not what a
        // caller reached it by, so the caller resolves it against its own.
        assertTrue(path.startsWith("/t/" + WARD + "/run/"), "the context is not a path: " + path);
        porterContext = root() + path;

        HttpResponse<String> inside = dbo.get(porterContext + "/Patient/" + porterPatient, porter);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS,
                inside.statusCode() == 200,
                "inside the run, the patient it was started over was not read: "
                        + inside.statusCode() + " " + inside.body());

        // Out of reach answers as absent, and BYTE FOR BYTE as an id nothing
        // ever minted: a difference between the two would say which records
        // exist to whoever asks.
        String invented = "01a00000-0000-7000-8000-00000000beef";
        HttpResponse<String> withheld = dbo.get(porterContext + "/Patient/" + other, porter);
        HttpResponse<String> absent = dbo.get(porterContext + "/Patient/" + invented, porter);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS,
                withheld.statusCode() == 404 && absent.statusCode() == 404
                        && withheld.body().equals(absent.body().replace(invented, other)),
                "a patient the run was not given answered differently from one that does not "
                        + "exist: " + withheld.body() + " / " + absent.body());
        HttpResponse<String> otherType = dbo.get(porterContext + "/Observation/" + other, porter);
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS,
                otherType.statusCode() == 404,
                "a run reached a type its step never took: " + otherType.statusCode());

        // And it says so: the context's capability names the step's types.
        HttpResponse<String> metadata = dbo.get(porterContext + "/metadata", porter);
        assertEquals(200, metadata.statusCode(), metadata.body());
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS,
                dbo.says(metadata).at("rest.resource.type").equals(List.of("Patient")),
                "the context advertises more than the step declared: " + metadata.body());
    }

    @Test
    @Order(32)
    @DisplayName("the work ends, and the way in closes behind it: the context then answers as a "
            + "run that never existed")
    @Proving(DboPromises.PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN)
    void theWayInClosesWhenTheWorkEnds() {
        assertTrue(porterRun != null, "the leg before started no run");
        String porter = dbo.workToken(WARD);
        HttpResponse<String> done = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(WARD) + "/run/" + porterRun + "/done"))
                .POST(HttpRequest.BodyPublishers.noBody()), porter);
        assertEquals(200, done.statusCode(), done.body());
        assertEquals(java.util.Optional.of("completed"), dbo.says(done).one("status"),
                done.body());

        HttpResponse<String> after = dbo.get(porterContext + "/Patient/" + porterPatient, porter);
        Proves.that(DboPromises.PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN,
                after.statusCode() == 404,
                "the context still answers for a run that is over: " + after.statusCode());

        String invented = "01a00000-0000-7000-8000-0000000000ff";
        HttpResponse<String> never = dbo.get(root() + "/t/" + WARD + "/run/" + invented
                + "/fhir/Patient/" + porterPatient, porter);
        Proves.that(DboPromises.PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN,
                never.statusCode() == 404 && after.body().equals(never.body()),
                "an ended run and one that never existed answer differently, so asking says "
                        + "which runs happened: " + after.body() + " / " + never.body());
    }

    @Test
    @Order(33)
    @DisplayName("a second porter at the ward, holding a credential for work and the id of the "
            + "run the first porter holds, reads nothing through it and cannot end it — it is "
            + "told the run is not there")
    @Proving(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS)
    void anotherPorterCannotReadTheRunTheFirstHolds() {
        String porter = dbo.workToken(WARD);
        HttpResponse<String> started = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(WARD) + "/step/" + FETCH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"inputs\":{\"patient\":\"Patient/" + porterPatient + "\"}}")),
                porter);
        assertEquals(201, started.statusCode(), started.body());
        String run = dbo.says(started).one("run").orElseThrow();
        String context = root() + dbo.says(started).one("context").orElseThrow();

        // Another client of the same ward, that may act in work exactly as
        // the first may. What it lacks is the run: it did not start it and
        // no lane handed it over.
        TenantAuthority ward = tenants.authority(WARD).orElseThrow();
        String second = NAMES.value("second-porter");
        ward.ensureClient(second, second + "-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.WORK));
        String stranger = ward.token(second, second + "-secret", null)
                instanceof TenantAuthority.TokenResult.Issued minted
                ? minted.accessToken() : null;
        assertTrue(stranger != null, "the ward would not issue the second porter a token");

        HttpResponse<String> document = dbo.get(context + "/Patient/" + porterPatient, stranger);
        HttpResponse<String> metadata = dbo.get(context + "/metadata", stranger);
        HttpResponse<String> ended = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(WARD) + "/run/" + run + "/done"))
                .POST(HttpRequest.BodyPublishers.noBody()), stranger);
        String invented = "01a00000-0000-7000-8000-0000000000fe";
        HttpResponse<String> never = dbo.get(root() + "/t/" + WARD + "/run/" + invented
                + "/fhir/Patient/" + porterPatient, stranger);
        Proves.that(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS,
                document.statusCode() == 404 && document.body().equals(never.body()),
                "a client that does not hold the run read its patient, or was told something "
                        + "other than that the run is not there: " + document.statusCode()
                        + " " + document.body() + " / " + never.body());
        Proves.that(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS,
                metadata.statusCode() == 404,
                "a client that does not hold the run read what its context answers for: "
                        + metadata.statusCode() + " " + metadata.body());
        Proves.that(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS,
                ended.statusCode() == 404,
                "a client that does not hold the run ended it: " + ended.statusCode() + " "
                        + ended.body());

        // The porter holding it is untouched: the run is still its, still
        // open, and it ends it.
        HttpResponse<String> inside = dbo.get(context + "/Patient/" + porterPatient, porter);
        Proves.that(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS,
                inside.statusCode() == 200,
                "the porter holding the run no longer reads it: " + inside.statusCode() + " "
                        + inside.body());
        HttpResponse<String> done = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(WARD) + "/run/" + run + "/done"))
                .POST(HttpRequest.BodyPublishers.noBody()), porter);
        assertEquals(200, done.statusCode(), done.body());
    }

    // ── the same worker, two carriers ──

    @Test
    @Order(34)
    @DisplayName("the clinic's own worker records a patient at Hogwarts over HTTP and at St "
            + "Jerome over the deployment's substrate: the same bean, the same outcome, and "
            + "nothing on the run that says which carried it")
    @Proving(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS)
    void theSameWorkerHoldsBothCarriers() {
        // What the application was configured with, read rather than assumed:
        // a lane naming a base is carried over HTTP, and one naming none is
        // carried by the substrate the serving half runs on.
        Map<String, Boolean> carriedBySubstrate = new java.util.HashMap<>();
        lanes.getLanes().forEach(lane ->
                carriedBySubstrate.put(lane.getTenant(), lane.overTheSubstrate()));
        assertEquals(Boolean.FALSE, carriedBySubstrate.get(HOSPITAL),
                "the clinic's lane into Hogwarts is not over HTTP: " + carriedBySubstrate);
        assertEquals(Boolean.TRUE, carriedBySubstrate.get(CLINIC),
                "the clinic's lane into St Jerome is not over the substrate: "
                        + carriedBySubstrate);
        String worker = lanes.getIdentity().getName();

        var overHttp = registering.register(HOSPITAL, person(NAMES.value("carried-over-http")));
        var overTheSubstrate = registering.register(CLINIC, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:st-jerome:mrn","value":"%s"}],
                 "name":[{"family":"Lovegood","given":["Luna"]}]}"""
                .formatted(NAMES.value("carried-over-the-substrate")));
        var heardOverHttp = hearing.settled(HOSPITAL, overHttp, Duration.ofMinutes(3));
        var heardOverTheSubstrate = hearing.settled(CLINIC, overTheSubstrate,
                Duration.ofMinutes(3));

        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                "completed".equals(heardOverHttp.state())
                        && "completed".equals(heardOverTheSubstrate.state()),
                "one carrier finished the registration and the other did not: over HTTP "
                        + heardOverHttp.body() + " / over the substrate "
                        + heardOverTheSubstrate.body());
        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                cloud.jengu.dbo.samples.worker.HearingBack.counted(heardOverHttp, "recorded")
                        .equals(cloud.jengu.dbo.samples.worker.HearingBack.counted(
                                heardOverTheSubstrate, "recorded"))
                        && cloud.jengu.dbo.samples.worker.HearingBack.produced(heardOverHttp)
                                .stream().anyMatch(one -> one.startsWith("Patient/"))
                        && cloud.jengu.dbo.samples.worker.HearingBack.produced(
                                heardOverTheSubstrate).stream()
                                .anyMatch(one -> one.startsWith("Patient/")),
                "the same step did not come to the same outcome on the two carriers: "
                        + heardOverHttp.body() + " / " + heardOverTheSubstrate.body());
        // The same worker's name on both runs: over the substrate the run
        // records the worker's identity, not the enrolment it signed with, so
        // nothing on the run tells the carriers apart.
        Proves.that(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS,
                dbo.asking(HOSPITAL).work().by(worker).stream()
                        .anyMatch(run -> run.id().equals(overHttp.run()))
                        && dbo.asking(CLINIC).work().by(worker).stream()
                                .anyMatch(run -> run.id().equals(overTheSubstrate.run())),
                "the two runs are not both recorded as performed by " + worker);
    }

    // ── and what automation may not take, a person does ──

    /** Where a ward screen sends a nurse back to after they sign in. */
    private static final String SCREEN = "http://127.0.0.1/ward-screen";

    @Test
    @Order(35)
    @DisplayName("a result automation may not review waits for a person: a nurse signs in "
            + "through the hospital's own identity provider, takes it from the people's list "
            + "as the role she holds, reads the result through the task, and finishes it")
    @Proving({DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE,
            DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS,
            DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
            DboPromises.PROC_WHO_OWES_THE_NEXT_ACT_IS_DERIVED})
    void aNurseTakesWhatAutomationMayNot() throws Exception {
        String step = cloud.jengu.dbo.samples.server.AskingForAReview.STEP;
        String normal = result("N");
        String high = result("H");
        var routine = reviewing.review(HOSPITAL, "Observation/" + normal);
        var alarming = reviewing.review(HOSPITAL, "Observation/" + high);
        assertTrue(routine.accepted() && alarming.accepted(),
                routine.body() + " / " + alarming.body());

        // The routine one is automation's, and the clinic's own worker does it.
        Proves.that(DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS,
                "completed".equals(hearing.settled(HOSPITAL, routine, Duration.ofMinutes(3))
                        .state()),
                "a normal result the step lets automation review was never reviewed");
        Run waiting = runs.byId(alarming.run()).orElseThrow();
        Proves.that(DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS,
                waiting.open() && !waiting.automation() && !waiting.claimed(
                        java.time.Instant.now()),
                "an abnormal result was not left waiting for a person: " + waiting);

        // What the clinic is told meanwhile: on the list, for a person, and
        // not come to rest — waiting longer will not end it, somebody has to.
        var told = initiator.answer(HOSPITAL, alarming.run());
        Proves.that(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR,
                "ready".equals(told.state()) && !told.settled()
                        && told.body().contains("\"requestedPerformer\"")
                        && told.body().contains("\"code\":\"person\"")
                        && !told.body().contains("\"code\":\"automation\""),
                "the clinic is not told the review waits for a person alone: " + told.body());

        // Poppy signs in at the ward screen, through Hogwarts's own provider.
        String practitioner = nurse("poppy");
        String token = signedIn("poppy");
        var list = taking.waiting(HOSPITAL, token, step);
        Proves.that(DboPromises.PROC_WHO_OWES_THE_NEXT_ACT_IS_DERIVED,
                list.stream().anyMatch(task -> task.id().equals(alarming.run())
                                && task.awaits() == cloud.jengu.dbo.work.Awaits.PERSON)
                        && list.stream().noneMatch(task -> task.id().equals(routine.run())),
                "the people's list does not hold the abnormal result alone: " + list);

        HttpResponse<String> taken = taking.take(HOSPITAL, token, alarming.run());
        assertEquals(200, taken.statusCode(), taken.body());
        Run holding = runs.byId(alarming.run()).orElseThrow();
        Proves.that(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE,
                holding.heldByAPerson()
                        && String.valueOf(holding.assignment().role()).startsWith(
                                "PractitionerRole/")
                        && holding.assignment().executor() == null,
                "the nurse does not hold the task as her role: " + holding.assignment());
        HttpResponse<String> read = taking.read(HOSPITAL, token, alarming.run(),
                "Observation/" + high);
        assertEquals(200, read.statusCode(), read.body());
        Proves.that(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE,
                hospital.get("/AuditEvent?run=" + java.net.URLEncoder.encode(holding.key(),
                        java.nio.charset.StandardCharsets.UTF_8)).body()
                        .contains("Practitioner/" + practitioner),
                "the reading the nurse made through the task does not name her");
        nurse("hagrid");
        Proves.that(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE,
                taking.read(HOSPITAL, signedIn("hagrid"), alarming.run(),
                        "Observation/" + high).statusCode() == 404,
                "another nurse, who does not hold the task, read through it");

        var meanwhile = initiator.answer(HOSPITAL, alarming.run());
        Proves.that(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE,
                "in-progress".equals(meanwhile.state())
                        && meanwhile.body().contains(String.valueOf(holding.assignment().role()))
                        && !meanwhile.body().contains("\"Device\""),
                "the task does not name the nurse's role as its owner: " + meanwhile.body());

        assertEquals(200, taking.finish(HOSPITAL, token, alarming.run()).statusCode());
        var done = initiator.answer(HOSPITAL, alarming.run());
        assertTrue("completed".equals(done.state()) && done.settled(),
                "the clinic was not told the review is done: " + done.body());
    }

    // ── a driver the clinic ships as a bundle of its own ──

    /** The clinic's own framework, which the store was installed into. */
    @Autowired
    org.osgi.framework.launch.Framework clinicFramework;

    /** Every bean that performs a step, so the leg can say none of them did. */
    @Autowired
    List<cloud.jengu.dbo.runner.StepService> beansThatPerform;

    @Test
    @Order(36)
    @DisplayName("a step the hospital declares is performed by a driver bundle the clinic "
            + "installed into its own framework, with no bean performing it: the assembly "
            + "installed the store beside the bundle, and the runner took the bundle's service "
            + "up as it takes a bean's")
    @Proving({DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER,
            DboPromises.PROC_STEP_SERVICE_EMBEDDABLE})
    void aDriverBundleOfTheClinicsPerformsADeclaredStep() {
        String observe = "hogwarts.ward.observe";
        Proves.that(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER,
                beansThatPerform.stream().noneMatch(bean -> observe.equals(bean.step())),
                "a bean performs " + observe + ", so whatever performs it proves nothing about "
                        + "the clinic's bundle");
        org.osgi.framework.Bundle driver = inTheClinicsFramework(
                "cloud.jengu.dbo.samples.thermometer");
        org.osgi.framework.Bundle runner = inTheClinicsFramework("cloud.jengu.dbo.runner");
        Proves.that(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER,
                driver.getState() == org.osgi.framework.Bundle.ACTIVE
                        && runner.getState() == org.osgi.framework.Bundle.ACTIVE,
                "the clinic's framework does not hold its driver and the store's runner, both "
                        + "running: driver " + driver.getState() + ", runner "
                        + runner.getState());

        var patient = dbo.write(HOSPITAL, "Patient", """
                {"resourceType":"Patient","name":[{"family":"%s"}]}""".formatted(
                NAMES.value("observed")));
        assertTrue(patient.accepted(), "the patient was not accepted: " + patient.body());
        cloud.jengu.dbo.spring.worker.DboInitiator.Started started = initiator.start(HOSPITAL,
                observe, Map.of("patient", "Patient/" + patient.idOrFail()));
        started.runOrFail();

        cloud.jengu.dbo.spring.worker.DboInitiator.Answer answer =
                hearing.settled(HOSPITAL, started, Duration.ofMinutes(3));
        Proves.that(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE, "completed".equals(answer.state()),
                "the ward observation was never performed: " + answer.body());
        Proves.that(DboPromises.CONT_A_HOST_MAY_OWN_THE_CONTAINER,
                cloud.jengu.dbo.samples.worker.HearingBack.counted(answer, "bundle")
                        .equals(java.util.Optional.of(driver.getBundleId())),
                "the run was not performed by the clinic's driver bundle, which counts its own "
                        + "bundle id: " + answer.body());
    }

    private org.osgi.framework.Bundle inTheClinicsFramework(String symbolicName) {
        return java.util.Arrays.stream(clinicFramework.getBundleContext().getBundles())
                .filter(bundle -> symbolicName.equals(bundle.getSymbolicName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(symbolicName + " is not in the clinic's "
                        + "framework"));
    }

    /** A potassium result, interpreted as given. */
    private String result(String interpretation) {
        var written = dbo.write(HOSPITAL, "Observation", """
                {"resourceType":"Observation","status":"final","code":{"text":"potassium"},
                 "interpretation":[{"coding":[{"code":"%s"}]}],
                 "subject":{"display":"%s"}}""".formatted(interpretation,
                NAMES.value("reviewed")));
        assertTrue(written.accepted(), written.body());
        return written.idOrFail();
    }

    /**
     * A nurse at Hogwarts: a practitioner, the person who is one, a nurse's
     * role, and a password. Returns the practitioner's id.
     */
    private String nurse(String login) {
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        authority.ensureRoleGrant("nurse", List.of("user/Task.read",
                "work/" + cloud.jengu.dbo.samples.server.AskingForAReview.STEP));
        authority.ensureClient(wardScreen(), null, List.of("user/Task.read",
                        "work/" + cloud.jengu.dbo.samples.server.AskingForAReview.STEP),
                "public-pkce", List.of(SCREEN));
        var practitioner = dbo.write(HOSPITAL, "Practitioner", """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"urn:rl:nid","value":"%s"}]}"""
                .formatted(NAMES.value(login)));
        assertTrue(practitioner.accepted(), practitioner.body());
        var person = dbo.write(HOSPITAL, "Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"urn:rl:nid","value":"%s"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(NAMES.value(login), practitioner.idOrFail()));
        assertTrue(person.accepted(), person.body());
        assertEquals(201, hospital.post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:rl:role","code":"nurse"}]}]}"""
                .formatted(practitioner.idOrFail())).statusCode());
        authority.ensureLocalCredential(NAMES.value(login), "a-secret-for-" + login,
                person.idOrFail());
        return practitioner.idOrFail();
    }

    /** The ward screen, as a client of the hospital: public, proving its code. */
    private static String wardScreen() {
        return NAMES.value("ward-screen");
    }

    /**
     * Signs a nurse in at the ward screen the way a browser does: the
     * provider's own login form, a code, and the code exchanged with the
     * proof the screen kept. Returns the access token.
     */
    private String signedIn(String login) throws Exception {
        String oidc = dbo.at(HOSPITAL) + "/oidc";
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String verifier = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(random);
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        HttpResponse<String> signIn = form(oidc + "/authorize/login", "client_id="
                + wardScreen() + "&redirect_uri=" + encoded(SCREEN) + "&state=ward"
                + "&code_challenge=" + challenge + "&login=" + encoded(NAMES.value(login))
                + "&password=" + encoded("a-secret-for-" + login));
        assertEquals(302, signIn.statusCode(), signIn.body());
        String location = signIn.headers().firstValue("Location").orElseThrow();
        String code = java.util.Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("code="))
                .map(pair -> java.net.URLDecoder.decode(pair.substring(5),
                        java.nio.charset.StandardCharsets.UTF_8))
                .findFirst().orElseThrow(() -> new AssertionError("no code: " + location));
        HttpResponse<String> tokens = form(oidc + "/token",
                "grant_type=authorization_code&client_id=" + wardScreen() + "&code="
                        + encoded(code) + "&redirect_uri=" + encoded(SCREEN)
                        + "&code_verifier=" + verifier);
        assertEquals(200, tokens.statusCode(), tokens.body());
        return dbo.says(tokens).one("access_token").orElseThrow();
    }

    private HttpResponse<String> form(String url, String body) {
        return dbo.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)), null);
    }

    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The server root, which a context's path is resolved against. */
    private String root() {
        String base = dbo.at(WARD);
        return base.substring(0, base.indexOf("/t/"));
    }

    /** A person as the asking application has them: a number and a name, and no id. */
    private static String person(String nid) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"%s"}],
                 "name":[{"family":"Lovegood","given":["Luna"]}]}""".formatted(nid);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Run routedRun(String key, String marker) {
        String specimen = dbo.write(HOSPITAL, "Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"registered\","
                        + "\"code\":{\"text\":\"" + marker + "\"}}").idOrFail();
        return runs.of(ROUTED_STEP, cloud.jengu.dbo.work.RunKind.PIPELINE, NAMES.value(key),
                Map.of("specimen", "Observation/" + specimen));
    }

    private HttpLane analysersLane() {
        return HttpLane.holding(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> participantToken(ANALYSER), HOSPITAL, ANALYSER, executor(ANALYSER),
                analyser.getPrivate(), analyserSigning.getPrivate());
    }

    private Run twoInputRun(String key) {
        String first = dbo.write(HOSPITAL, "Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"registered\","
                        + "\"code\":{\"text\":\"" + NAMES.value(key + "-specimen") + "\"}}")
                .idOrFail();
        String second = dbo.write(HOSPITAL, "Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"registered\","
                        + "\"code\":{\"text\":\"" + NAMES.value(key + "-order") + "\"}}")
                .idOrFail();
        return runs.of(CHAINED_STEP, cloud.jengu.dbo.work.RunKind.PIPELINE, NAMES.value(key),
                Map.of("specimen", "Observation/" + first, "order", "Observation/" + second));
    }

    private static String reference(Run run, String slot) {
        return run.inputs().get(slot).one();
    }

    private String sign(String link) {
        return cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                link.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                analyserSigning.getPrivate());
    }

    /** The run's chain entries as recorded, in the order they were written. */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> chainOf(Run run) {
        List<Map<String, String>> out = new ArrayList<>();
        for (var entry : engine.select(cloud.jengu.dbo.core.api.Criteria.of("AuditEntry")
                .eq("run", cloud.jengu.dbo.core.api.EnvelopeValue.of(run.key())))) {
            Map<String, Object> map = (Map<String, Object>) cloud.jengu.dbo.core.wire.RecordWire
                    .read(new String(entry.payload(), java.nio.charset.StandardCharsets.UTF_8));
            if (!(map.get("detail") instanceof Map<?, ?> detail) || detail.get("link") == null) {
                continue;
            }
            Map<String, String> flat = new java.util.LinkedHashMap<>();
            flat.put("code", String.valueOf(map.get("code")));
            detail.forEach((k, v) -> flat.put(String.valueOf(k), String.valueOf(v)));
            out.add(flat);
        }
        return out;
    }

    private static Executor executor(String name) {
        return new Executor(name, "1.0", "example.meristem", Scope.BASELINE);
    }

    /** A token for a client this story registered with the participant secret. */
    private String participantToken(String clientId) {
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        if (authority.token(clientId, clientId + "-secret", null)
                instanceof TenantAuthority.TokenResult.Issued minted) {
            return minted.accessToken();
        }
        throw new IllegalStateException("the hospital would not issue " + clientId + " a token");
    }

    /** The sealed verb as a carrier sees it: the raw answer, uninterpreted. */
    private String sealedVerbRaw(Run run) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("participant", ANALYSER);
        body.put("identity", cloud.jengu.dbo.core.wire.RecordWire.encode(executor(ANALYSER)));
        body.put("run", cloud.jengu.dbo.core.wire.RecordWire.encode(run));
        HttpResponse<String> answer = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(HOSPITAL) + "/work/sealed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        cloud.jengu.dbo.core.wire.RecordWire.write(body))),
                participantToken(ANALYSER));
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    /** One document's trail, asked for as that document's trail. */
    private List<String> entries(String targetType, String targetId) {
        return engine.select(cloud.jengu.dbo.core.api.Criteria.of("AuditEntry")
                        .eq("targetType", cloud.jengu.dbo.core.api.EnvelopeValue.of(targetType))
                        .eq("targetId", cloud.jengu.dbo.core.api.EnvelopeValue.of(targetId)))
                .stream()
                .map(o -> new String(o.payload(), java.nio.charset.StandardCharsets.UTF_8))
                .toList();
    }

    /** Enrols a participant through the hospital authority's own provisioning door. */
    private HttpResponse<String> enrol(String clientId, String publicKeyJwk) {
        String body = "{\"client_id\":\"" + clientId + "\",\"secret\":\"s3cr3t\","
                + "\"scope\":[\"system/*.read\"]"
                + (publicKeyJwk != null ? ",\"public_key\":" + publicKeyJwk : "") + "}";
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(HOSPITAL) + "/oidc/admin/clients"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), dbo.token(HOSPITAL));
    }

    private String clientRecord(String clientId) {
        List<String> records = clientRecords(clientId);
        assertEquals(1, records.size(), "one client record for " + clientId + ": " + records);
        return records.get(0);
    }

    /** The hospital's client records, read from its own database as an operator would. */
    private List<String> clientRecords(String clientId) {
        return new WhatTheDatabaseHolds(environment, HOSPITAL).rows(
                        "SELECT convert_from(payload, 'UTF8') FROM "
                        + cloud.jengu.dbo.core.api.Domains.tables(
                                cloud.jengu.dbo.auth.IdentityModel.DOMAIN)
                        + "_data WHERE type = 'ClientApplication' AND NOT deleted").stream()
                .filter(row -> row.contains("\"clientId\":\"" + clientId + "\""))
                .toList();
    }

    /** A lane a participant holds as itself, at the hospital's work surface. */
    private cloud.jengu.dbo.runner.Lane participantLane(String participant, String token) {
        return HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"), () -> token, HOSPITAL,
                participant, new Executor(participant, "1.0", "example.meristem",
                        Scope.BASELINE));
    }

    /** A credential the hospital issues one participant, bounded at issue. */
    private String participant(String clientId, String... scopes) {
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        authority.ensureClient(clientId, clientId + "-secret", List.of(scopes));
        if (authority.token(clientId, clientId + "-secret", null)
                instanceof TenantAuthority.TokenResult.Issued minted) {
            return minted.accessToken();
        }
        throw new IllegalStateException("the hospital would not issue " + clientId + " a token");
    }

    private static String task(String scope, String reference) {
        return task(PROCESS, "assay", scope, "specimen", reference);
    }

    private static String task(String process, String step, String scope, String slot,
            String reference) {
        String inputs = slot == null ? ""
                : ",\"input\":[{\"type\":{\"coding\":[{\"system\":\"urn:dbo:run:input\","
                        + "\"code\":\"" + slot + "\"}]},"
                        + "\"valueReference\":{\"reference\":\"Observation/" + reference + "\"}}]";
        return "{\"resourceType\":\"Task\",\"intent\":\"order\",\"status\":\"requested\","
                + "\"identifier\":[{\"system\":\"urn:dbo:run\",\"value\":\"" + scope + "\"}],"
                + "\"code\":{\"coding\":[{\"system\":\"urn:dbo:process\",\"code\":\"" + process
                + "\"},{\"system\":\"urn:dbo:step\",\"code\":\"" + step + "\"}]}" + inputs + "}";
    }

    private HttpResponse<String> postTask(String body, String bearer) {
        return dbo.send(HttpRequest.newBuilder(URI.create(hospital.at("/Task")))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), bearer);
    }
}
