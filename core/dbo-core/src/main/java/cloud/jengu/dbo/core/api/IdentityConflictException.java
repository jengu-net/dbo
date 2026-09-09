package cloud.jengu.dbo.core.api;

/**
 * Two people would have become one. Surfaced to the owner, never merged
 * (REQ-DBO-CORE-NO-IMPLICIT-MERGE).
 *
 * <p>Not two records: one human is spoken about by several, and a Person and
 * a Patient sharing a national number are that human twice rather than two of
 * them. What is refused is a second record of a type the tenant declared
 * identified by that system — where the value IS the record's identity, so a
 * second holder is a duplicate — and a link that would join two people who
 * each hold identity claims of their own.
 */
public class IdentityConflictException extends RuntimeException {

    private final String existingId;
    private final String claimingId;
    private final Identifier identifier;

    public IdentityConflictException(String existingId, String claimingId, Identifier identifier) {
        this("identifier %s|%s already claimed by %s (claim by %s)"
                .formatted(identifier.system(), identifier.value(), existingId, claimingId),
                existingId, claimingId, identifier);
    }

    private IdentityConflictException(String message, String existingId, String claimingId,
            Identifier identifier) {
        super(message);
        this.existingId = existingId;
        this.claimingId = claimingId;
        this.identifier = identifier;
    }

    /**
     * A link that would join two people who are each identified already.
     *
     * <p>It asserts that one human holds two identities. That is sometimes
     * true and sometimes a mistake, and nothing here can tell which — so it is
     * refused with both named, rather than decided quietly in a direction
     * nobody can undo.
     */
    public static IdentityConflictException wouldMerge(String existingId, String claimingId) {
        return new IdentityConflictException(
                ("linking these records would make one human of %s and %s, and both hold "
                        + "identity claims of their own. Which of them this is, or whether "
                        + "one identity should be withdrawn first, is not this store's to "
                        + "decide").formatted(existingId, claimingId),
                existingId, claimingId, null);
    }

    public String existingId() { return existingId; }

    public String claimingId() { return claimingId; }

    public Identifier identifier() { return identifier; }
}
