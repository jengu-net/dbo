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

    /**
     * Replaced whole rather than mutated in place.
     *
     * <p>A registration can change while the store is serving — a tenant
     * authors a search parameter, and the type's extractor and declared
     * indexes are not what they were. Every read here happens on a request
     * thread, so the map a reader is walking must never be the one a writer is
     * editing: copy-on-write gives each reader a map that is complete and
     * consistent, at the cost of an allocation on a change that happens
     * roughly never. Insertion order is kept because schema creation walks
     * {@link #all()} and a stable order makes two deployments comparable.
     */
    private volatile Map<String, TypeRegistration> byName;

    public TypeRegistry(Collection<TypeRegistration> registrations) {
        Map<String, TypeRegistration> initial = new LinkedHashMap<>();
        for (TypeRegistration r : registrations) {
            if (initial.put(r.typeName(), r) != null) {
                throw new IllegalArgumentException("duplicate type registration: " + r.typeName());
            }
        }
        byName = initial;
    }

    public TypeRegistration require(String typeName) {
        TypeRegistration r = byName.get(typeName);
        if (r == null) {
            throw new UnknownTypeException(typeName);
        }
        return r;
    }

    /**
     * Swaps one type's registration for another of the same name.
     *
     * <p>Refused for a type nobody registered, and for a replacement that
     * renames or re-domains it: this exists so a type's extractor and indexes
     * can change under a live store, not so a store's catalogue can be edited.
     * A registration arriving for a type the store never had would create a
     * type nothing set a schema up for.
     */
    public synchronized void replace(TypeRegistration replacement) {
        TypeRegistration existing = require(replacement.typeName());
        if (!existing.domain().equals(replacement.domain())) {
            throw new IllegalArgumentException(replacement.typeName() + " is registered in domain '"
                    + existing.domain() + "' and the replacement says '" + replacement.domain()
                    + "' — its rows live in the first one");
        }
        Map<String, TypeRegistration> next = new LinkedHashMap<>(byName);
        next.put(replacement.typeName(), replacement);
        byName = next;
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
