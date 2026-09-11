package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.definitions.DefinitionElement;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A definition is taken apart when it arrives, and what comes out is rows a
 * database can check against without reading the definition again.
 *
 * <p>The corpus is the whole of what the carried faces publish, because the
 * interesting property is not that a hand-picked profile expands — it is that
 * nothing in two published versions is quietly dropped. What cannot be located
 * is refused by name, and this holds the refusals to the shape the design
 * expected to owe the database: a slice told apart by following a reference.
 */
class ADefinitionIsExpandedWhenItArrivesTest {

    @Test
    @DisplayName("every element of every carried structure is located, or says it cannot be")
    @Proving({DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES,
            DboPromises.VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME})
    void everyElementIsLocatedOrSaysItCannotBe() {
        for (String face : List.of("r4", "r5")) {
            int elements = 0;
            List<String> unenforceable = new ArrayList<>();
            for (FaceRootPackages.Definition definition
                    : FaceRootPackages.definitionsFor(face, Set.of("StructureDefinition"))) {
                DefinitionElements.Expansion expansion;
                try {
                    expansion = DefinitionElements.of(definition.document());
                } catch (IllegalArgumentException noSnapshot) {
                    // A differential-only definition is snapshotted by its
                    // face before it is expanded; what a version publishes
                    // carries its own, and one that does not is not this
                    // test's subject.
                    continue;
                }
                elements += expansion.elements().size();
                for (DefinitionElement row : expansion.elements()) {
                    if (row.enforceable()) {
                        assertFalse(row.steps().isEmpty() && row.parentId() != null,
                                row.id() + " is enforceable and located by nothing");
                        assertFalse(row.steps().stream().anyMatch(String::isBlank),
                                row.id() + " is located by a step that says nothing");
                    } else {
                        assertEquals(List.of(), row.steps(),
                                row.id() + " cannot be located and is located anyway");
                        unenforceable.add(definition.url() + " " + row.id()
                                + " — " + row.unenforceable());
                    }
                }
                // Nothing is dropped: what the snapshot states, the expansion
                // holds, whether or not it can be acted on.
                assertEquals(expansion.refusals().size(),
                        expansion.elements().stream().filter(row -> !row.enforceable()).count(),
                        "a refusal without a row of its own in " + definition.url());
            }
            assertTrue(elements > 10_000, face + " expanded only " + elements + " elements");

            // The residue, stated rather than rounded off. Four of each
            // version are the shape the design expected to owe the database:
            // a slice told apart by following a reference is a join. The rest
            // are R5 bundle profiles that name a slice and never say how to
            // recognise one — a fact about those profiles, which is why they
            // are carried as unenforceable rather than fixed here.
            assertEquals(4, unenforceable.stream()
                            .filter(why -> why.contains("follows a reference")).count(),
                    face + " follows a different number of references: " + unenforceable);
            assertTrue(unenforceable.size() <= 8,
                    face + " cannot act on more than it did: " + unenforceable);
        }
    }

    @Test
    @DisplayName("a choice is located under every key it can appear under")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void aChoiceIsLocatedUnderEveryKeyItCanTake() {
        DefinitionElement value = elementsOf("r4", "Observation").get("Observation.value[x]");
        assertTrue(value.steps().contains("$.\"valueQuantity\"[*]"),
                "a quantity observation is not located: " + value.steps());
        assertTrue(value.steps().contains("$.\"valueBoolean\"[*]"),
                "a boolean observation is not located: " + value.steps());
        assertTrue(value.steps().size() > 5,
                "a choice of eleven types is located under " + value.steps().size() + " keys");
    }

