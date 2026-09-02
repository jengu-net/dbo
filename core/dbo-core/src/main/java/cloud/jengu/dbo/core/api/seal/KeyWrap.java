package cloud.jengu.dbo.core.api.seal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A payload data key wrapped to one participant, and unwrapped only by the
 * holder of that participant's private half.
 *
 * <p>This is the store's first asymmetric encryption, and it is here for one
 * reason: a participant is something the store authenticates but, until this,
 * could not encrypt <em>to</em>. Every other key the store seals under is one
 * the sealer already holds. Sealing to a participant removes a reader — the
 * carrier — that no amount of authentication removes, and that reader exists
 * once one runner fleet carries every tenant's work.
 *
 * <p>Plain JDK, one construction: an ephemeral X25519 agreement with the
 * participant's key, HKDF-SHA256 bound to the key's thumbprint, AES-256-GCM
 * over the data key with the thumbprint as associated data. So a wrap names
 * the key version it was made to, and that name is authenticated rather than
 * decorative — a wrap re-labelled to another version fails its tag.
 *
 * <p>What this refuses: a symmetric envelope under a pre-shared secret. For a
 * participant enrolled by the same system that seals to it, such a secret was
 * minted or validated by the sealer and travelled the same path as the sealed
 * thing; the envelope would then add nothing over the transport that carried
 * it.
 */
public final class KeyWrap {

    static final String ALG = "ECDH-ES+A256GCM";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private KeyWrap() {}

    /**
     * One wrapped data key: the version it was wrapped to, the ephemeral
     * public value the holder needs to agree with, and the sealed key.
     *
     * @param kid        the thumbprint of the participant key wrapped to
     * @param ephemeral  the wrapper's one-time X25519 public value, little-endian
     * @param iv         the GCM nonce
     * @param ciphertext the data key under GCM, tag appended
     */
    public record Wrapped(String kid, byte[] ephemeral, byte[] iv, byte[] ciphertext) {

        public String render() {
            return "{\"alg\":\"" + ALG + "\",\"kid\":\"" + kid + "\",\"epk\":\""
                    + B64.encodeToString(ephemeral) + "\",\"iv\":\"" + B64.encodeToString(iv)
                    + "\",\"key\":\"" + B64.encodeToString(ciphertext) + "\"}";
        }

        public static Wrapped parse(String json) {
            if (!ALG.equals(ParticipantKey.jwkField(json, "alg"))) {
                throw new IllegalArgumentException("not a wrapped key of this store");
            }
            return new Wrapped(ParticipantKey.jwkField(json, "kid"),
                    B64D.decode(ParticipantKey.jwkField(json, "epk")),
                    B64D.decode(ParticipantKey.jwkField(json, "iv")),
                    B64D.decode(ParticipantKey.jwkField(json, "key")));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Wrapped w && kid.equals(w.kid)
                    && Arrays.equals(ephemeral, w.ephemeral) && Arrays.equals(iv, w.iv)
                    && Arrays.equals(ciphertext, w.ciphertext);
        }

        @Override
        public int hashCode() {
            return kid.hashCode();
        }

        @Override
        public String toString() {
            return "Wrapped[to " + kid + "]";
        }
    }

    /** A fresh keypair for a participant to hold — generated on its side, never here in production. */
    public static KeyPair newParticipantKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("X25519 is part of the platform", impossible);
        }
    }

    /** Wraps a data key to one participant. Only the holder of the private half can undo it. */
    public static Wrapped wrap(byte[] dataKey, ParticipantKey to) {
        try {
            KeyPair ephemeral = newParticipantKeyPair();
            byte[] shared = agree(ephemeral.getPrivate(), to);
            byte[] kek = derive(shared, to.kid());
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(128, iv));
            gcm.updateAAD(to.kid().getBytes(StandardCharsets.UTF_8));
            byte[] sealed = gcm.doFinal(dataKey);
            return new Wrapped(to.kid(), ParticipantKey.of(ephemeral.getPublic()).x(), iv, sealed);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("wrap failed", failed);
        }
    }

    /**
     * The holder's side. The private key is the participant's own and never
     * left it; a wrong key, a wrong version or a touched byte fails the tag
     * rather than yielding something.
     */
    public static byte[] unwrap(Wrapped wrapped, PrivateKey mine) throws GeneralSecurityException {
        ParticipantKey ephemeral = new ParticipantKey(null, wrapped.ephemeral());
        byte[] shared = agree(mine, ephemeral);
        byte[] kek = derive(shared, wrapped.kid());
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"),
                new GCMParameterSpec(128, wrapped.iv()));
        gcm.updateAAD(wrapped.kid().getBytes(StandardCharsets.UTF_8));
        return gcm.doFinal(wrapped.ciphertext());
    }

    private static byte[] agree(PrivateKey mine, ParticipantKey theirs)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(mine);
        agreement.doPhase(theirs.toPublicKey(), true);
        return agreement.generateSecret();
    }

    /** HKDF-SHA256, extract then one expand block: 32 bytes, bound to the version wrapped to. */
    private static byte[] derive(byte[] shared, String kid) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(shared);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(("dbo:seal:" + kid).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 1);
        return mac.doFinal();
    }
}
