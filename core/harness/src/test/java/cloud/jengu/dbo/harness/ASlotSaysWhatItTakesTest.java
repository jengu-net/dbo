package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.SlotShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four shapes a slot can be, and the ways of writing none of them.
 *
 * <p>Here rather than beside the class because {@code dbo-core} carries no
 * test source set: it has no dependencies, and a test framework is a
 * dependency.
 */
class ASlotSaysWhatItTakesTest {

    @Test
    @DisplayName("a bare type is one object given with the run; Reference(T) is one the "
            + "tenant already holds")
    void theTwoSingleForms() {
        SlotShape given = SlotShape.of("Organization");
        assertEquals("Organization", given.type());
        assertFalse(given.referred(), "a bare type was read as a reference");
        assertFalse(given.many());

        SlotShape referred = SlotShape.of("Reference(Organization)");
        assertEquals("Organization", referred.type(),
                "the notation was left on the type, so this slot takes a type no tenant "
                        + "declares and every declaration of it would be refused");
        assertTrue(referred.referred());
        assertFalse(referred.many());
    }

    @Test
    @DisplayName("[] makes either of them several, and leaves the type alone")
    void theTwoRepeatingForms() {
        assertEquals(new SlotShape("Basic", SlotShape.Source.GIVEN, true),
                SlotShape.of("Basic[]"));
        assertEquals(new SlotShape("Organization", SlotShape.Source.STORE, true),
                SlotShape.of("Reference(Organization)[]"));
    }

    @Test
    @DisplayName("every form is written the way it is read, because a register renders it back")
    void itRoundTrips() {
        for (String declared : new String[] {"Organization", "Reference(Organization)",
                "Organization[]", "Reference(Organization)[]"}) {
            assertEquals(declared, SlotShape.of(declared).declared(),
                    "a declared slot did not survive being read and written again, so a "
                            + "register and the declaration it is generated from can say "
                            + "different things");
        }
    }

    @Test
    @DisplayName("one spelling per shape: Reference(T[]) is refused and told how to say it")
    void oneSpellingEach() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SlotShape.of("Reference(Organization[])"));
        assertTrue(refused.getMessage().contains("Reference(Organization)[]"),
                "the refusal does not say how to write it instead: " + refused.getMessage());
    }

    @Test
    @DisplayName("FHIR's multi-target Reference(A | B) is refused by name rather than read "
            + "as the first of them")
    void oneTargetType() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SlotShape.of("Reference(Patient | Group)"));
        assertTrue(refused.getMessage().contains("more than one target"),
                "a form FHIR writes was refused without saying which part is the trouble: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("a slot that names no type is refused, because it is over nothing")
    void aSlotNamesAType() {
        assertThrows(IllegalArgumentException.class, () -> SlotShape.of(""));
        assertThrows(IllegalArgumentException.class, () -> SlotShape.of(null));
        assertThrows(IllegalArgumentException.class, () -> SlotShape.of("Reference("));
        assertThrows(IllegalArgumentException.class, () -> SlotShape.of("Reference()"));
    }
}
