package cloud.jengu.dbo.core.api;

import java.util.Objects;

/**
 * An external identity claim: {@code {system, value}}. FHIR Identifier in all
 * but name; the engine is version- and model-agnostic (§12).
 */
public record Identifier(String system, String value) {

    /**
     * Reserved system carrying CANONICAL-class identity: the identifier value
     * is the artifact's canonical url. Unifies canonical and identifier
     * lookups on one storage path.
     */
    public static final String CANONICAL_SYSTEM = "urn:dbo:canonical";

    public Identifier {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(value, "value");
        if (system.isBlank() || value.isBlank()) {
            throw new IllegalArgumentException("identifier system and value must be non-blank");
        }
    }
}
