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

    private final Map<String, List<EnvelopeValue>> paths = new LinkedHashMap<>();
    private final List<Identifier> identifiers = new ArrayList<>();
    private final List<ReferenceEdge> references = new ArrayList<>();

    public Envelope value(String path, EnvelopeValue value) {
        Paths.requireValid(path);
        paths.computeIfAbsent(path, k -> new ArrayList<>()).add(value);
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
        return paths;
    }

    public List<Identifier> identifiers() {
        return identifiers;
    }

    public List<ReferenceEdge> references() {
        return references;
    }
}
