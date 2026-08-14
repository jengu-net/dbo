package cloud.jengu.dbo.auth;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Compact JWS (RFC 7515), RS256 — JDK {@link Signature} only. The token
 * shape is {@code headerB64.claimsB64.signatureB64} with base64url, no
 * padding.
 */
public final class Jws {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jws() {
    }

    public static String sign(String kid, String claimsJson, PrivateKey key) {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
        String signingInput = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "." + B64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("JWS signing failed", e);
        }
    }

    /** Parsed, NOT yet verified. */
    public record Parts(String kid, String alg, String claimsJson, String signingInput, byte[] signature) {}

    public static Parts parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a compact JWS");
        }
        String headerJson = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
        Object header = Json.parse(headerJson);
        return new Parts(
                Json.str(header, "kid"),
                Json.str(header, "alg"),
                new String(B64D.decode(parts[1]), StandardCharsets.UTF_8),
                parts[0] + "." + parts[1],
                B64D.decode(parts[2]));
    }

    public static boolean verify(Parts parts, PublicKey key) {
        if (!"RS256".equals(parts.alg())) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(parts.signingInput().getBytes(StandardCharsets.US_ASCII));
            return signature.verify(parts.signature());
        } catch (Exception e) {
            return false;
        }
    }
}
