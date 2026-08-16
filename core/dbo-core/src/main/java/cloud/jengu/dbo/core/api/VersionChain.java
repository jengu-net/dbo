package cloud.jengu.dbo.core.api;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Each version of an object links to the one before it (#33).
 *
 * <p>Rewriting a version changes its link, which changes every link after it.
 * A history can therefore be checked without trusting the system that stored
 * it, and without asking anything outside — the cheap half of tamper
 * evidence, and the half that works offline.
 *
 * <p>The link covers what a forger would want to change: the content, which
 * version it claims to be, the moment it claims to have happened, and whether
 * it was a deletion. The moment is in there deliberately — a migration may
 * legitimately replay a version's original timestamp, so the timestamp has to
 * be part of what is attested rather than a field anyone can set freely.
 *
 * <p>Fields are length-prefixed before hashing. Plain concatenation lets two
 * different histories produce one digest by moving a byte across a boundary,
 * which is a cheap mistake to make and an expensive one to find.
 */
public final class VersionChain {

    /** The first version links to nothing, and says so rather than to zeros. */
    public static final byte[] GENESIS = new byte[0];

    private VersionChain() {
    }

    public static byte[] link(byte[] previous, byte[] payload, long version, Instant at,
            boolean deleted) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, previous == null ? GENESIS : previous);
            update(digest, payload == null ? GENESIS : payload);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(version).array());
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(at.toEpochMilli()).array());
            digest.update((byte) (deleted ? 1 : 0));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; if it is absent the platform
            // has larger problems than this chain.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, byte[] field) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
        digest.update(field);
    }
}
