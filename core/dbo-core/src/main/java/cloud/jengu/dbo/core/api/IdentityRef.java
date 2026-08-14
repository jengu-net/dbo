package cloud.jengu.dbo.core.api;

import java.util.Objects;

/**
 * The only key a conditional write accepts
 * (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS). Non-identity criteria are
 * unrepresentable by construction — a conditional write on a search result is
 * a category error, not a validation failure.
 */
public sealed interface IdentityRef {

    record Canonical(String url) implements IdentityRef {
        public Canonical {
            Objects.requireNonNull(url, "url");
        }

        Identifier asIdentifier() {
            return new Identifier(Identifier.CANONICAL_SYSTEM, url);
        }
    }

    record ByIdentifier(Identifier identifier) implements IdentityRef {
        public ByIdentifier {
            Objects.requireNonNull(identifier, "identifier");
        }
    }

    static IdentityRef canonical(String url) {
        return new Canonical(url);
    }

    static IdentityRef identifier(String system, String value) {
        return new ByIdentifier(new Identifier(system, value));
    }
}
