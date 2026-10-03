package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a step may ask the tenant to write is what its declaration says, and
 * what the tenant refuses is an answer rather than a fault.
 *
 * <p>The face here records what it was asked and answers as told, because
 * what is decided at this level is everything BEFORE the face — the
 * declaration, the record's own type — and how the face's verdicts are read.
 * That the face then validates, holds identities and commits all or none is
 * its own, proven where a tenant is.
 */
class AResultWritesOnlyWhatItsStepDeclaresTest {

    private static final String PATIENT = """
            {"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"1"}]}""";
    private static final String STAY = """
            {"resourceType":"Encounter","status":"in-progress"}""";

    private final TenantSpec.Step registering = new TenantSpec.Step(
            "hogwarts.admission.register", Map.of("patient", "Patient"), Set.of("Patient"));

    @Test
    @DisplayName("a result is one transaction through the face, and answers each version it "
            + "wrote in the order it carried them")
    @Proving(DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT)
    void aResultIsOneTransaction() {
        List<String> bundles = new ArrayList<>();
        RunResults results = results(bundle -> {
            bundles.add(bundle);
            return """
                    {"resourceType":"Bundle","type":"transaction-response","entry":[
                     {"response":{"status":"201 Created","location":"Patient/p1/_history/1"}}]}""";
        });

        assertEquals(List.of("Patient/p1/1"),
                results.commit(run(), List.of(Outcome.Write.create("urn:uuid:a", PATIENT))));
        assertEquals(1, bundles.size(), "one result is one bundle");
        assertTrue(bundles.get(0).contains("\"type\":\"transaction\"")
                        && bundles.get(0).contains("\"fullUrl\":\"urn:uuid:a\"")
                        && bundles.get(0).contains(PATIENT),
                "the result did not reach the face as a transaction carrying the record: "
                        + bundles.get(0));
    }

    @Test
    @DisplayName("a type the step does not declare it writes is refused by name, and the face "
            + "is never asked")
    @Proving(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN)
    void anUndeclaredTypeIsRefused() {
        List<String> asked = new ArrayList<>();
        RunResults results = results(bundle -> {
            asked.add(bundle);
            return "{}";
        });

        Lane.Results.Refused refused = assertThrows(Lane.Results.Refused.class,
                () -> results.commit(run(), List.of(Outcome.Write.create(PATIENT),
                        Outcome.Write.create(STAY))));
        assertTrue(refused.getMessage().contains("does not write 'Encounter'"),
                refused.getMessage());
        assertTrue(asked.isEmpty(), "the face was asked to write an undeclared type");
    }

    @Test
    @DisplayName("a write naming one type over a record of another is refused, so a declared "
            + "type cannot carry an undeclared record")
    @Proving(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN)
    void aRecordIsTheTypeItsWriteNames() {
        RunResults results = results(bundle -> "{}");

        Lane.Results.Refused refused = assertThrows(Lane.Results.Refused.class,
                () -> results.commit(run(), List.of(
                        new Outcome.Write("POST", "Patient", null, null, STAY))));
        assertTrue(refused.getMessage().contains("carries a 'Encounter'"), refused.getMessage());
    }

    @Test
    @DisplayName("what the face refuses is a refusal, and a face that could not reach a verdict "
            + "is a failure to be tried again")
    @Proving(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN)
    void aVerdictIsARefusalAndNoVerdictIsAFailure() {
        RunResults invalid = results(bundle -> {
            throw new cloud.jengu.dbo.fhir.common.ValidationFailedException("Patient",
                    List.of("Patient.gender: not a code"));
        });
        assertThrows(Lane.Results.Refused.class,
                () -> invalid.commit(run(), List.of(Outcome.Write.create(PATIENT))));

        RunResults unreachable = results(bundle -> {
            throw new cloud.jengu.dbo.fhir.common.ValidationUnavailableException("Patient",
                    "the validator is still loading");
        });
        assertThrows(cloud.jengu.dbo.fhir.common.ValidationUnavailableException.class,
                () -> unreachable.commit(run(), List.of(Outcome.Write.create(PATIENT))),
                "a face with no verdict was read as a refusal, which would end work that "
                        + "another attempt would finish");
    }

    private RunResults results(Function<String, String> bundle) {
        FhirStoreFacade face = (FhirStoreFacade) Proxy.newProxyInstance(
                FhirStoreFacade.class.getClassLoader(), new Class<?>[] {FhirStoreFacade.class},
                (proxy, method, arguments) -> {
                    if ("bundle".equals(method.getName())) {
                        return bundle.apply((String) arguments[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new RunResults("hogwarts", List.of(registering), face);
    }

    private static Run run() {
        return new Run("run-1", 1, "hogwarts.admission.register/one", "hogwarts.admission",
                "register", RunKind.PIPELINE, null, null, null, Map.of(),
                null, List.of(), null, Run.Produced.NOTHING, "1", Map.of(), null, "the-asker",
                null, cloud.jengu.dbo.work.Status.IN_PROGRESS, true, null, null, 0, null);
    }
}
