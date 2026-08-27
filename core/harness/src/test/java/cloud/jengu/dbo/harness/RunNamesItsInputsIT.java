package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.fhir.r5.R5FhirVersion;
import cloud.jengu.dbo.fhir.element.R6FhirVersion;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run names its inputs, and they travel with the work (#149).
 *
 * <p>The step declaration is the central profile of what a step consumes —
 * named slots, each an opaque shape reference — a run fills them at creation,
 * the face renders them as {@code Task.input}, and the lane resolves them for
 * whoever legitimately claimed the work. A runner never reaches into the
 * store for what the work is about, because it has no verb that could.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunNamesItsInputsIT {

    private static final StepDeclaration VALIDATE =
            StepDeclaration.of("lab.result.validate", "1.0", WorkModel.DOMAIN)
                    .taking("order", "http://hl7.org/fhir/StructureDefinition/ServiceRequest")
                    .taking("specimen", "http://hl7.org/fhir/StructureDefinition/Specimen");

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static Runs runs;
    static Declarations declarations;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("RunNamesItsInputsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        java.util.List<TypeRegistration> registrations =
                new java.util.ArrayList<>(WorkModel.registrations());
        registrations.add(new TypeRegistration(
                "Basic", WorkModel.DOMAIN, IdentityClass.INTERNAL,
                Set.of(), Handling.operational(),
                (type, payload) -> new Envelope(), List.of()));
        store = new PgObjectStore(ds, registrations);
        runs = new Runs(store);
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    private Lane lane(Executor identity) {
        return Lane.inProcess("t-inputs", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, identity.name(), identity, store);
    }

    @Test
    @DisplayName("a run fills the step's declared slots at creation, in declaration order, "
            + "and both mismatches are refused by name")
    @Proving({DboPromises.PROC_STEP_DECLARES_ITS_SLOTS,
            DboPromises.PROC_RUN_INPUTS_FILL_THE_SLOTS})
    void aRunFillsTheDeclaredSlots() {
        // handed in the WRONG order on purpose: the record keeps declaration order
        Run run = runs.of(VALIDATE, RunKind.PIPELINE, "inputs-fill",
                Map.of("specimen", "Basic/spec-9", "order", "Basic/ord-1"));

        Run read = runs.byKey(run.key()).orElseThrow();
        assertEquals(List.of("order", "specimen"), List.copyOf(read.inputs().keySet()),
                "slot order is the declaration's, not the caller's map's");
        assertEquals("Basic/ord-1", read.inputs().get("order"),
                "fixed at creation — what the work is over is part of what the work is");

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> runs.of(VALIDATE, RunKind.PIPELINE, "inputs-unknown",
                        Map.of("order", "Basic/o", "specimen", "Basic/s", "device", "Basic/d")));
        assertTrue(unknown.getMessage().contains("device")
                        && unknown.getMessage().contains("order"),
                "an undeclared slot is refused naming it and what the step takes: "
                        + unknown.getMessage());

        IllegalArgumentException unfilled = assertThrows(IllegalArgumentException.class,
                () -> runs.of(VALIDATE, RunKind.PIPELINE, "inputs-missing",
                        Map.of("order", "Basic/o")));
        assertTrue(unfilled.getMessage().contains("specimen"),
                "a declared slot left unfilled is refused by name — every declared slot "
                        + "is mandatory: " + unfilled.getMessage());
    }

    @Test
    @DisplayName("the rendered Task carries each input — slot name as the code, reference "
            + "displayed not resolved — valid in every version served")
    @Proving(DboPromises.PROC_TASK_CARRIES_THE_INPUTS)
    void theTaskCarriesTheInputs() {
        for (String code : List.of("r4", "r5", "r6")) {
            // A face renders runs over the domains it claims, so each
            // version gets a run whose step writes ITS domain.
            StepDeclaration step = StepDeclaration.of("lab.result.validate", "1.0", code)
                    .taking("order", "http://hl7.org/fhir/StructureDefinition/ServiceRequest")
                    .taking("specimen", "http://hl7.org/fhir/StructureDefinition/Specimen");
            Run run = runs.of(step, RunKind.PIPELINE, "inputs-render-" + code,
                    Map.of("order", "Basic/ord-2", "specimen", "Basic/spec-2"));
            DomainFace face = switch (code) {
                case "r4" -> R4FhirVersion.INSTANCE.face();
                case "r5" -> R5FhirVersion.INSTANCE.face();
                default -> new R6FhirVersion().face();
            };
            String document = face.require(RecordProjection.class)
                    .project(runs.asRecord(run))
                    .orElseThrow(() -> new AssertionError(code + " renders nothing"));

            assertTrue(document.contains("\"input\":[")
                            && document.contains("urn:dbo:run:input")
                            && document.contains("\"code\":\"order\"")
                            && document.contains("\"display\":\"Basic/spec-2\""),
                    code + ": Task.input is FHIR's own element for named work parameters — "
                            + document);
            assertTrue(document.indexOf("\"code\":\"order\"")
                            < document.indexOf("\"code\":\"specimen\""),
                    code + ": rendered in declaration order — " + document);

            Payloads payloads = face.require(Payloads.class);
            Object parsed = payloads.read("Bundle", document.getBytes(StandardCharsets.UTF_8));
            assertEquals(List.of(), payloads.validate("Bundle", parsed),
                    code + " refused a document its own face built: " + document);
        }
    }

    @Test
    @DisplayName("a claimed run's inputs arrive resolved; an unclaimed asker is refused; "
            + "no slots delivers exactly nothing")
    @Proving(DboPromises.PROC_INPUTS_ARRIVE_WITH_THE_WORK)
    void inputsArriveWithTheWork() {
        String order = store.put(PutRequest.create("Basic",
                "{\"kind\":\"order\"}".getBytes(StandardCharsets.UTF_8))).id();
        String specimen = store.put(PutRequest.create("Basic",
                "{\"kind\":\"specimen\",\"code\":\"spec-3\"}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(VALIDATE, RunKind.PIPELINE, "inputs-travel",
                Map.of("order", "Basic/" + order, "specimen", "Basic/" + specimen));

        Executor claimant = new Executor("resolver", "1.0", "cloud.jengu.test", Scope.BASELINE);
        Lane lane = lane(claimant);

        assertThrows(IllegalStateException.class, () -> lane.inputs(run),
                "inputs travel with a claim, never with a question — an unclaimed run "
                        + "delivers nothing and says why");

        Run claimed = runs.claim(run, claimant, Instant.now().plusSeconds(60)).orElseThrow();
        Map<String, StoredObject> arrived = lane.inputs(claimed);
        assertEquals(Set.of("order", "specimen"), arrived.keySet(),
                "the claim is the entitlement, and the slots arrive resolved");
        assertTrue(new String(arrived.get("specimen").payload(), StandardCharsets.UTF_8)
                        .contains("spec-3"),
                "resolved to the object the host holds, not echoed as a reference");

        // and a runner other than the claimant is refused the same way
        Lane other = lane(new Executor("bystander", "1.0", "cloud.jengu.test", Scope.BASELINE));
        assertThrows(IllegalStateException.class, () -> other.inputs(claimed),
                "somebody else's claim is not this identity's entitlement");

        // a run whose step declares no slots delivers exactly nothing
        Run bare = runs.pipeline("lab.result", "archive", "lab.result/archive/bare",
                List.of(WorkModel.DOMAIN));
        Run bareClaimed = runs.claim(bare, claimant, Instant.now().plusSeconds(60)).orElseThrow();
        assertEquals(Map.of(), lane.inputs(bareClaimed),
                "no slots, nothing arrives — an empty map is the honest answer, not a "
                        + "placeholder");
    }
}
