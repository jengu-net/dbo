package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.wire.RecordWire;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One document of a run's inputs, sealed for the participants meant to open
 * it and for nobody who merely carries it.
 *
 * <p>Sealed <b>per payload, wrapped per participant</b>: a random data key
 * for this document alone, wrapped once to each recipient's enrolment key.
 * Per tenant was refused because a shared router enrolled in a tenant would
 * hold that tenant's key, and the router is the carrier being excluded.
 *
 * <p>What is sealed is the <b>carrier form</b> — the record as the store's
 * encrypted disclosure mode hands it out, identifying elements already under
 * the person's key. So the person-key layer sits inside this seal, and a
 * copy still in flight after an erasure is in the same state as the store's
 * own records after a shred: opening the seal yields a record whose identity
 * no key can reassemble.
 *
 * <p>The slot and reference travel in the clear, in the manifest and here,
 * because they are what routing and resolution are made of; the reference is
 * bound into the seal as associated data, so a payload cannot be quietly
 * re-addressed to another slot.
 *
 * @param slot       which input this is
 * @param reference  {@code Type/id} of the document
 * @param wrapped    participant name to the data key wrapped to that participant
 * @param iv         the GCM nonce for the document
 * @param ciphertext the document, as a wire-encoded stored object, under the data key
 */
public record SealedPayload(String slot, String reference, Map<String, String> wrapped,
        byte[] iv, byte[] ciphertext) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public SealedPayload {
        wrapped = wrapped == null ? Map.of() : Map.copyOf(wrapped);
    }

    /** Seals one document to every recipient named; refuses an empty audience by construction. */
    public static SealedPayload seal(String slot, String reference, StoredObject document,
            Map<String, ParticipantKey> recipients) {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("a payload is sealed to somebody, and nobody was named");
        }
        byte[] dataKey = new byte[32];
        RANDOM.nextBytes(dataKey);
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(128, iv));
            gcm.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = gcm.doFinal(RecordWire.write(RecordWire.encode(document))
                    .getBytes(StandardCharsets.UTF_8));
            Map<String, String> wrapped = new LinkedHashMap<>();
            recipients.forEach((participant, key) ->
                    wrapped.put(participant, KeyWrap.wrap(dataKey, key).render()));
            return new SealedPayload(slot, reference, wrapped, iv, sealed);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("sealing failed", failed);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * The holder's side: unwraps the data key with the private half it never
     * sent, and yields the document. A participant this was not wrapped to
     * has nothing to unwrap and is told so; a wrong key fails the tag.
     */
    public StoredObject open(String participant, PrivateKey mine) throws GeneralSecurityException {
        String wrap = wrapped.get(participant);
        if (wrap == null) {
            throw new GeneralSecurityException("'" + participant + "' is not among those this "
                    + "payload was sealed to: " + wrapped.keySet());
        }
        byte[] dataKey = KeyWrap.unwrap(KeyWrap.Wrapped.parse(wrap), mine);
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(128, iv));
            gcm.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
            byte[] plain = gcm.doFinal(ciphertext);
            return RecordWire.decode(RecordWire.read(new String(plain, StandardCharsets.UTF_8)),
                    StoredObject.class);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SealedPayload other && slot.equals(other.slot)
                && reference.equals(other.reference) && wrapped.equals(other.wrapped)
                && Arrays.equals(iv, other.iv) && Arrays.equals(ciphertext, other.ciphertext);
    }

    @Override
    public int hashCode() {
        return reference.hashCode();
    }

    @Override
    public String toString() {
        return "SealedPayload[" + slot + " = " + reference + ", to " + wrapped.keySet() + "]";
    }
}