    @Test
    @DisplayName("a slice is located by what the definition says tells it apart")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void aSliceIsLocatedByWhatTellsItApart() {
        Map<String, DefinitionElement> bp = elementsOf("r4", "bp");

        DefinitionElement systolic = bp.get("Observation.component:SystolicBP");
        assertEquals(List.of("$.\"component\"[*] ? (@.\"code\".\"coding\".\"code\" == \"8480-6\" "
                        + "&& @.\"code\".\"coding\".\"system\" == \"http://loinc.org\")"),
                systolic.steps(),
                "the systolic component is not told apart by the code the profile pins");

        // The pinned code is not stated at the discriminator's path at all:
        // it sits two levels down, on a slice of the codings inside the code.
        // Finding it means walking the path through the slices the profile
        // made on the way, which is the walk this proves.
        assertTrue(bp.get("Observation.component:SystolicBP.code.coding:SBPCode.code")
                        .fixedJson().contains("8480-6"),
                "the value it is told apart by is not held on the element that states it");
    }

    @Test
    @DisplayName("a choice sliced by type is located by the key its slice is named for")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void aChoiceSlicedByTypeIsLocatedByItsKey() {
        DefinitionElement quantity = elementsOf("r4", "bmi")
                .get("Observation.value[x]:valueQuantity");
        assertEquals(List.of("$.\"valueQuantity\"[*]"), quantity.steps(),
                "a choice sliced by type asks for a predicate it does not need");
    }

    @Test
    @DisplayName("an element is counted inside its own parent, not across the document")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void anElementIsCountedInsideItsOwnParent() {
        Map<String, DefinitionElement> patient = elementsOf("r4", "Patient");

        DefinitionElement name = patient.get("Patient.contact.name");
        assertEquals("Patient.contact", name.parentId(),
                "a contact's name is not looked for inside a contact");
        assertEquals(List.of("$.\"name\"[*]"), name.steps(),
                "a contact's name is located from the document instead of from its contact");
        assertEquals(Integer.valueOf(1), name.max(),
                "one name per contact is the constraint this proves is per contact");

        // Three contacts each with a name are three names from the root and
        // one name per contact; the second is what the definition says, and
        // it is only sayable because the step is relative and the parent is
        // named.
        assertEquals("Patient", patient.get("Patient.contact").parentId());
        assertNull(patient.get("Patient").parentId(), "the resource itself is inside nothing");
        assertEquals(List.of(), patient.get("Patient").steps(),
                "the resource itself is located by a step rather than being the instance");
    }

    @Test
    @DisplayName("an extension slice is told apart by the url of the extension it says it is")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void anExtensionSliceIsToldApartByItsCanonical() {
        DefinitionElement race = elementsOf("r4", "clinicaldocument").entrySet().stream()
                .filter(e -> e.getKey().startsWith("Composition.extension:"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        assertTrue(race.steps().get(0).startsWith("$.\"extension\"[*] ? (@.\"url\" == \"http"),
                "an extension slice is not told apart by its url: " + race.steps());
    }

    @Test
    @DisplayName("what an element holds and is bound to comes off the snapshot with it")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void whatAnElementHoldsComesWithIt() {
        Map<String, DefinitionElement> patient = elementsOf("r4", "Patient");

        DefinitionElement gender = patient.get("Patient.gender");
        assertEquals("required", gender.bindingStrength());
        assertTrue(gender.bindingValueSet().contains("administrative-gender"),
                "the binding is not carried: " + gender.bindingValueSet());
        assertEquals(0, gender.min());
        assertEquals(Integer.valueOf(1), gender.max());

        DefinitionElement managing = patient.get("Patient.managingOrganization");
        assertEquals(List.of("Reference"),
                managing.types().stream().map(DefinitionElement.Type::code).toList());
        assertTrue(managing.types().get(0).targets().stream()
                        .anyMatch(target -> target.endsWith("/Organization")),
                "a reference arrives without what it may point at: " + managing.types());
    }

    /** Every element of one carried structure, by id. */
    private static Map<String, DefinitionElement> elementsOf(String face, String name) {
        for (FaceRootPackages.Definition definition
                : FaceRootPackages.definitionsFor(face, Set.of("StructureDefinition"))) {
            if (!definition.url().endsWith("/" + name)) {
                continue;
            }
            Map<String, DefinitionElement> byId = new LinkedHashMap<>();
            for (DefinitionElement row : DefinitionElements.of(definition.document()).elements()) {
                byId.put(row.id(), row);
            }
            return byId;
        }
        throw new IllegalStateException(face + " carries no structure named " + name);
    }
}
