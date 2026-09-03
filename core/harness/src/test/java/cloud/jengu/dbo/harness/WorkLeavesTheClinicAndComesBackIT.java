package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-EDGE-ROUNDTRIP, walked in order.
 *
 * <p>The clinic records care and sends its assays out. Meristem runs the
 * bench that performs them — one service, shared across every practice, in a
 * JVM that is not the store's and holds no database of its own.
 *
 * <p>What Meristem does not build is a copy of the store's work model. The
 * bench declares the step it performs, takes work over a lane, reports what it
 * got done, and hands back what it could not. Everything about who may take
 * what, what a report is allowed to say, and what happened afterwards belongs
 * to the tenant.
 *
 * <p><b>One clinic, one bench, in dependency order.</b> Every assertion is
 * about the run the previous leg just made. The story never counts what the
 * tenant holds, which is what lets more legs be added to the end of it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkLeavesTheClinicAndComesBackIT {

    private static final String CLINIC = "kevadlabor";
    private static final String PROCESS = "dbo.lab";
    private static final String ASSAY = PROCESS + ".assay";
    private static final String REVIEW = PROCESS + ".review";

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

    static final HttpClient http = HttpClient.newHttpClient();
    static SharedTenants.Tenant clinic;
    static ObjectStore engine;
    static Runs runs;
    static HttpLane bench;
    static String authoring;
    static String specimenId;
    static String runKey;

    @BeforeAll
    void up() {
        // Shared: this story needs a tenant to author work in, not a tenant of
        // its own. Its runs are keyed by a scope it chooses, and every
        // assertion names the run the previous leg made.
        clinic = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        engine = clinic.engine();
        runs = new Runs(engine);
        authoring = clinic.token("meristem-author", "system/*.read", "system/*.write");

        // The bench's credential is bounded to the one step it performs, and
        // its executor names itself with that client id: a credential bounded
        // to steps may work only as itself, so a bench free to spell any name
        // could read the inputs of runs it was never entitled to.
        clinic.token("meristem", "work/" + ASSAY);
        bench = HttpLane.to(URI.create(clinic.base() + "/work"),
                () -> clinic.token("meristem", "work/" + ASSAY), clinic.code(), "meristem",
                new Executor("meristem", "2.1", "example.meristem", Scope.BASELINE));

        specimenId = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
    }

    // ── the bench says what it can do ──

    @Test
    @Order(1)
    @DisplayName("the bench declares the step it performs and the clinic learns it, without "
            + "anybody having installed a bundle into the clinic")
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
    void introducingAStepGrantsNothing() throws Exception {
        HttpResponse<String> authored = postTask(task("by-the-bench", specimenId),
                clinic.token("meristem", "work/" + ASSAY));

        assertTrue(authored.statusCode() == 401 || authored.statusCode() == 403,
                "a participation credential authored work on the surface, so the thing that "
                        + "performs work can also decide there is work: " + authored.body());
    }

    // ── the clinic states the obligation ──

    @Test
    @Order(3)
    @DisplayName("the clinic authors the assay on its own surface, and what is stored is a "
            + "run rather than a document beside one")
    @Proving({DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE, DboPromises.PROC_RUN_HAS_A_RECORD,
            DboPromises.PROC_TASK_CARRIES_THE_INPUTS, DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS})
    void theClinicAuthorsTheAssay() throws Exception {
        // The run's key is the step it is of and the scope the author gave it:
        // an order number is only unique within the work it is an order for.
        runKey = ASSAY + "/order-4711";
        HttpResponse<String> posted = postTask(task("order-4711", specimenId), authoring);
        assertEquals(201, posted.statusCode(), posted.body());

        Run run = runs.byKey(runKey).orElseThrow(
                () -> new AssertionError("the posted task did not become a run"));
        assertEquals(ASSAY, run.process() + "." + run.step(),
                "the run names the step the task asked for");
        assertEquals(Map.of("specimen", "Basic/" + specimenId), run.inputs(),
                "the declared slot is filled by what the task named: " + run.inputs());
    }

    @Test
    @Order(4)
    @DisplayName("a task naming a slot the step never declared is refused by name, rather "
            + "than stored with a field nobody will read")
    @Proving({DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS, DboPromises.PROC_STEP_DECLARES_ITS_SLOTS})
    void anUndeclaredSlotIsRefusedByName() throws Exception {
        HttpResponse<String> undeclared = postTask(
                task(PROCESS, "assay", "reagent-order", "reagent", specimenId), authoring);

        assertTrue(undeclared.statusCode() >= 400 && undeclared.statusCode() < 500,
                "an undeclared slot was accepted: " + undeclared.body());
        assertTrue(undeclared.body().contains("reagent"),
                "the refusal names the slot that does not exist, so the author learns what "
                        + "to correct rather than that something was wrong: "
                        + undeclared.body());
    }

    // ── the bench takes it ──

    @Test
    @Order(5)
    @DisplayName("the bench is offered only what its credential covers, and taking anything "
            + "else is refused rather than quietly returning nothing")
    @Proving({DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
            DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED})
    void whatItMayTakeIsTheIntersection() throws Exception {
        List<Run> offered = bench.poll(Set.of("assay"), 10);
        assertTrue(offered.stream().anyMatch(r -> runKey.equals(r.key())),
                "the assay it is entitled to was not offered: " + offered);

        // A run of the step it did NOT get a credential for.
        postTask(task(PROCESS, "review", "signing-1", null, null), authoring);
        Run review = runs.byKey(REVIEW + "/signing-1").orElseThrow();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bench.claim(review, Duration.ofMinutes(5)));
        assertTrue(refused.getMessage().contains("review"),
                "a lane that silently offered nothing would look exactly like a lane with no "
                        + "work; this one names the step it refused: " + refused.getMessage());
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

        assertEquals(Holder.AUTOMATION, taken.holder(), "automation is running it now");
        assertEquals("meristem", taken.assignment().executor().name(),
                "the run names what took it, so a decision can be reproduced");
        assertEquals("2.1", taken.stepVersion(),
                "and under which version of the declaration, because reproducing a decision "
                        + "needs the definition as well as the runner");
    }

    @Test
    @Order(7)
    @DisplayName("the specimen arrives with the work, and nothing else does: the bench asks "
            + "for a run, never for a reference of its own choosing")
    @Proving(DboPromises.PROC_INPUTS_ARRIVE_WITH_THE_WORK)
    void theInputsArriveWithTheWork() {
        Run held = runs.byKey(runKey).orElseThrow();
        Map<String, StoredObject> inputs = bench.inputs(held);

        assertTrue(inputs.containsKey("specimen"),
                "the slot the step declared was resolved by the party that holds the "
                        + "objects: " + inputs.keySet());
        assertEquals(specimenId, inputs.get("specimen").id(),
                "and it is the document the task named, not one the bench asked for");
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
        assertEquals(2, at.milestone().position(),
                "and the position is derived from the step's declared order rather than "
                        + "invented by the bench: " + at.milestone());
        assertEquals(3, at.milestone().total());
    }

    // ── and says honestly how it went ──

    @Test
    @Order(9)
    @DisplayName("a step whose closure is somebody's judgement refuses a bench reporting "
            + "done, by name, whatever its credential says")
    @Proving(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS)
    void aVerbTheStepDoesNotDeclareIsRefused() {
        Run signing = runs.byKey(REVIEW + "/signing-1").orElseThrow();

        // Refused as its own kind, not as a generic failure: a caller that
        // cannot tell "this step does not admit that verb" from "the store did
        // not answer" retries something that will never succeed.
        Runs.NotAnAction refused = assertThrows(Runs.NotAnAction.class,
                () -> new Runs(engine, cloud.jengu.dbo.core.process.Steps.of(REVIEW_STEP))
                        .closed(signing));
        assertTrue(refused.getMessage().contains("close"),
                "the refusal names the verb and the step: " + refused.getMessage());
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
        assertTrue(handed.open(), "released means handed back, so somebody can take it again");
        assertEquals("the control sample was out of range", handed.assignment().note(),
                "with the reason on the record, for whoever takes it next");
        assertEquals("measured", handed.milestone().name(),
                "and the milestone survives the hand-back, so the next taker resumes from a "
                        + "fact rather than from the beginning");
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
        assertFalse(envelope.paths().toString().contains(specimenId),
                "the envelope names the document the run was over, so anybody entitled to "
                        + "read progress learns which specimen it was: " + envelope.paths());
    }

    @Test
    @Order(12)
    @DisplayName("what the bench did is in the clinic's trail: carrying the work and reading "
            + "what it named are different entries")
    @Proving({DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES, DboPromises.WF_HOPS_AUDITED})
    void theTrailTellsCarryingFromReading() throws Exception {
        HttpResponse<String> trail = http.send(HttpRequest.newBuilder(
                        URI.create(clinic.fhir() + "/AuditEvent?_count=50"))
                .header("Authorization", "Bearer " + authoring).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, trail.statusCode(), trail.body());

        assertTrue(trail.body().contains("meristem"),
                "the bench's hops are attributed to the bench rather than to the store's own "
                        + "machinery: " + trail.body().substring(0,
                        Math.min(600, trail.body().length())));
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
                        + "\"valueReference\":{\"reference\":\"Basic/" + reference + "\"}}]";
        return "{\"resourceType\":\"Task\",\"intent\":\"order\",\"status\":\"requested\","
                + "\"identifier\":[{\"system\":\"urn:dbo:run\",\"value\":\"" + scope + "\"}],"
                + "\"code\":{\"coding\":[{\"system\":\"urn:dbo:process\",\"code\":\"" + process
                + "\"},{\"system\":\"urn:dbo:step\",\"code\":\"" + step + "\"}]}" + inputs + "}";
    }

    private static HttpResponse<String> postTask(String body, String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(clinic.fhir() + "/Task"))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

}
