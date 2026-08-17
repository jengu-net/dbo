package cloud.jengu.dbo.pdi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The PDI map (§14.2): which types are person types, which of their top-level
 * elements identify, and what happens to each when the reader has no key.
 *
 * <p>Everything listed is encrypted in place under the person's key. What
 * differs is the <b>disposition</b> — what a reader without the key sees in
 * its place (jengu-platform#880, ADR 0056 §7).
 */
public record PdiSpec(Map<String, Map<String, Disposition>> personTypes) {

    /** What a reader without the key gets in place of an identifying element. */
    public enum Disposition {
        /** Nothing. The element is simply absent. */
        REMOVE,
        /**
         * A coarser value that answers the questions the element was needed
         * for without identifying anybody.
         *
         * <p>This exists because some elements are identifying <em>and</em>
         * load-bearing. A birth date drives dosing, growth charts and
         * screening intervals; removing it by default would be a
         * patient-safety failure wearing compliance clothes. The coarse value
         * is computed on write and kept in the clear, because a reader with no
         * key has no plaintext to derive it from.
         */
        GENERALISE
    }

    public PdiSpec {
        Map<String, Map<String, Disposition>> copy = new LinkedHashMap<>();
        personTypes.forEach((type, elements) -> copy.put(type, Map.copyOf(elements)));
        personTypes = Map.copyOf(copy);
    }

    public boolean isPersonType(String typeName) {
        return personTypes.containsKey(typeName);
    }

    public Set<String> identifyingElements(String typeName) {
        return personTypes.getOrDefault(typeName, Map.of()).keySet();
    }

    public Disposition dispositionOf(String typeName, String element) {
        return personTypes.getOrDefault(typeName, Map.of())
                .getOrDefault(element, Disposition.REMOVE);
    }

    /**
     * The default map for the FHIR person types (R4 and R5 share these
     * top-level elements).
     *
     * <p>A jurisdiction may disagree about what identifies, which is what
     * {@link #overriddenBy} is for — the set is a default, not a law.
     */
    public static PdiSpec fhir() {
        Map<String, Disposition> elements = new LinkedHashMap<>();
        for (String removed : new String[] {
                "identifier", "name", "telecom", "address", "photo", "contact"}) {
            elements.put(removed, Disposition.REMOVE);
        }
        elements.put("birthDate", Disposition.GENERALISE);
        Map<String, Map<String, Disposition>> types = new LinkedHashMap<>();
        for (String type : new String[] {"Patient", "Practitioner", "RelatedPerson"}) {
            types.put(type, elements);
        }
        return new PdiSpec(types);
    }

    /**
     * The same map with a jurisdiction's amendments applied — an element it
     * treats as identifying that we do not, or a disposition it insists on.
     *
     * <p>What counts as identifying is a question about law rather than about
     * data, so it follows the zone rather than one global guess.
     */
    public PdiSpec overriddenBy(Map<String, Map<String, Disposition>> overrides) {
        Map<String, Map<String, Disposition>> merged = new LinkedHashMap<>();
        personTypes.forEach((type, elements) -> merged.put(type, new LinkedHashMap<>(elements)));
        overrides.forEach((type, elements) ->
                merged.computeIfAbsent(type, t -> new LinkedHashMap<>()).putAll(elements));
        return new PdiSpec(merged);
    }
}
