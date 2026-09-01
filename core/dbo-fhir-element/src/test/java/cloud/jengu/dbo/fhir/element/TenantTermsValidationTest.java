package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DBO-TERM-VALIDATION-USES-TENANT-TERMINOLOGY: coded values are
 * resolved against the tenant's own terminology where the carried definitions
 * are silent — and a system the tenant does not hold is UNRESOLVABLE, which is
 * a different fact from a code being wrong.
 */
class TenantTermsValidationTest {

    /** A tenant holding a slice of LOINC: one real code. */
    private static final Terms TENANT = (system, code) ->
            "http://loinc.org".equals(system)
                    ? Optional.of(new Terms.Membership("718-7".equals(code), "Hemoglobin"))
                    : Optional.empty();

    private static List<String> issues(String json) {
        ElementPayloads payloads = ElementVersion.of("r4").payloadsFor(TENANT);
        org.hl7.fhir.r5.elementmodel.Element document = payloads.read(null, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return payloads.validate(payloads.typeOf(document), document);
    }

    private static String observation(String system, String code) {
        return "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"coding\":[{"
                + "\"system\":\"" + system + "\",\"code\":\"" + code + "\"}]}}";
    }

    @Test
    @Proving(DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY)
    void aCodeTheTenantHoldsPasses() {
        assertEquals(List.of(), issues(observation("http://loinc.org", "718-7")),
                "a code present in the tenant's terminology must not be an issue");
    }

    @Test
    @Proving({DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY,
            DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID})
    void aCodeAbsentFromAHeldSystemIsRefused() {
        List<String> issues = issues(observation("http://loinc.org", "9999-9"));
        assertTrue(issues.stream().anyMatch(i -> i.contains("9999-9")
                        && i.contains("tenant")),
                "a code absent from a held system is invalid, said with the tenant named: "
                        + issues);
    }

    @Test
    @Proving({DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY,
            DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID})
    void aSystemTheTenantDoesNotHoldIsUnresolvableNotInvalid() {
        // SNOMED: neither the carried definitions nor this tenant hold it
        assertEquals(List.of(), issues(observation("http://snomed.info/sct", "22298006")),
                "an unresolvable system must not fail a write — it is a coverage fact");
    }

    @Test
    @Proving(DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY)
    void definitionsCarriedSystemsAreUntouchedByTenantTerms() {
        // administrative-gender lives in the carried core package: the shared
        // context answers, the tenant's terms are never consulted
        String bad = "{\"resourceType\":\"Patient\",\"gender\":\"not-a-gender\"}";
        ElementPayloads payloads = ElementVersion.of("r4").payloadsFor(TENANT);
        org.hl7.fhir.r5.elementmodel.Element document = payloads.read(null, bad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> issues = payloads.validate("Patient", document);
        assertTrue(issues.stream().anyMatch(i -> i.contains("not-a-gender")),
                "carried-definition checks must survive the tenant view: " + issues);
    }
}
