package cloud.jengu.dbo.pdi;

import java.util.Map;
import java.util.Set;

/**
 * The PDI map (§14.2): which types are person types, and which of their
 * top-level elements identify. Everything listed is encrypted in place
 * under the person's key; everything else stays plain and searchable.
 */
public record PdiSpec(Map<String, Set<String>> personTypes) {

    public PdiSpec {
        personTypes = Map.copyOf(personTypes);
    }

    public boolean isPersonType(String typeName) {
        return personTypes.containsKey(typeName);
    }

    public Set<String> identifyingElements(String typeName) {
        return personTypes.getOrDefault(typeName, Set.of());
    }

    /** The FHIR person types (R4 and R5 share these top-level elements). */
    public static PdiSpec fhir() {
        Set<String> elements = Set.of(
                "identifier", "name", "telecom", "address", "birthDate", "photo", "contact");
        return new PdiSpec(Map.of(
                "Patient", elements,
                "Practitioner", elements,
                "RelatedPerson", elements));
    }
}
