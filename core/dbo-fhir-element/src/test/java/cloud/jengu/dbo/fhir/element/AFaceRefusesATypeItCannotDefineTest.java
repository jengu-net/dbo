package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type declared as one this face defines, which it does not, is refused.
 *
 * <p>Saying a face has no definition for a type is the declaration's job:
 * {@code none} means the type is held as itself — stored verbatim, indexed by
 * the identity it declares, validated against nothing. The face never infers
 * it, deliberately, because a face that noticed it had no definition for a
 * name and quietly switched would turn every typo into an opaque type.
 *
 * <p>Which left the case in between unanswered. A name this face does not
 * know, declared as though it did, came up <b>serving</b>: the capability
 * statement advertised it, a search of it answered an empty bundle, and only a
 * write refused — blaming the caller's body for being unparseable rather than
 * the declaration for naming a type that does not exist. Three answers that
 * each read reasonably on their own, which is how it survived.
 */
class AFaceRefusesATypeItCannotDefineTest {

    @Test
    @DisplayName("a type this face has never heard of is refused, by name, and the types "
            + "that are fine are not named")
    void whatTheFaceCannotDefineIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ElementFhirVersion("r4")
                        .forTypes(List.of(type("Patient"), type("Sprocket")))
                        .registrations());

        assertTrue(refused.getMessage().contains("Sprocket"),
                "the refusal does not name the type, so somebody has a spec to fix and no "
                        + "idea which line: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("Patient"),
                "the refusal names a type that is fine, which sends a reader to the wrong "
                        + "line: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("none"),
                "the refusal does not say the one thing that makes such a type legal, so a "
                        + "reader who meant it has nowhere to go: " + refused.getMessage());
    }

    @Test
    @DisplayName("and the same type is accepted once the declaration says the face has no "
            + "definition for it")
    void sayingSoIsWhatMakesItLegal() {
        // The case that must keep working. A consumer's own type, held as
        // itself, is how a participant declaration and its kin are declared,
        // so refusing this would break tenants that are right.
        assertDoesNotThrow(() -> new ElementFhirVersion("r4")
                .forTypes(List.of(type("Patient"), type("Sprocket").withoutADefinition()))
                .registrations());
    }

    @Test
    @DisplayName("a resource with no search parameters of its own is still a resource")
    void aTypeWithNoSearchParametersIsNotAnUnknownOne() {
        // Binary is defined by this face and declares no search parameters,
        // so asking a type's parameters cannot tell it from a name nobody has
        // heard of. That was the first idea for this check and it would have
        // refused a legitimate declaration.
        assertDoesNotThrow(() -> new ElementFhirVersion("r4")
                .forTypes(List.of(type("Binary")))
                .registrations());
    }

    @Test
    @DisplayName("and asking costs no toolchain context, because a bring-up may not")
    void askingIsFree() {
        ElementVersion version = new ElementVersion("r4");

        assertTrue(version.defines("Patient"));
        assertFalse(version.defines("Sprocket"));
        assertTrue(version.defines("Binary"));

        assertFalse(version.contextBuilt(),
                "answering what this face defines built the toolchain's context. A bring-up "
                        + "asks this for every declared type of every tenant, and there is a "
                        + "test beside this one saying a bring-up costs no context");
    }

    private static FhirTypeConfig type(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.INTERNAL, Set.of(),
                Handling.operational());
    }
}
