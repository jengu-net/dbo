package cloud.jengu.dbo.core.face;

import java.util.Optional;

/**
 * Answering a reference that is a question — a face capability, because only
 * a face knows where references live in its own payloads.
 *
 * <p>A conditional reference names its referent by a query rather than by an
 * id: {@code Organization?identifier=sys|value}. On the authored path the
 * face answers it at write time, so what is stored points at a record. A
 * declared set had no such path — declarations are written through the engine
 * with the face's grain applied, not through the face's accept path — so the
 * query was stored verbatim, and every reader following that reference found
 * a question where an id belongs.
 *
 * <p>That is worse than a logical reference, which at least does not pretend
 * to be resolvable. Hence this: the same answering, reachable from the one
 * place that applies declarations.
 */
public interface ReferenceResolution {

    /**
     * The payload with its conditional references rewritten to ids.
     *
     * @param resolver consulted before the store, for a referent that is
     *                 being created by the same act — the set answering for
     *                 itself, so the order somebody composed it in is not a
     *                 contract
     * @throws IllegalArgumentException naming the reference, when neither the
     *                 resolver nor the store can answer it. Refused rather
     *                 than stored: a reference nobody can answer is the one
     *                 thing worse than no reference at all, because it reads
     *                 as a promise
     */
    byte[] resolved(byte[] payload, Resolver resolver);

    /** What a reference's query is asked of. */
    @FunctionalInterface
    interface Resolver {

        /** The id of the one record this query names here, or empty. */
        Optional<String> resolve(String typeName, String query);

        /** Answers nothing, for a caller with no set of its own to offer. */
        Resolver NOTHING = (typeName, query) -> Optional.empty();
    }
}
