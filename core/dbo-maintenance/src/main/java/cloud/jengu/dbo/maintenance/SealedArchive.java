package cloud.jengu.dbo.maintenance;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Owner-key envelope encryption (§11, REQ-DBO-MNT-OWNER-KEY-ENCRYPTION):
 * a random AES-256-GCM data key encrypts the archive; the data key is wrapped
 * with the tenant owner's master key. The platform operates backups it cannot
 * read; restore structurally requires the owner. JDK crypto only.
 *
 * File layout: "DBO1" | int headerLen | headerJson | ciphertext.
 */
public final class SealedArchive {

    private static final byte[] MAGIC = "DBO1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private SealedArchive() {}

    public static void seal(byte[] plainArchive, byte[] ownerMasterKey, OutputStream out)
            throws IOException {
        try {
            SecretKey dataKey = KeyGenerator.getInstance("AES").generateKey();

            byte[] wrapIv = new byte[12];
            RANDOM.nextBytes(wrapIv);
            Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
            wrap.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ownerMasterKey, "AES"),
                    new GCMParameterSpec(128, wrapIv));
            byte[] wrappedKey = wrap.doFinal(dataKey.getEncoded());

            byte[] dataIv = new byte[12];
            RANDOM.nextBytes(dataIv);
            Cipher data = Cipher.getInstance("AES/GCM/NoPadding");
            data.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(128, dataIv));
            byte[] ciphertext = data.doFinal(plainArchive);

            String header = "{\"alg\":\"AES-256-GCM\",\"wrappedKey\":\"%s\",\"wrapIv\":\"%s\",\"dataIv\":\"%s\"}"
                    .formatted(b64(wrappedKey), b64(wrapIv), b64(dataIv));
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

            out.write(MAGIC);
            out.write(ByteBuffer.allocate(4).putInt(headerBytes.length).array());
            out.write(headerBytes);
            out.write(ciphertext);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("sealing failed", e);
        }
    }

    /** Throws on a wrong owner key (GCM authentication failure). */
    public static byte[] open(InputStream in, byte[] ownerMasterKey) throws IOException {
        byte[] magic = in.readNBytes(4);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("not a DBO sealed archive");
        }
        int headerLen = ByteBuffer.wrap(in.readNBytes(4)).getInt();
        String header = new String(in.readNBytes(headerLen), StandardCharsets.UTF_8);
        byte[] wrappedKey = unb64(field(header, "wrappedKey"));
        byte[] wrapIv = unb64(field(header, "wrapIv"));
        byte[] dataIv = unb64(field(header, "dataIv"));
        ByteArrayOutputStream ciphertext = new ByteArrayOutputStream();
        in.transferTo(ciphertext);
        try {
            Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
            unwrap.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ownerMasterKey, "AES"),
                    new GCMParameterSpec(128, wrapIv));
            byte[] dataKey = unwrap.doFinal(wrappedKey);

            Cipher data = Cipher.getInstance("AES/GCM/NoPadding");
            data.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(128, dataIv));
            return data.doFinal(ciphertext.toByteArray());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot open archive: wrong owner key?", e);
        }
    }


    /**
     * Seals as the caller writes (#35): the archive never exists whole in
     * memory, in plaintext or in ciphertext.
     *
     * <p>The wire format is unchanged — magic, header, ciphertext — so an
     * archive sealed this way opens with either reader. Closing the returned
     * stream writes the GCM tag; not closing it produces a truncated archive
     * that will fail to open, which is the correct outcome.
     */
    public static OutputStream sealing(byte[] ownerMasterKey, OutputStream out) throws IOException {
        try {
            SecretKey dataKey = KeyGenerator.getInstance("AES").generateKey();

            byte[] wrapIv = new byte[12];
            RANDOM.nextBytes(wrapIv);
            Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
            wrap.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ownerMasterKey, "AES"),
                    new GCMParameterSpec(128, wrapIv));
            byte[] wrappedKey = wrap.doFinal(dataKey.getEncoded());

            byte[] dataIv = new byte[12];
            RANDOM.nextBytes(dataIv);
            Cipher data = Cipher.getInstance("AES/GCM/NoPadding");
            data.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(128, dataIv));

            byte[] headerBytes = ("{\"alg\":\"AES-256-GCM\",\"wrappedKey\":\"%s\",\"wrapIv\":\"%s\",\"dataIv\":\"%s\"}"
                    .formatted(b64(wrappedKey), b64(wrapIv), b64(dataIv)))
                    .getBytes(StandardCharsets.UTF_8);
            out.write(MAGIC);
            out.write(ByteBuffer.allocate(4).putInt(headerBytes.length).array());
            out.write(headerBytes);
            return new javax.crypto.CipherOutputStream(out, data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("sealing failed", e);
        }
    }

    /**
     * Opens as the caller reads (#35).
     *
     * <p>Deliberately NOT {@code CipherInputStream}: that class swallows the
     * AEAD tag failure on close, so a tampered or truncated archive reads as
     * a short, clean stream. Silent truncation is the worst possible failure
     * for a restore — the destination would look successful and be missing
     * whatever came after the edit. This stream calls {@code doFinal}
     * explicitly and lets the failure out.
     *
     * <p>The tag arrives at the END, so the reader necessarily handles
     * unauthenticated plaintext until then. That is why verification is a
     * separate pass and import runs in a transaction that can roll back.
     */
    public static InputStream opening(InputStream in, byte[] ownerMasterKey) throws IOException {
        byte[] magic = in.readNBytes(4);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("not a DBO sealed archive");
        }
        int headerLen = ByteBuffer.wrap(in.readNBytes(4)).getInt();
        String header = new String(in.readNBytes(headerLen), StandardCharsets.UTF_8);
        try {
            Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
            unwrap.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ownerMasterKey, "AES"),
                    new GCMParameterSpec(128, unb64(field(header, "wrapIv"))));
            byte[] dataKey = unwrap.doFinal(unb64(field(header, "wrappedKey")));

            Cipher data = Cipher.getInstance("AES/GCM/NoPadding");
            data.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(128, unb64(field(header, "dataIv"))));
            return new AuthenticatedInput(in, data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot open archive: wrong owner key?", e);
        }
    }

    /** Decrypts on demand and refuses to end quietly on a bad tag. */
    private static final class AuthenticatedInput extends InputStream {
        private final InputStream source;
        private final Cipher cipher;
        private final byte[] chunk = new byte[8192];
        private byte[] pending = new byte[0];
        private int at;
        private boolean finished;

        AuthenticatedInput(InputStream source, Cipher cipher) {
            this.source = source;
            this.cipher = cipher;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int off, int len) throws IOException {
            while (at >= pending.length) {
                if (finished) {
                    return -1;
                }
                int n = source.read(chunk);
                if (n == -1) {
                    try {
                        pending = cipher.doFinal();
                    } catch (java.security.GeneralSecurityException e) {
                        // The one failure that must never be swallowed.
                        throw new IOException("archive failed authentication — it was truncated "
                                + "or altered after sealing", e);
                    }
                    finished = true;
                } else {
                    byte[] out = cipher.update(chunk, 0, n);
                    pending = out == null ? new byte[0] : out;
                }
                at = 0;
            }
            int take = Math.min(len, pending.length - at);
            System.arraycopy(pending, at, into, off, take);
            at += take;
            return take;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static String field(String json, String name) {
        String needle = "\"" + name + "\":\"";
        int start = json.indexOf(needle) + needle.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
