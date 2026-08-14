package cloud.jengu.dbo.core.api;

/**
 * Two objects claimed the same identity-bearing identifier. Surfaced to the
 * owner, never merged (REQ-DBO-CORE-NO-IMPLICIT-MERGE).
 */
public class IdentityConflictException extends RuntimeException {

    private final String existingId;
    private final String claimingId;
    private final Identifier identifier;

    public IdentityConflictException(String existingId, String claimingId, Identifier identifier) {
        super("identifier %s|%s already claimed by %s (claim by %s)"
                .formatted(identifier.system(), identifier.value(), existingId, claimingId));
        this.existingId = existingId;
        this.claimingId = claimingId;
        this.identifier = identifier;
    }

    public String existingId() { return existingId; }

    public String claimingId() { return claimingId; }

    public Identifier identifier() { return identifier; }
}
