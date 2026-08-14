package cloud.jengu.dbo.auth;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** RSA JWK (RFC 7517) — public material only; render and parse. */
public final class Jwk {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jwk() {
    }

    public static String render(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + kid + "\""
                + ",\"n\":\"" + B64.encodeToString(unsigned(key.getModulus())) + "\""
                + ",\"e\":\"" + B64.encodeToString(unsigned(key.getPublicExponent())) + "\"}";
    }

    public static RSAPublicKey parse(String jwkJson) {
        Object jwk = Json.parse(jwkJson);
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, B64D.decode(Json.str(jwk, "n"))),
                    new BigInteger(1, B64D.decode(Json.str(jwk, "e")))));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid RSA JWK", e);
        }
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] bytes = v.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
