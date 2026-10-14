package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.wire.RecordWire;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file of secrets in the format SOPS writes, read without SOPS.
 *
 * <p><b>Each value encrypted on its own</b>, under one data key, with AES-GCM
 * and the path to the value as additional data, so a value moved to another
 * name does not open; and <b>a MAC over every value</b>, encrypted under the
 * same key with the time the file was last written as additional data, so a
 * value dropped, added or swapped between two files does not pass either.
 *
 * <p>The data key is wrapped once per recipient. This reads only the
 * recipients a Vault's Transit engine wrapped; the owner keeps others — their
 * own age key — so that what is in the repository can be opened without the
 * store.
 */
final class SopsFile {

    /** A Transit key the data key was wrapped by, as the file names it. */
    record VaultRecipient(String enginePath, String keyName, String wrapped) {
    }

    private static final Pattern ENCRYPTED = Pattern.compile(
            "ENC\\[AES256_GCM,data:(.*),iv:(.*),tag:(.*),type:(\\w+)\\]");

    private final String name;
    private final Map<?, ?> document;
    private final Map<?, ?> metadata;

    private SopsFile(String name, Map<?, ?> document, Map<?, ?> metadata) {
        this.name = name;
        this.document = document;
        this.metadata = metadata;
    }

    /**
     * @param name what the file is called, for every refusal
     * @throws IllegalArgumentException when it is not a file SOPS wrote
     */
    static SopsFile parse(String name, byte[] bytes) {
        Object read;
        try {
            read = RecordWire.read(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException(name + " is not JSON: " + unreadable.getMessage());
        }
        if (!(read instanceof Map<?, ?> document)
                || !(document.get("sops") instanceof Map<?, ?> metadata)
                || !(metadata.get("mac") instanceof String)
                || !(metadata.get("lastmodified") instanceof String)) {
            throw new IllegalArgumentException(name + " is not a file SOPS encrypted: it "
                    + "carries no sops block with a MAC");
        }
        return new SopsFile(name, document, metadata);
    }

    /** What the file is called. */
    String name() {
        return name;
    }

    /** The names it holds, which SOPS leaves readable: what it declares, before opening it. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        for (Object key : document.keySet()) {
            if (!"sops".equals(key)) {
                names.add(String.valueOf(key));
            }
        }
        return names;
    }

    /** The Transit keys its data key was wrapped by. */
    List<VaultRecipient> vault() {
        List<VaultRecipient> recipients = new ArrayList<>();
        if (metadata.get("hc_vault") instanceof List<?> named) {
            for (Object one : named) {
                if (one instanceof Map<?, ?> recipient && recipient.get("enc") instanceof String
                        wrapped) {
                    recipients.add(new VaultRecipient(String.valueOf(recipient.get("engine_path")),
                            String.valueOf(recipient.get("key_name")), wrapped));
                }
            }
        }
        return recipients;
    }

    /**
     * Its secrets, opened with the data key and checked against the MAC.
     *
     * <p>Only names with a string value at the top: a secret is reached by its
     * name, and anything else would be a shape nobody reaches.
     *
     * @throws IllegalArgumentException when a value does not open, the MAC
     *                                  does not agree, or a value is not a
     *                                  secret
     */
    Map<String, String> open(byte[] dataKey) {
        MessageDigest mac = sha512();
        Map<String, String> secrets = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : document.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("sops".equals(key)) {
                continue;
            }
            List<String> path = List.of(key);
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException(name + ": '" + key + "' is not a string, and "
                        + "a secret is one value reached by its name");
            }
            Opened opened = decrypt(value, String.join(":", path) + ":", dataKey, key);
            if (!"str".equals(opened.type())) {
                throw new IllegalArgumentException(name + ": '" + key + "' is a "
                        + opened.type() + ", and a secret is a string");
            }
            mac.update(opened.plain().getBytes(StandardCharsets.UTF_8));
            secrets.put(key, opened.plain());
        }
        String said = decrypt(String.valueOf(metadata.get("mac")),
                String.valueOf(metadata.get("lastmodified")), dataKey, "the MAC").plain();
        String computed = HexFormat.of().withUpperCase().formatHex(mac.digest());
        if (!MessageDigest.isEqual(said.getBytes(StandardCharsets.UTF_8),
                computed.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(name + " does not agree with its MAC: a value "
                    + "was changed, added or removed outside SOPS");
        }
        return secrets;
    }

    private record Opened(String plain, String type) {
    }

    private Opened decrypt(String value, String additional, byte[] dataKey, String what) {
        Matcher m = ENCRYPTED.matcher(value);
        if (!m.matches()) {
            throw new IllegalArgumentException(name + ": '" + what + "' is not encrypted, and "
                    + "every value of a secrets file is");
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] data = base64.decode(m.group(1));
            byte[] tag = base64.decode(m.group(3));
            byte[] sealed = new byte[data.length + tag.length];
            System.arraycopy(data, 0, sealed, 0, data.length);
            System.arraycopy(tag, 0, sealed, data.length, tag.length);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(tag.length * 8, base64.decode(m.group(2))));
            gcm.updateAAD(additional.getBytes(StandardCharsets.UTF_8));
            return new Opened(new String(gcm.doFinal(sealed), StandardCharsets.UTF_8),
                    m.group(4));
        } catch (GeneralSecurityException | IllegalArgumentException refused) {
            throw new IllegalArgumentException(name + ": '" + what + "' does not open with "
                    + "the data key its recipient unwrapped", refused);
        }
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (GeneralSecurityException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
