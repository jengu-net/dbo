package cloud.jengu.dbo.core.face;

import java.util.Arrays;

/**
 * Whether two documents say the same thing.
 *
 * <p>The engine cannot answer this. "The same object" is a domain question:
 * whether key order matters, whether {@code 1.50} and {@code 1.5} are one
 * number, whether whitespace between tokens means anything. The engine's own
 * answer — comparing bytes — says two documents differ because a tool wrote
 * their fields in a different order, and an import that re-writes every object
 * on a re-run is how a "no-op" becomes a version bump on a whole tenant.
 *
 * <p><b>A canonical form, not a comparison.</b> A caller that has one document
 * and a thousand to check it against canonicalises once; a caller storing a
 * digest keeps the canonical bytes' digest and never holds the documents at
 * all. Returning the form rather than a boolean is what makes both possible.
 *
 * <p><b>It is not a normalisation of stored truth.</b> The canonical form is
 * for comparing and is never written back: a payload is returned to a reader
 * as it arrived (REQ-DBO-CORE-PAYLOAD-IS-TRUTH), and a face that re-rendered
 * one through a model would drop what that model does not define
 * (REQ-DBO-CORE-DECLARED-TRUTH-FORM). Canonicalising the document tree
 * has neither hazard: nothing is interpreted, so nothing can be lost.
 */
@FunctionalInterface
public interface DocumentEquivalence {

    /**
     * The form two documents share exactly when they say the same thing.
     *
     * @throws IllegalArgumentException when the bytes are not a document this
     *                                  face can read — the caller's mistake,
     *                                  said as one
     */
    byte[] canonical(byte[] document);

    /** Whether these two are the same object, said the same way or not. */
    default boolean same(byte[] one, byte[] other) {
        return Arrays.equals(canonical(one), canonical(other));
    }
}
