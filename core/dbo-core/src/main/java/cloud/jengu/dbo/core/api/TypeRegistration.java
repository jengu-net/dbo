package cloud.jengu.dbo.core.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A type's contract with the engine (§12): its name, its declared identity
 * class, its identity-bearing systems, how it is handled, the envelope
 * extractor, and the index spec. Registration fails closed — no type without
 * an identity declaration (REQ-DBO-CORE-DECLARED-IDENTITY), no type without a
 * declared {@link Handling}, no index as an afterthought
 * (REQ-DBO-SRCH-DECLARED-INDEXES groundwork).
 *
 * <p>Failing closed on handling is the point rather than strictness for its
 * own sake. A default would be an unrecorded decision about somebody's data —
 * and whichever default were chosen would be wrong for some type, silently,
 * in a direction only visible during a restore.
 */
public record TypeRegistration(
        String typeName,
        String domain,
        IdentityClass identityClass,
        Set<String> identitySystems,
        Handling handling,
        EnvelopeExtractor extractor,
        List<IndexSpec> indexes,
        String payloadVersion) {

    /** Without an explicit payload version: "1" (models that never migrate need not care). */
    public TypeRegistration(String typeName, String domain, IdentityClass identityClass,
            Set<String> identitySystems, Handling handling, EnvelopeExtractor extractor,
            List<IndexSpec> indexes) {
        this(typeName, domain, identityClass, identitySystems, handling, extractor, indexes, "1");
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    public TypeRegistration {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(identityClass, "identityClass");
        if (handling == null) {
            throw new IllegalArgumentException(typeName + ": no declared handling — say who may "
                    + "write it, whether it may change, whether it is kept and whether it may "
                    + "leave, because no default is right for every type");
        }
        Objects.requireNonNull(extractor, "extractor");
        identitySystems = Set.copyOf(identitySystems == null ? Set.of() : identitySystems);
        indexes = List.copyOf(indexes == null ? List.of() : indexes);
        if (!NAME.matcher(typeName).matches()) {
            throw new IllegalArgumentException("invalid type name: " + typeName);
        }
        if (!DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("invalid domain name: " + domain);
        }
        switch (identityClass) {
            case IDENTIFIER -> {
                if (identitySystems.isEmpty()) {
                    throw new IllegalArgumentException(
                            typeName + ": IDENTIFIER identity requires designated identity systems");
                }
            }
            case CANONICAL -> {
                if (!identitySystems.isEmpty()) {
                    throw new IllegalArgumentException(
                            typeName + ": CANONICAL identity carries no identifier systems (url is the identity)");
                }
            }
            case INTERNAL -> {
                if (!identitySystems.isEmpty()) {
                    throw new IllegalArgumentException(
                            typeName + ": INTERNAL identity carries no identifier systems");
                }
            }
        }
        for (IndexSpec ix : indexes) {
            Paths.requireValid(ix.path());
        }
        Objects.requireNonNull(payloadVersion, "payloadVersion");
    }
}
