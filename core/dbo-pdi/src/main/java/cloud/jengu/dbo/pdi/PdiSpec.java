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
 * its place (§14).
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

    /**
     * The search paths that match on an identifying element, which is not the
     * same list as the elements themselves (#115).
     *
     * <p>FHIR reaches one element by several parameter names: {@code email} and
     * {@code phone} are both {@code telecom}, {@code family} and {@code given}
     * are both {@code name}. A guard that only knew the element names would
     * refuse {@code ?name=} and wave {@code ?email=} through — which is worse
     * than no guard, because it looks like one.
     *
     * <p>Declared rather than derived: what a parameter reaches is knowledge
     * about a domain's shapes, and a list somebody can read is what makes the
     * next addition visible.
     */
    private static final Map<String, String> PATHS_TO_ELEMENTS = Map.ofEntries(
            Map.entry("identifier", "identifier"),
            Map.entry("name", "name"),
            Map.entry("family", "name"),
            Map.entry("given", "name"),
            Map.entry("phonetic", "name"),
            Map.entry("telecom", "telecom"),
            Map.entry("email", "telecom"),
            Map.entry("phone", "telecom"),
            Map.entry("address", "address"),
            Map.entry("address_city", "address"),
            Map.entry("address_line", "address"),
            Map.entry("address_postalcode", "address"),
            Map.entry("address_state", "address"),
            Map.entry("address_country", "address"),
            Map.entry("photo", "photo"),
            Map.entry("contact", "contact"));

    /**
     * Which identifying element this search path matches on, or null.
     *
     * <p>The base path is taken, so {@code name_xct} — the exact-match twin the
     * envelope writes beside a string path — answers the same as {@code name}.
     */
    public String identifyingElementFor(String typeName, String path) {
        if (path == null) {
            return null;
        }
        // Envelope paths, which is what a Criteria carries: a parameter's code
        // with hyphens turned to underscores, and a string parameter's exact
        // twin written beside it. `Paths.requireValid` refuses the hyphenated
        // spelling outright, so there is nothing to normalise here — only the
        // twin to strip, or `name_xct` would slip past a guard on `name`.
        String base = path.endsWith("_xct") ? path.substring(0, path.length() - 4) : path;
        String element = PATHS_TO_ELEMENTS.get(base);
        return element != null && identifyingElements(typeName).contains(element) ? element : null;
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
        // Person above all: it is the human, and §14 makes it the place a
        // human's identifying data is authored. Leaving it out would protect
        // every capacity somebody acts in and not the person themselves.
        for (String type : new String[] {
                "Person", "Patient", "Practitioner", "RelatedPerson"}) {
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
