package cloud.jengu.dbo.core.api;

/**
 * Exactly one primary identity class per registered type (§12,
 * REQ-DBO-CORE-DECLARED-IDENTITY). There is no fourth class.
 */
public enum IdentityClass {
    /** Identity is the canonical url (stored under {@link Identifier#CANONICAL_SYSTEM}). */
    CANONICAL,
    /** Identity is carried by designated identifier systems, in trust order. */
    IDENTIFIER,
    /** Store-assigned id only; never shadows, never dedupes across stores. */
    INTERNAL,
}
