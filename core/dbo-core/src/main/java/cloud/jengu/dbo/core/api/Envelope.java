package cloud.jengu.dbo.core.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The derived, searchable projection of a payload (§2): typed path values,
 * external identifiers, and reference edges. Recomputed from the payload on
 * every write; always rebuildable (REQ-DBO-CORE-PAYLOAD-IS-TRUTH).
 */
public final class Envelope {

    public record ReferenceEdge(String refType, String targetType, String targetId) {}

    private final Map<String, java.util.LinkedHashSet<EnvelopeValue>> paths =
            new LinkedHashMap<>();
    private final List<Identifier> identifiers = new ArrayList<>();
    private final List<ReferenceEdge> references = new ArrayList<>();

    /**
     * One value under one key, however many times a document says it.
     *
     * <p>A search asks whether a record carries a value, and carrying it
     * twice is the same answer as carrying it once — the index answers by
     * containment. A profile constraining thirty elements repeated the word
     * {@code element} thirty times under one key, and every definition a
     * face carries paid for that in the column it is stored in.
     *
     * <p>In the order the document said them, because a list of the same
     * values in a different order is not the same envelope, and two sides
     * deriving one have to agree about which they built.
     */
    public Envelope value(String path, EnvelopeValue value) {
        Paths.requireValid(path);
        paths.computeIfAbsent(path, k -> new java.util.LinkedHashSet<>()).add(value);
        return this;
    }

    public Envelope identifier(String system, String value) {
        identifiers.add(new Identifier(system, value));
        return this;
    }

    public Envelope reference(String refType, String targetType, String targetId) {
        references.add(new ReferenceEdge(refType, targetType, targetId));
        return this;
    }

    public Map<String, List<EnvelopeValue>> paths() {
        Map<String, List<EnvelopeValue>> out = new LinkedHashMap<>();
        paths.forEach((path, values) -> out.put(path, List.copyOf(values)));
        return out;
    }

    public List<Identifier> identifiers() {
        return identifiers;
    }

    public List<ReferenceEdge> references() {
        return references;
    }
}
