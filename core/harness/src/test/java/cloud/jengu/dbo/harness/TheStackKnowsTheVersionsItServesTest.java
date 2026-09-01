package cloud.jengu.dbo.harness;

import org.hl7.fhir.r5.model.Enumerations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared stack knows the version codes the faces it carries are served
 * under.
 *
 * <p>This looks like a test of a library, and it is not. Definitions arrive as
 * a package and a version arrives as a code inside them, and the stack either
 * knows that code or refuses the definitions outright:
 *
 * <pre>
 * Unknown FHIRVersion code '6.0.0-ballot5'
 *   … loading StructureDefinition-Account.json from hl7.fhir.r6.core#6.0.0-ballot5
 * </pre>
 *
 * <p>That is what the core we shipped before 6.10.2 did — it carried the R6
 * loader and stopped at ballot3, so it looked capable and was not. A ballot
 * therefore needs a core release that knows its code, and the two move
 * together. Held here because a downgrade would otherwise announce itself as a
 * tenant that will not come up, in a runtime, days later.
 */
class TheStackKnowsTheVersionsItServesTest {

    @Test
    @DisplayName("the versions the faces serve are codes the stack recognises")
    void theStackKnowsTheCodes() {
        for (String code : java.util.List.of("4.0.1", "5.0.0", "6.0.0-ballot5")) {
            assertEquals(code, Enumerations.FHIRVersion.fromCode(code).toCode(),
                    "the stack does not know '" + code + "', so definitions declaring it "
                            + "are refused before anything is served");
        }
    }

    @Test
    @DisplayName("and R6 is recognised as R6, not as something later than R5")
    void r6IsRecognisedAsSuch() {
        assertTrue(org.hl7.fhir.utilities.VersionUtilities.isR6Ver("6.0.0-ballot5"),
                "a version the toolchain cannot place gets the wrong loader");
    }
}
