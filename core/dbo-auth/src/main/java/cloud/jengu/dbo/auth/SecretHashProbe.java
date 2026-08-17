package cloud.jengu.dbo.auth;

/**
 * Lets a test check a distributed verifier actually verifies, without making
 * {@link SecretHash} public.
 *
 * <p>The property is worth asserting rather than assuming: a hash that
 * reaches a bench and then fails to match is an offline sign-in that fails at
 * the bedside, which is the exact moment nobody can debug it.
 */
public final class SecretHashProbe {

    private SecretHashProbe() {
    }

    public static boolean verifies(String secret, String encoded) {
        return SecretHash.verify(secret, encoded);
    }
}
