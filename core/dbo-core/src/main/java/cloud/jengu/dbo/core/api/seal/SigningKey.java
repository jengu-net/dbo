package cloud.jengu.dbo.core.api.seal;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The public half of the keypair a participant signs its links with,
 * offered at enrolment beside the one it is sealed to.
 *
 * <p>Two keys rather than one because the curve that agrees cannot sign:
 * X25519 is agreement only, and Ed25519 is signature only, and deriving one
 * from the other is more cryptography to own than a second 32 bytes. Both
 * halves are the participant's own, generated before it enrolled; the store
 * holds the public halves and nothing that signs or opens.
 *
 * <p>What a signature buys is non-forgery and non-repudiation — a router
 * cannot manufacture an edge's access link, an edge cannot deny one it
 * signed. What it does not buy is omission-proofing, and that limit is
 * accepted where the chain is described.
 *
 * @param kid the RFC 7638 thumbprint, the version a signature is checked against
 * @param x   the raw 32-byte Ed25519 public value as the JWK carries it
 */
public record SigningKey(String kid, byte[] x) {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public SigningKey {
        if (x == null || x.length != 32) {
            throw new IllegalArgumentException("an Ed25519 public key is 32 bytes");
        }
        x = x.clone();
        String thumbprint = thumbprintOf(x);
        if (kid == null) {
            kid = thumbprint;
        } else if (!kid.equals(thumbprint)) {
            throw new IllegalArgumentException("kid must be the key's thumbprint");
        }
    }

    public static SigningKey parse(String jwkJson) {
        String kty = ParticipantKey.jwkField(jwkJson, "kty");
        String crv = ParticipantKey.jwkField(jwkJson, "crv");
        String x = ParticipantKey.jwkField(jwkJson, "x");
        if (!"OKP".equals(kty) || !"Ed25519".equals(crv) || x == null) {
            throw new IllegalArgumentException(
                    "a signing key is an OKP/Ed25519 JWK with its public value x");
        }
        if (ParticipantKey.jwkField(jwkJson, "d") != null) {
            throw new IllegalArgumentException("a participant offers only the public half");
        }
        return new SigningKey(ParticipantKey.jwkField(jwkJson, "kid"), B64D.decode(x));
    }

    /** The same key from JDK material, for the holder's side. */
    public static SigningKey of(PublicKey key) {
        if (!(key instanceof EdECPublicKey ed)
                || !NamedParameterSpec.ED25519.getName().equals(ed.getParams().getName())) {
            throw new IllegalArgumentException("not an Ed25519 public key");
        }
        // RFC 8032 encoding: y little-endian, with the parity of x in the top bit.
        byte[] raw = ParticipantKey.littleEndian(ed.getPoint().getY());
        if (ed.getPoint().isXOdd()) {
            raw[31] |= (byte) 0x80;
        }
        return new SigningKey(null, raw);
    }

    /** A fresh keypair for a participant to hold — generated on its side, never here in production. */
    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Ed25519 is part of the platform", impossible);
        }
    }

    /** The holder's side: a signature over bytes, base64url. */
    public static String sign(byte[] bytes, PrivateKey mine) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(mine);
            signature.update(bytes);
            return B64.encodeToString(signature.sign());
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("signing failed", failed);
        }
    }

    /** Whether the signature is this key's over these bytes; a malformed one is simply not. */
    public boolean verifies(byte[] bytes, String signature) {
        if (signature == null) {
            return false;
        }
        try {
            Signature check = Signature.getInstance("Ed25519");
            check.initVerify(toPublicKey());
            check.update(bytes);
            return check.verify(B64D.decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException not) {
            return false;
        }
    }

    public String render() {
        return "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + B64.encodeToString(x)
                + "\",\"kid\":\"" + kid + "\"}";
    }

    public PublicKey toPublicKey() {
        byte[] raw = x.clone();
        boolean xOdd = (raw[31] & 0x80) != 0;
        raw[31] &= 0x7f;
        BigInteger y = new BigInteger(1, ParticipantKey.reversed(raw));
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Ed25519 is part of the platform", impossible);
        }
    }

    static String thumbprintOf(byte[] x) {
        String canonical = "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\""
                + B64.encodeToString(x) + "\"}";
        try {
            return B64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of the platform", impossible);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SigningKey other && kid.equals(other.kid) && Arrays.equals(x, other.x);
    }

    @Override
    public int hashCode() {
        return kid.hashCode();
    }

    @Override
    public String toString() {
        return "SigningKey[" + kid + "]";
    }
}
