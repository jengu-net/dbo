package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.pdi.PdiSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What identifies is declared, and some of it is made
 * coarser rather than taken away.
 *
 * <p>A birth date identifies and is also what dosing, growth charts and
 * screening intervals are computed from. Removing it by default would be a
 * patient-safety failure wearing compliance clothes, so it becomes a year —
 * which still answers those questions and identifies nobody.
 *
 * <p>These are unit-level on the declaration itself. The store-level proof
 * that a keyless read returns the coarse value rides the existing PDI
 * integration test, which already exercises isolate and reassemble.
 */
class GeneralisedNotRemovedIT {

    @Test
    @DisplayName("a birth date is declared coarse, not removed; the rest is removed")
    void birthDateIsGeneralisedAndTheRestIsRemoved() {
        PdiSpec spec = PdiSpec.fhir();

        assertEquals(PdiSpec.Disposition.GENERALISE, spec.dispositionOf("Patient", "birthDate"),
                "a field that dosing depends on must not simply vanish");
        for (String removed : new String[] {"name", "identifier", "telecom", "address"}) {
            assertEquals(PdiSpec.Disposition.REMOVE, spec.dispositionOf("Patient", removed),
                    removed + " answers no clinical question in coarse form");
        }
    }

    @Test
    @DisplayName("an element with no known coarse form is absent rather than approximated")
    void unknownElementsAreNotApproximated() {
        PdiSpec spec = PdiSpec.fhir();

        // 'name' is declared REMOVE precisely because there is no honest coarse
        // form of it — a plausible-looking stand-in would be worse than nothing
        assertEquals(PdiSpec.Disposition.REMOVE, spec.dispositionOf("Patient", "name"));
        assertEquals(PdiSpec.Disposition.REMOVE, spec.dispositionOf("Patient", "unheard-of"),
                "an element nobody declared is removed, never guessed at");
    }

    @Test
    @DisplayName("a jurisdiction can change what identifies, and how")
    void aJurisdictionCanOverrideTheDeclaration() {
        PdiSpec base = PdiSpec.fhir();
        assertFalse(base.identifyingElements("Patient").contains("maritalStatus"));

        // a zone that treats marital status as identifying, and refuses to let
        // a birth date be coarsened at all
        PdiSpec zone = base.overriddenBy(Map.of("Patient", Map.of(
                "maritalStatus", PdiSpec.Disposition.REMOVE,
                "birthDate", PdiSpec.Disposition.REMOVE)));

        assertTrue(zone.identifyingElements("Patient").contains("maritalStatus"),
                "what counts as identifying is a question about law, not about data");
        assertEquals(PdiSpec.Disposition.REMOVE, zone.dispositionOf("Patient", "birthDate"));

        assertEquals(PdiSpec.Disposition.GENERALISE, base.dispositionOf("Patient", "birthDate"),
                "and the override must not reach back into the map it was derived from");
    }

    @Test
    @DisplayName("the coarse birth date is still a valid FHIR date")
    void theCoarseFormIsValidFhir() {
        // FHIR's date type admits YYYY, YYYY-MM and YYYY-MM-DD, so the coarse
        // value is expressible in the standard's own type system rather than
        // being a shape violation every reader has to special-case
        assertTrue("1980".matches("([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)"
                        + "(-(0[1-9]|1[0-2])(-(0[1-9]|[12][0-9]|3[01]))?)?"),
                "the coarse form must satisfy the FHIR date regex");
    }
}
