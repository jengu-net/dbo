package cloud.jengu.dbo.core.api.seal;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The public half of a participant's keypair, as it was offered at enrolment.
 *
 * <p>A participant generates its keypair before it is enrolled and offers
 * only this half; the private half never crosses, so a copy of the enrolment
 * records opens nothing. The store keeps this and wraps payload data keys to
 * it — what a participant may open is decided by what it holds, not by what
 * it is told.
 *
 * <p>One curve, X25519, and one carrier form, the JWK for it
 * ({@code kty} OKP, {@code crv} X25519, {@code x}). A second curve would be a
 * second thing every holder has to implement for no reader it removes.
 *
 * <p>The <b>version is the key's thumbprint</b> (RFC 7638), never a counter
 * the store hands out: a rotated key is a different thumbprint, a wrap names
 * the thumbprint it was made to, and a holder can tell an old wrap from a
 * current one without asking anybody. The version is part of what
 * authenticates the wrap, not a label beside it.
 *
 * @param kid the thumbprint, and the name a wrap carries
 * @param x   the raw 32-byte public value, little-endian as the JWK carries it
 */
public record ParticipantKey(String kid, byte[] x) {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public ParticipantKey {
        if (x == null || x.length != 32) {
            throw new IllegalArgumentException("an X25519 public key is 32 bytes");
        }
        x = x.clone();
        String thumbprint = thumbprintOf(x);
        if (kid == null) {
            kid = thumbprint;
        } else if (!kid.equals(thumbprint)) {
            // A kid that is not the thumbprint would let two records claim
            // one version, which is the thing the thumbprint exists to stop.
            throw new IllegalArgumentException("kid must be the key's thumbprint");
        }
    }

    /** The key as its holder would offer it: a JWK carrying only the public half. */
    public static ParticipantKey parse(String jwkJson) {
        String kty = jwkField(jwkJson, "kty");
        String crv = jwkField(jwkJson, "crv");
        String x = jwkField(jwkJson, "x");
        if (!"OKP".equals(kty) || !"X25519".equals(crv) || x == null) {
            throw new IllegalArgumentException(
                    "a participant key is an OKP/X25519 JWK with its public value x");
        }
        if (jwkField(jwkJson, "d") != null) {
            // A private half offered is a private half leaked; refusing it is
            // the only answer that leaves the holder still holding it alone.
            throw new IllegalArgumentException("a participant offers only the public half");
        }
        byte[] raw;
        try {
            raw = B64D.decode(x);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("x is not base64url", notBase64);
        }
        return new ParticipantKey(jwkField(jwkJson, "kid"), raw);
    }

    /** The same key from JDK material, for the holder's side of the seam. */
    public static ParticipantKey of(PublicKey key) {
        if (!(key instanceof XECPublicKey xec)
                || !(xec.getParams() instanceof NamedParameterSpec spec)
                || !NamedParameterSpec.X25519.getName().equals(spec.getName())) {
            throw new IllegalArgumentException("not an X25519 public key");
        }
        return new ParticipantKey(null, littleEndian(xec.getU()));
    }

    /** The JWK, as the store records it — public value and thumbprint. */
    public String render() {
        return "{\"kty\":\"OKP\",\"crv\":\"X25519\",\"x\":\"" + B64.encodeToString(x)
                + "\",\"kid\":\"" + kid + "\"}";
    }

    /** JDK material for key agreement. */
    public PublicKey toPublicKey() {
        try {
            return KeyFactory.getInstance("X25519").generatePublic(
                    new XECPublicKeySpec(NamedParameterSpec.X25519,
                            new BigInteger(1, reversed(x))));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("X25519 is part of the platform", impossible);
        }
    }

    /**
     * RFC 7638: SHA-256 over the required members in lexicographic order with
     * no whitespace, base64url. Deterministic, so two stores holding the same
     * key name the same version without coordination.
     */
    static String thumbprintOf(byte[] x) {
        String canonical = "{\"crv\":\"X25519\",\"kty\":\"OKP\",\"x\":\""
                + B64.encodeToString(x) + "\"}";
        try {
            return B64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of the platform", impossible);
        }
    }

    /** JDK hands the u-coordinate as a big-endian integer; the JWK carries it little-endian. */
    static byte[] littleEndian(BigInteger u) {
        byte[] big = u.toByteArray();
        byte[] out = new byte[32];
        // Strip a sign byte and left-pad to 32 before reversing.
        int start = big.length > 32 ? big.length - 32 : 0;
        int len = big.length - start;
        System.arraycopy(big, start, out, 32 - len, len);
        return reversed(out);
    }

    static byte[] reversed(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    /** A string member of a flat JSON object; null when absent. Enough for a JWK, and no parser to own. */
    static String jwkField(String json, String name) {
        String needle = "\"" + name + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        int open = json.indexOf('"', colon + 1);
        int close = json.indexOf('"', open + 1);
        if (colon < 0 || open < 0 || close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ParticipantKey other && kid.equals(other.kid)
                && Arrays.equals(x, other.x);
    }

    @Override
    public int hashCode() {
        return kid.hashCode();
    }

    @Override
    public String toString() {
        return "ParticipantKey[" + kid + "]";
    }
}
