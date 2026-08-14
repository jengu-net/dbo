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
