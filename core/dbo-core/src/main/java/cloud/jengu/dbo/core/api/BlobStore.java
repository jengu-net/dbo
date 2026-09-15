package cloud.jengu.dbo.core.api;

import java.util.Optional;

/**
 * Binary content a tenant holds, kept whole rather than as a record.
 *
 * <p>A recording, a scanned referral, a photograph: content that is
 * identifying <em>as a whole</em>, where nothing useful is gained by taking it
 * apart and a great deal is lost. It is held as the bytes it arrived as and
 * returned as the bytes it arrived as — a scanned document that comes back
 * re-encoded is not the document somebody signed.
 *
 * <p><b>Where it lives is the whole design.</b> An implementation belongs to
 * one tenant and puts its content where that tenant's other durable state
 * already is, so erasure-by-drop reaches it for the same reason it reaches
 * everything else: dropping the tenant drops what the tenant held. A store
 * that kept blobs somewhere else would be a second place erasure has to
 * remember to reach, and the one nobody notices is forgotten.
 *
 * <p>The interface is deliberately small and says nothing about where the
 * bytes go. A deployment large enough to want an object store gets one behind
 * this same shape, and a small one is not quietly running a different promise.
 */
public interface BlobStore {

    /**
     * Content, and what it was called when it arrived.
     *
     * @param key      what this store calls it, chosen by the store
     * @param media    the media type as the writer stated it, never inferred
     * @param content  the bytes, exactly as they were written
     */
    record Blob(String key, String media, byte[] content) {

        public int size() {
            return content.length;
        }
    }

    /**
     * Keeps the bytes and answers with the key to read them back by.
     *
     * <p>The key is this store's to choose. A caller that named its own would
     * be deciding where somebody else's content lives, and two callers would
     * eventually choose the same name for different content.
     */
    String put(byte[] content, String media);

    /** The content under this key, or nothing — never a different blob. */
    Optional<Blob> get(String key);

    /**
     * Forgets one blob.
     *
     * @return whether there had been anything to forget, so a caller can tell
     *         a deletion from a key that was never here
     */
    boolean drop(String key);

    /** How many this tenant holds, for a deployment reporting on itself. */
    long count();
}
