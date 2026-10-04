package cloud.jengu.dbo.runner.transport;

/**
 * Who may ask, decided from a signature rather than a token: an ask on a
 * plane that must hold no credential is authenticated by the key the
 * participant enrolled with, and its reach derived from the same record a
 * token would have been.
 */
@FunctionalInterface
public interface SignedGrants {

    /**
     * @param participant the enrolment the ask names
     * @param signed      the bytes the signature is over, exactly as they travelled
     * @param signature   the signature the ask carried
     */
    Access of(String participant, byte[] signed, String signature);
}
