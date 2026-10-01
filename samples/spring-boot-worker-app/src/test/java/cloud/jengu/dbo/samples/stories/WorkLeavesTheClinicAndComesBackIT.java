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
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
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

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    org.springframework.core.env.Environment environment;

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
    @DisplayName("taking it makes the run say who holds it, under what version of the step, "
            + "and which executor took it")
    @Proving({DboPromises.PROC_RUN_SAYS_WHO_HOLDS_IT, DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT,
            DboPromises.PROC_RUN_NAMES_THE_STEP_VERSION, DboPromises.PROC_EXECUTOR_DECLARES_ITSELF})
    void takingItSaysWhoHoldsIt() {
        Run waiting = runs.byKey(runKey).orElseThrow();
        Run taken = bench.claim(waiting, Duration.ofMinutes(5)).orElseThrow(
                () -> new AssertionError("the bench could not take work it was entitled to"));

        Proves.that(DboPromises.PROC_RUN_SAYS_WHO_HOLDS_IT, taken.holder() == Holder.AUTOMATION,
                "automation is running it now, and the run says " + taken.holder());
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
        bench.released(held, "the control sample was out of range");

        Run handed = runs.byKey(runKey).orElseThrow();
        Proves.that(DboPromises.PROC_DONE_MEANS_DONE, handed.open(),
                "released is not handed back, so nobody can take it again");
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

        assertTrue(envelope.paths().containsKey("holder")
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

        assertEquals(cloud.jengu.dbo.work.Holder.NOBODY,
                runs.byKey(held.key()).orElseThrow().holder(),
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
                        && runs.byKey(held.key()).orElseThrow().holder()
                                != cloud.jengu.dbo.work.Holder.NOBODY,
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
                runs.byKey(held.key()).orElseThrow().holder()
                        != cloud.jengu.dbo.work.Holder.NOBODY
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
        assertEquals(cloud.jengu.dbo.work.Holder.NOBODY,
                runs.byKey(held.key()).orElseThrow().holder(), "the run did not close");

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
    void aWedgedEdgeLetsTheClaimLapse() throws InterruptedException {
        Run held = gateway.claim(routedRun("wedged", NAMES.value("wedged")),
                Duration.ofSeconds(1)).orElseThrow();
        gateway.sealed(held, List.of(KEYED_BENCH));
        Thread.sleep(1500);
        gateway.releaseLapsed();

        Run released = runs.byKey(held.key()).orElseThrow();
        Proves.that(DboPromises.PROC_DONE_MEANS_DONE,
                released.holder() != cloud.jengu.dbo.work.Holder.NOBODY
                        && released.assignment() != null
                        && released.assignment().executor() == null
                        && String.valueOf(released.assignment().note()).contains("lapsed"),
                "the run does not read released and still owed, with why: "
                        + released.assignment());
        assertThrows(RuntimeException.class,
                () -> gateway.closed(held, cloud.jengu.dbo.work.RunChain.root(held)),
                "a report after the claim lapsed closed the run");
        Proves.that(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
                runs.byKey(held.key()).orElseThrow().holder()
                        != cloud.jengu.dbo.work.Holder.NOBODY,
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
        String admin = environment.getRequiredProperty("dbo.admin.jdbc-url");
        List<String> found = new ArrayList<>();
        try (var c = java.sql.DriverManager.getConnection(
                        admin.substring(0, admin.lastIndexOf('/') + 1) + "tenant_" + HOSPITAL,
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement("SELECT convert_from(payload, 'UTF8') FROM "
                        + cloud.jengu.dbo.core.api.Domains.tables(
                                cloud.jengu.dbo.auth.IdentityModel.DOMAIN)
                        + "_data WHERE type = 'ClientApplication' AND NOT deleted");
                var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getString(1).contains("\"clientId\":\"" + clientId + "\"")) {
                    found.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException unreadable) {
            throw new IllegalStateException("the hospital's database could not be read",
                    unreadable);
        }
        return found;
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
