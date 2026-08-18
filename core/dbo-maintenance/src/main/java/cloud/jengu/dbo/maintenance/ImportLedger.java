package cloud.jengu.dbo.maintenance;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Where a destination writes down what it accepted
 * (REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED).
 *
 * <p>An import that verifies and then forgets leaves the question "what did we
 * import, and what did both parties say it was" answerable only by producing
 * the archive again — and an archive is the one artifact nobody can be relied
 * on to still have. So the root is recorded at the destination, with the two
 * keys that signed it, at the moment it is accepted.
 *
 * <p>Required, not optional: {@link TenantImport} refuses a null ledger rather
 * than importing unrecorded. The interface is here and the implementation is
 * the runtime's, because only the runtime knows what a tenant's accountability
 * trail is — maintenance knows there must be one.
 *
 * <p>Keys travel as digests. The public keys are not secret, but a fingerprint
 * is what identifies a signer in a record somebody reads later, and it is the
 * same length whatever the key was.
 */
@FunctionalInterface
public interface ImportLedger {

    /**
     * Records an accepted archive. Called once, after verification and after
     * the objects are applied — an archive that was refused was never
     * imported, and an archive that failed part-way through cannot happen
     * ({@link TenantImport#importVerified}).
     */
    void accepted(Accepted accepted);

    /**
     * What was accepted. Time is deliberately absent: the machinery that
     * writes the record stamps it, the way the audit trail already asserts
     * who and when rather than taking them as parameters.
     *
     * @param root            the root both parties signed
     * @param vendorKeyDigest fingerprint of the key that sealed it
     * @param tenantKeyDigest fingerprint of the key that countersigned
     * @param objects         objects written
     * @param unchanged       objects already present, identical, skipped
     */
    record Accepted(String root, String vendorKeyDigest, String tenantKeyDigest,
            long objects, long unchanged) {

        /** SHA-256 of a public key, hex — how a signer is named in the record. */
        public static String fingerprint(byte[] publicKey) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(publicKey));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every JRE", e);
            }
        }
    }
}
