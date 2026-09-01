package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Would this be accepted <em>as the input to this step</em>?
 *
 * <p>A resource is assembled for a step, and the step's shape is narrower than
 * the type's — so a caller who can only ask the weaker question gets a resource
 * that passes {@code $validate} and is refused by the step that receives it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidateAgainstAStepsShapeIT {

    /** Valid as an Observation, and not a vital sign: no category, no LOINC code. */
    private static final String PLAIN_OBSERVATION = """
            {"resourceType":"Observation","status":"final",
             "code":{"coding":[{"system":"http://example.test/codes","code":"local-1"}]}}""";

    static FhirStoreFacade fhir;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ValidateAgainstAStepsShapeIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.internal("Observation")));
        // What a plain-classpath runtime has: the catalogues that declared
        // themselves. A container passes the registry's view instead.
        fhir = declared.store(new PgObjectStore(ds, declared.registrations()),
                "https://dbo.test/fhir", cloud.jengu.dbo.core.process.Steps.installed());
    }

    @Test
    @DisplayName("a resource can pass the type's shape and fail the step's")
    void theStepsShapeIsTheNarrowerQuestion() {
        String againstTheType = fhir.validationOutcome(PLAIN_OBSERVATION);
        // Valid, which is not the same as unremarkable: the local code system
        // is unresolvable here, and the outcome says so as advice.
        assertFalse(againstTheType.contains("\"severity\":\"error\""),
                "it is a valid Observation: " + againstTheType);

        String againstTheStep = fhir.validationOutcome(PLAIN_OBSERVATION, HarnessSteps.VITALS);
        assertTrue(againstTheStep.contains("\"severity\":\"error\""),
                "and it is not what the step will accept — which is the answer the caller "
                        + "actually needed: " + againstTheStep);
        assertTrue(againstTheStep.contains("vitalsigns"),
                "the refusal names the profile, or a caller cannot tell whether they used "
                        + "the wrong shape or the wrong data: " + againstTheStep);
    }

    @Test
    @DisplayName("a step nobody declared is refused by name, and one with no input shape says so")
    void unknownAndShapelessStepsAreDistinguished() {
        String unknown = fhir.validationOutcome(PLAIN_OBSERVATION, "lab.result.nothing-here");
        assertTrue(unknown.contains("no step") && unknown.contains("lab.result.nothing-here"),
                unknown);

        String shapeless = fhir.validationOutcome(PLAIN_OBSERVATION, "lab.result.dispatch");
        assertTrue(shapeless.contains("declares no input shape"),
                "a step that constrains nothing is a different answer from a step nobody "
                        + "declared: " + shapeless);
    }

    @Test
    @DisplayName("a profile this store does not carry is refused rather than fetched")
    void anUnknownProfileIsRefused() {
        String outcome = fhir.validationOutcome(PLAIN_OBSERVATION,
                "http://example.test/StructureDefinition/somebody-elses-ig");

        assertTrue(outcome.contains("\"severity\":\"error\""),
                "a caller wanting an arbitrary published IG is asking for a validation "
                        + "service, not for this store's opinion about its own content: "
                        + outcome);
        assertTrue(outcome.contains("somebody-elses-ig"), outcome);
    }
}
