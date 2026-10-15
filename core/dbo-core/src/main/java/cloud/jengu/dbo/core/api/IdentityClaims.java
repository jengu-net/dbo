package cloud.jengu.dbo.core.api;

import java.util.List;

/**
 * What a record is claimed under — the one thing two stores that minted
 * different ids for it both know it by.
 *
 * <p>Held in one place because two acts depend on agreeing about it: the
 * configuration keys what it applies by this claim, and a site's record
 * comes home naming others by it. Two readings of "what is this record"
 * would let the configuration and a site each call a different token the
 * identity of the same record.
 */
public final class IdentityClaims {

    private IdentityClaims() {
    }

    /**
     * Which of the things a record calls itself is the one it is CLAIMED
     * under — the type's identity, not whichever token the extractor listed
     * first.
     *
     * <p>An envelope carries every identifier a resource bears, and for a
     * canonical type it carries the canonical identity beside them. The
     * resource's own {@code identifier} tokens come first, so claiming the
     * first one claimed a national OID as though it were the identity of a
     * type identified by its url — and the engine refused it, correctly,
     * because that is not what the type is claimed under.
     *
     * <p>The identity class is the answer and the store already holds it. For
     * {@code IDENTIFIER} the declared systems say which tokens are identity,
     * and the first of those the extractor listed wins — the extractor's order
     * IS the trust order, which is the one thing a set of systems cannot
     * carry. For {@code INTERNAL} the store's own id is the identity, and the
     * first token the record bears is what the configuration has always keyed
     * it by: keyed on nothing, every pass would make another record.
     *
     * @return the claim, or null when the record carries nothing its type is
     *         claimed under, or its type is not registered here, or it cannot
     *         be read — none of which is this method's to report
     */
    public static Identifier of(ObjectStore store, String typeName, byte[] payload) {
        TypeRegistration registration = registered(store, typeName);
        if (registration == null) {
            return null;
        }
        List<Identifier> named = named(registration, typeName, payload);
        if (named.isEmpty()) {
            return null;
        }
        return switch (registration.identityClass()) {
            case CANONICAL -> named.stream()
                    .filter(one -> Identifier.CANONICAL_SYSTEM.equals(one.system()))
                    .findFirst().orElse(null);
            case IDENTIFIER -> named.stream()
                    .filter(one -> registration.identitySystems().contains(one.system()))
                    .findFirst().orElse(null);
            case INTERNAL -> named.get(0);
        };
    }

    /**
     * Everything a record calls itself, read the way the store reads it — or
     * nothing, when its type is unknown here or it cannot be read at all.
     */
    public static List<Identifier> named(ObjectStore store, String typeName, byte[] payload) {
        TypeRegistration registration = registered(store, typeName);
        return registration == null ? List.of() : named(registration, typeName, payload);
    }

    /**
     * A type's registration, or null when the store does not know it. Not
     * this method's refusal to make: the write that follows meets an unknown
     * type and says so where the reason belongs.
     */
    private static TypeRegistration registered(ObjectStore store, String typeName) {
        try {
            return store.registrationOf(typeName);
        } catch (UnknownTypeException unknown) {
            return null;
        }
    }

    private static List<Identifier> named(TypeRegistration registration, String typeName,
            byte[] payload) {
        try {
            return registration.extractor().extract(typeName, payload).identifiers();
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * The claim as a reference that is a question — {@code
     * Type?identifier=system|value}, or {@code Type?url=value} for a canonical
     * — the form every store answers for itself.
     */
    public static String question(String typeName, Identifier claim) {
        return Identifier.CANONICAL_SYSTEM.equals(claim.system())
                ? typeName + "?url=" + claim.value()
                : typeName + "?identifier=" + claim.system() + "|" + claim.value();
    }
}
