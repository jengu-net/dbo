package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refusal says which element it is about, all the way to the caller.
 *
 * <p>It always knew. The database's checks carry a path, the toolchain's
 * messages carry a location, and both were folded into one sentence and the
 * sentences joined with semicolons — so a form that wanted to mark the field
 * somebody typed wrong had to take them apart again, and most did not.
 */
class AFindingKeepsItsPathTest {

    @Test
    @DisplayName("the sentence forms both answerers write are read back with their paths")
    void bothSentenceShapesRecoverTheirPath() {
        ValidationFailedException refused = new ValidationFailedException("Patient", List.of(
                "ERROR Patient.identifier[0].system: minimum required = 1",
                "Patient.birthDate: a date that is not a date"));

        assertEquals(List.of("Patient.identifier[0].system", "Patient.birthDate"),
                refused.findings().stream().map(Finding::path).toList(),
                "a refusal lost the element it was about between being found and being told");
        assertEquals("minimum required = 1", refused.findings().get(0).detail());
    }

    @Test
    @DisplayName("and prose that was never a path is not read as one")
    void proseIsNotAPath() {
        ValidationFailedException refused = new ValidationFailedException("Patient",
                List.of("the document could not be parsed: unexpected end of input"));

        assertNull(refused.findings().get(0).path(),
                "a sentence with no element in it was given one, so a form would mark a "
                        + "field that has nothing to do with what is wrong");
    }

    @Test
    @DisplayName("an outcome carries one issue per finding, and each names its element")
    void theOutcomeBindsToTheField() {
        String outcome = ElementOutcomes.outcome("invalid", List.of(
                new Finding("error", "Patient.identifier[0].system", null, "minimum required = 1"),
                new Finding("error", null, null, "the document could not be parsed")));

        assertTrue(outcome.contains("\"expression\":[\"Patient.identifier[0].system\"]"),
                "the issue does not name the element, so a form cannot mark the field: "
                        + outcome);
        assertEquals(2, outcome.split("\"severity\"", -1).length - 1,
                "the findings were folded back into one issue: " + outcome);
        assertTrue(!outcome.contains("\"expression\":[null]"),
                "a finding that knows no element was given one anyway, which points a form "
                        + "at the wrong field: " + outcome);
    }
}
