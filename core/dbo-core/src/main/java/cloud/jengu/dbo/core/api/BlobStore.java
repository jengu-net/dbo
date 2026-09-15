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
    record Blob(String key, String media, byte[] content, String person) {

        public Blob(String key, String media, byte[] content) {
            this(key, media, content, null);
        }


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

    /**
     * The same, for content that is about somebody.
     *
     * <p>The writer names the person; nothing here reads the bytes. A store
     * that can seal keeps it under that person's key, so destroying the key
     * destroys the content — crypto-shredding reaching a recording for the
     * same structural reason dropping a tenant reaches its rows, rather than
     * because something remembered to go and delete it.
     *
     * <p>Refused by a store that cannot seal, and deliberately not accepted
     * quietly: a caller who named a subject believes the content is protected,
     * and storing it in the clear while they believe that is worse than
     * telling them no.
     */
    default String put(byte[] content, String media, String person) {
        throw new UnsupportedOperationException("this store cannot seal content to a person: "
                + "it holds no key for one, so content named for somebody would be kept in "
                + "the clear while whoever wrote it believed otherwise");
    }

    /** Whether content named for a person is sealed to them here. */
    default boolean seals() {
        return false;
    }

    /**
     * Content that was here and whose person has been erased.
     *
     * <p>Distinct from nothing being found, because they are different facts
     * and only one of them is an answer: an auditor asking what became of a
     * recording is told it was destroyed with its subject, rather than that
     * this store has never heard of it.
     */
    class ErasedException extends RuntimeException {

        private final String key;

        public ErasedException(String key) {
            super("the person this content was about has been erased, so it cannot be read: "
                    + key);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /**
     * Puts content back under the key it already had.
     *
     * <p>The exception to the rule above, and it does not break it: a key
     * arriving with an archive is not a caller choosing where somebody else's
     * content lives, it is this store being told what it chose before. The
     * alternative is a restore that keeps every byte and renames it, so each
     * record that pointed at a scan arrives pointing at nothing — which reads
     * as a clean import, because the content is all there and only its name is
     * wrong.
     *
     * <p>For putting an archive back, and nothing else. Anything writing new
     * content uses {@link #put} and is given a key.
     */
    void restore(String key, byte[] content, String media);

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
