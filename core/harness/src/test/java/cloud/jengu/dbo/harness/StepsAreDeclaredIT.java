package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.StepId;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step says what it is before anything runs it (#71).
 *
 * <p>The two things that make a declaration load-bearing rather than
 * descriptive: a run records the version it ran under, and a payload can be
 * held to the shape the step declared — through the face, because the engine
 * can no more compare a shape than name one.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepsAreDeclaredIT {

    private static final String VALIDATE = "lab.result.validate";

    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("StepsAreDeclaredIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()));
    }

    private static StepDeclaration validate() {
        return StepDeclaration.of(VALIDATE, "2.1", "r4")
                .consuming("http://hl7.org/fhir/StructureDefinition/Observation");
    }

    @Test
    @DisplayName("a step id is opaque, stable, and refused by name when nobody declared it")
    void anUndeclaredStepIsRefusedByName() {
        Steps steps = Steps.of(validate());

        assertEquals("2.1", steps.require(VALIDATE).version());
        assertEquals("lab.result", StepId.of(VALIDATE).processId());

        Steps.UnknownStep refused = assertThrows(Steps.UnknownStep.class,
                () -> steps.require("lab.result.dispatch"));
        assertTrue(refused.getMessage().contains("lab.result.dispatch"),
                "a step referenced but not installed is refused by name rather than silently "
                        + "doing nothing: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(VALIDATE),
                "and the refusal says what would have worked");
    }

    @Test
    @DisplayName("two modules declaring one id is a collision, not an override")
    void idsAreGloballyStable() {
        assertThrows(IllegalArgumentException.class,
                () -> Steps.of(validate(), StepDeclaration.of(VALIDATE, "9.9", "r5")),
                "a step id is globally stable, so the second declaration is a mistake rather "
                        + "than a newer answer");
    }

    @Test
    @DisplayName("a step declares the actions it contains, so a role has something to narrow")
    void aStepDeclaresTheActionsItContains() {
        StepDeclaration held = validate().containing("open", "close", "reopen");

        assertEquals(java.util.Set.of("open", "close", "reopen"), held.actions(),
                "what a human holder may do is exactly the set an automated executor would "
                        + "otherwise perform — roles narrow actions, not steps");
        assertEquals(java.util.Set.of(), validate().actions(),
                "a step that has not said admits everything to decide later, not nothing");
    }

    @Test
    @DisplayName("a run records the step version it ran under, beside the executor's")
    void aRunNamesTheStepVersion() {
        Run run = runs.of(validate(), RunKind.PIPELINE, "report-1");

        Run read = runs.byKey(run.key()).orElseThrow();
        assertEquals("2.1", read.stepVersion(),
                "reproducing last year's decision needs the definition as well as the runner");
        assertEquals("lab.result", read.process());
        assertEquals("validate", read.step());
        assertEquals(List.of("r4"), read.domains(),
                "the domains are the step's, not the caller's");
    }

    @Test
    @DisplayName("a payload is held to the shape the step declared, through the face")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aPayloadIsHeldToTheStepsShape() {
        Payloads payloads = R4FhirVersion.INSTANCE.face().require(Payloads.class);
        String shape = validate().consumes().orElseThrow();

        Object valid = payloads.read("Observation", ("{\"resourceType\":\"Observation\","
                + "\"status\":\"final\",\"code\":{\"text\":\"glucose\"}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), payloads.validate("Observation", valid, shape),
                "a document that conforms passes the step's precondition");

        Object missingStatus = payloads.read("Observation",
                "{\"resourceType\":\"Observation\",\"code\":{\"text\":\"glucose\"}}"
                        .getBytes(StandardCharsets.UTF_8));
        assertFalse(payloads.validate("Observation", missingStatus, shape).isEmpty(),
                "and one that does not is refused before the step acts");

        List<String> unresolvable = payloads.validate("Observation", valid,
                "http://example.test/StructureDefinition/nothing-carries-this");
        assertFalse(unresolvable.isEmpty(),
                "a shape nobody can resolve is not a shape a document conformed to — treating "
                        + "it as nothing-wrong is how a precondition quietly stops being one");
    }
}
