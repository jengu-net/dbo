package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.Payloads;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.fhirpath.FHIRPathEngine;
import org.hl7.fhir.r5.model.SearchParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every search parameter a version defines can be evaluated, or is named here.
 *
 * <p>Extraction skips an expression the evaluator cannot run, because refusing
 * a write over one parameter of a hundred would refuse valid data. That is the
 * right behaviour and it is also a place things go quiet: a parameter that
 * stops evaluating stops being indexed, and the symptom is a search that finds
 * nothing rather than an error anybody sees.
 *
 * <p>So the count is asserted. A parameter that starts throwing shows up here,
 * with its expression, rather than as a support case about a missing result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EverySearchParameterIsEvaluableTest {

    private final ElementVersion version = ElementVersion.of("r6");

    private static final Map<String, String> INSTANCES = new LinkedHashMap<>(Map.of(
            "Patient", """
                    {"resourceType":"Patient",
                     "identifier":[{"system":"https://ee.ee/eid","value":"38001010001"}],
                     "name":[{"family":"Aiakas","given":["Kass"]}],
                     "telecom":[{"system":"phone","value":"+3725550000"}],
                     "gender":"female","birthDate":"1980-01-01",
                     "address":[{"city":"Tartu","country":"EE"}],
                     "generalPractitioner":[{"reference":"Practitioner/gp"}]}""",
            "Observation", """
                    {"resourceType":"Observation","status":"final",
                     "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category",
                                             "code":"laboratory"}]}],
                     "code":{"coding":[{"system":"http://loinc.org","code":"718-7"}]},
                     "subject":{"reference":"Patient/one"},
                     "encounter":{"reference":"Encounter/e1"},
                     "effectiveDateTime":"2026-01-01T10:00:00Z",
                     "valueQuantity":{"value":13.2,"unit":"g/dL",
                                      "system":"http://unitsofmeasure.org","code":"g/dL"}}""",
            "Encounter", """
                    {"resourceType":"Encounter","status":"completed",
                     "class":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode",
                                          "code":"AMB"}]}],
                     "subject":{"reference":"Patient/one"},
                     "actualPeriod":{"start":"2026-01-01T09:00:00Z","end":"2026-01-01T09:30:00Z"}}""",
            "Condition", """
                    {"resourceType":"Condition",
                     "clinicalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-clinical",
                                                  "code":"active"}]},
                     "code":{"coding":[{"system":"http://snomed.info/sct","code":"386661006"}]},
                     "subject":{"reference":"Patient/one"},
                     "onsetDateTime":"2026-01-01"}"""));

    @Test
    @DisplayName("every parameter of the types a tenant serves evaluates against an instance of it")
    void everyParameterEvaluates() {
        FHIRPathEngine fhirPath = new FHIRPathEngine(version.context());
        Payloads<?> payloads = version.face().require(Payloads.class);
        List<String> unevaluable = new ArrayList<>();
        int evaluated = 0;

        for (Map.Entry<String, String> instance : INSTANCES.entrySet()) {
            Element document = (Element) payloads.read(null,
                    instance.getValue().getBytes(StandardCharsets.UTF_8));
            for (SearchParameter parameter : version.parametersFor(instance.getKey())) {
                try {
                    fhirPath.evaluate(document, parameter.getExpression());
                    evaluated++;
                } catch (Exception e) {
                    unevaluable.add(instance.getKey() + "/" + parameter.getCode() + " — "
                            + parameter.getExpression() + " — " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        }

        // Four types' worth, after folding the several SearchParameter
        // resources that share one code: the number is a floor against the
        // parameters silently not being found, not a target.
        assertTrue(evaluated > 100, "the parameters are not being found at all: " + evaluated);
        assertEquals(List.of(), unevaluable,
                "a parameter that cannot be evaluated is silently not indexed, and the symptom "
                        + "is a search that finds nothing");
    }

    @Test
    @DisplayName("and what is extracted is what a search would look for")
    void whatIsExtractedIsWhatASearchLooksFor() {
        var envelope = version.extractor("Observation")
                .extract("Observation", INSTANCES.get("Observation").getBytes(StandardCharsets.UTF_8));

        assertTrue(envelope.paths().containsKey("code"), envelope.paths().keySet().toString());
        assertTrue(envelope.paths().containsKey("status"));
        assertTrue(envelope.paths().containsKey("date"), "a clinical search is by date first");
        assertEquals(List.of(new cloud.jengu.dbo.core.api.Envelope.ReferenceEdge(
                        "subject", "Patient", "one")),
                envelope.references().stream()
                        .filter(r -> r.refType().equals("subject")).toList(),
                "a reference must be an edge, not a string: it is what an _include follows");
    }
}
