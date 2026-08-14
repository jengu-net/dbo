package cloud.jengu.dbo.core;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.UnknownTypeException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The registered type catalogue for one store. Fails closed: every operation
 * resolves the registration first (§12, REQ-DBO-CORE-DECLARED-IDENTITY).
 */
public final class TypeRegistry {

    private final Map<String, TypeRegistration> byName = new LinkedHashMap<>();

    public TypeRegistry(Collection<TypeRegistration> registrations) {
        for (TypeRegistration r : registrations) {
            if (byName.put(r.typeName(), r) != null) {
                throw new IllegalArgumentException("duplicate type registration: " + r.typeName());
            }
        }
    }

    public TypeRegistration require(String typeName) {
        TypeRegistration r = byName.get(typeName);
        if (r == null) {
            throw new UnknownTypeException(typeName);
        }
        return r;
    }

    public Collection<TypeRegistration> all() {
        return byName.values();
    }

    public Set<String> domains() {
        Set<String> domains = new TreeSet<>();
        for (TypeRegistration r : byName.values()) {
            domains.add(r.domain());
        }
        return domains;
    }

    /**
     * Resolves a conditional-write identity to the identifier the storage
     * layer looks up, validating the ref matches the type's declared class.
     */
    public Identifier identityIdentifier(TypeRegistration type, IdentityRef ref) {
        return switch (ref) {
            case IdentityRef.Canonical c -> {
                if (type.identityClass() != IdentityClass.CANONICAL) {
                    throw new IllegalArgumentException(
                            type.typeName() + " is " + type.identityClass()
                                    + "; canonical identity not applicable");
                }
                yield new Identifier(Identifier.CANONICAL_SYSTEM, c.url());
            }
            case IdentityRef.ByIdentifier b -> {
                if (type.identityClass() != IdentityClass.IDENTIFIER) {
                    throw new IllegalArgumentException(
                            type.typeName() + " is " + type.identityClass()
                                    + "; identifier identity not applicable");
                }
                if (!type.identitySystems().contains(b.identifier().system())) {
                    throw new IllegalArgumentException(
                            b.identifier().system() + " is not an identity-bearing system of "
                                    + type.typeName());
                }
                yield b.identifier();
            }
        };
    }

    /** True when this identifier carries identity for the type (claims are unique; conflicts surface). */
    public boolean isIdentityBearing(TypeRegistration type, Identifier identifier) {
        return switch (type.identityClass()) {
            case CANONICAL -> Identifier.CANONICAL_SYSTEM.equals(identifier.system());
            case IDENTIFIER -> type.identitySystems().contains(identifier.system());
            case INTERNAL -> false;
        };
    }
}
