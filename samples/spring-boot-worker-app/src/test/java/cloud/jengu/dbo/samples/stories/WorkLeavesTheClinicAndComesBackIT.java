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
 * US-DBO-EDGE-ROUNDTRIP, walked on the sample world.
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

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

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
        for (int round = 0; round < 50; round++) {
            offered.addAll(bench.poll(Set.of("assay"), 10));
            if (offered.stream().anyMatch(r -> runKey.equals(r.key()))) {
                break;
            }
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

    // ── helpers ───────────────────────────────────────────────────────────

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
