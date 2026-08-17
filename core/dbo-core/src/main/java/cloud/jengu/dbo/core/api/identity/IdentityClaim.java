package cloud.jengu.dbo.core.api.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * One claim that a subject is a particular person: an identifier, and how well
 * anybody knows it is theirs (dbo#39).
 *
 * <p>A number transcribed from a card in a pocket, one checked against the
 * issuing registry, and one cryptographically asserted by a national eID are
 * the same identifier at three strengths. Treating them alike is how a
 * transcription error silently attaches one human's records to another.
 *
 * <p><b>What kind of document this is stays with the face.</b> Passport,
 * driving licence and national number are FHIR's v2-0203 vocabulary, and the
 * engine has no business knowing them — it needs only what governs matching:
 * how strongly the claim is held, and whether it still stands.
 *
 * @param system the issuing authority's namespace. Never optional: passport
 *               and licence numbers are unique <em>per issuer</em>, so two
 *               countries may hold the same string, and matching on a bare
 *               value would join two different people.
 */
public record IdentityClaim(String system, String value,
                            Verification verification, Status status) {

    /** How well the claim is known to belong to the subject. */
    public enum Verification {
        /** Somebody said so, or it was read off a document. Nobody checked. */
        ASSERTED,
        /** Confirmed against the issuing authority, at some point. */
        CHECKED,
        /** Presented cryptographically in this session, by a trusted broker. */
        AUTHENTICATED
    }

    /** Whether the claim still stands. */
    public enum Status {
        ACTIVE,
        /**
         * Replaced by a newer claim — a renewed document, a corrected number.
         * Kept resolvable, because records written under it still refer to it.
         */
        SUPERSEDED,
        /**
         * Withdrawn by the issuer: a document reported lost or stolen.
         *
         * <p>Distinct from expiry on purpose. An expired document is merely
         * stale; a revoked one is in somebody else's hands, which makes it an
         * authentication path rather than a piece of history.
         */
        REVOKED
    }

    public IdentityClaim {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(status, "status");
        if (system.isBlank()) {
            throw new IllegalArgumentException(
                    "a claim without an issuing system is not a claim: the same number means "
                            + "different people in different namespaces");
        }
    }

    /** Read off a document, unchecked — the weakest honest claim. */
    public static IdentityClaim asserted(String system, String value) {
        return new IdentityClaim(system, value, Verification.ASSERTED, Status.ACTIVE);
    }

    /** Presented cryptographically by a broker in this session. */
    public static IdentityClaim authenticated(String system, String value) {
        return new IdentityClaim(system, value, Verification.AUTHENTICATED, Status.ACTIVE);
    }

    /** Whether this claim may drive a match without a human looking at it. */
    public boolean mayMatchAutomatically() {
        return status == Status.ACTIVE && verification != Verification.ASSERTED;
    }
}
