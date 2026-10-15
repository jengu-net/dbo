package cloud.jengu.dbo.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * Wraps signing-key private material at rest (§13.2). The key encryption
 * key is machinery custody — the same pattern as the tenant database
 * credentials; §14's tenant working key subsumes this seam when it lands.
 */
public final class KeyProtector {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec kek;
    /** What wrapped before, still opened while what it wrapped is moved; null for nothing. */
    private final KeyProtector previous;

    public KeyProtector(byte[] kek) {
        this(kek, null);
    }

    /**
     * Wrapping under one key and opening under it or the one before: what a
     * tenant's keys are while they are moved from one to the other.
     *
     * @param previous the key that wrapped before, or null
     */
    public KeyProtector(byte[] kek, byte[] previous) {
        if (kek == null || kek.length != 32) {
            throw new IllegalArgumentException("KEK must be 32 bytes");
        }
        this.kek = new SecretKeySpec(kek, "AES");
        this.previous = previous == null ? null : new KeyProtector(previous);
    }

    public byte[] wrap(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain);
            byte[] out = new byte[12 + sealed.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(sealed, 0, out, 12, sealed.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("key wrap failed", e);
        }
    }

    public byte[] unwrap(byte[] wrapped) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(128, wrapped, 0, 12));
            return cipher.doFinal(wrapped, 12, wrapped.length - 12);
        } catch (Exception e) {
            if (previous != null) {
                return previous.unwrap(wrapped);
            }
            throw new IllegalStateException("key unwrap failed — wrong KEK?", e);
        }
    }

    /** Whether this opens what it did not wrap itself: true only while keys are moved. */
    public boolean opensThePrevious() {
        return previous != null;
    }
}
