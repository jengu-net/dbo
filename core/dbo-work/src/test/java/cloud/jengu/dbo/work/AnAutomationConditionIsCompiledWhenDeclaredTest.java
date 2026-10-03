package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.process.AutomationCriterion;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step's condition for automation: compiled when the step is declared,
 * refused there when it is outside what the store evaluates or reads an
 * element that identifies a person, and answered over a task's inputs.
 */
class AnAutomationConditionIsCompiledWhenDeclaredTest {

    private static final Map<String, String> SLOTS = Map.of(
            "result", "Reference(Observation)", "patient", "Reference(Patient)");

    /** The membrane's default for a patient, which is what a tenant's spec is read against. */
    private static boolean identifying(String type, String element) {
        return "Patient".equals(type) && List.of("identifier", "name", "telecom", "address",
                "photo", "contact", "birthDate").contains(element);
    }

    private static AutomationCriterion compiled(String when) {
        return AutomationCriterion.compile(when, SLOTS,
                AnAutomationConditionIsCompiledWhenDeclaredTest::identifying);
    }

    private static Object normal(String code) {
        return Map.of("resourceType", "Observation", "interpretation", List.of(
                Map.of("coding", List.of(Map.of("code", code)))));
    }

    @Test
    @DisplayName("a comparison over a slot's record admits the task its inputs satisfy and "
            + "no other")
    @Proving(DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS)
    void aComparisonDecidesFromTheInputs() {
        AutomationCriterion when = compiled("result.interpretation.coding.code = 'N'");

        assertTrue(when.admits(slot -> List.of(normal("N"))));
        assertFalse(when.admits(slot -> List.of(normal("H"))));
        assertFalse(when.admits(slot -> List.of(Map.of("resourceType", "Observation"))),
                "an element the record does not carry equals nothing");
    }

    @Test
    @DisplayName("and binds tighter than or, and exists asks only whether there is a value")
    void andBindsTighterThanOr() {
        AutomationCriterion when = compiled("result.interpretation.coding.code = 'N' or "
                + "result.status = 'final' and result.note.exists()");

        assertTrue(when.admits(slot -> List.of(normal("N"))));
        assertTrue(when.admits(slot -> List.of(Map.of("status", "final",
                "note", List.of(Map.of("text", "checked"))))));
        assertFalse(when.admits(slot -> List.of(Map.of("status", "final"))));
    }

    @Test
    @DisplayName("a condition outside what the store evaluates is refused when the step is "
            + "declared, naming what stopped it")
    @Proving(DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS)
    void whatCannotBeEvaluatedIsRefusedByName() {
        IllegalArgumentException function = assertThrows(IllegalArgumentException.class,
                () -> compiled("result.value.where(unit = 'mg').exists()"));
        assertTrue(function.getMessage().contains("where("), function.getMessage());
        IllegalArgumentException stranger = assertThrows(IllegalArgumentException.class,
                () -> compiled("specimen.status = 'available'"));
        assertTrue(stranger.getMessage().contains("specimen"), stranger.getMessage());
    }

    @Test
    @DisplayName("a condition reading an element that identifies a person is refused, naming "
            + "the element")
    @Proving(DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS)
    void anIdentifyingElementIsRefusedByName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> compiled("patient.birthDate.exists()"));
        assertTrue(refused.getMessage().contains("Patient.birthDate"), refused.getMessage());
    }
}
