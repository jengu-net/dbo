package cloud.jengu.dbo.core.face;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Many objects in one document: a search result, a history, an export (§1).
 *
 * <p>The face says what goes <b>around</b> the members and how one member is
 * spelled; the caller writes the prologue, a separator before every member but
 * the first, and the epilogue. The only state is which member is first, and
 * that is loop bookkeeping rather than format knowledge — the separator comes
 * from here, so nothing above learns that one format wants commas and another
 * wants newlines.
 *
 * <p>The caller keeps the loop deliberately. A sequence handed to a face would
 * be a cursor inside a transaction, and a face that pulls decides how long that
 * transaction lives and what becomes of it when the face throws halfway down a
 * page. A face translates; it does not act.
 *
 * <p>Writing a member into the caller's stream rather than returning its bytes
 * is what keeps a stored payload from being copied on its way out: the face
 * writes its wrapper and then the payload. Memory is one member, not one page.
 */
public interface PayloadFraming {

    /** What a document looks like around its members. */
    record Frame(byte[] prologue, byte[] separator, byte[] epilogue) {}

    /**
     * What the engine knows before it has read a single row.
     *
     * @param total     how many the query matched, or null when not counted
     * @param selfUrl   the query that produced this document
     * @param nextUrl   where the rest is, or null when this is the rest
     */
    record Facts(Long total, String selfUrl, String nextUrl) {}

    /**
     * One object in the document.
     *
     * @param role why this member is here — matched, or pulled in beside a
     *             match. A word about the document, not about FHIR.
     */
    record Member(String typeName, String id, long versionId, byte[] payload,
            String url, String role, java.util.List<String> elements,
            String source, String handling, String tag) {

        /**
         * @param source   which upstream streamed this copy, or null for the
         *                 tenant's own record — said as {@code Meta.source} (#109)
         * @param handling the declared handling class enforced on this record,
         *                 or null — said as {@code Meta.security}
         * @param tag      an operational label — a record shadowing a parked
         *                 upstream copy says so — or null; said as {@code Meta.tag}
         */
        public Member {
        }

        public Member(String typeName, String id, long versionId, byte[] payload,
                String url, String role, java.util.List<String> elements) {
            this(typeName, id, versionId, payload, url, role, elements, null, null, null);
        }

        /** A member that was matched rather than included. */
        public static final String MATCHED = "matched";

        /** A member present because something matched referred to it. */
        public static final String INCLUDED = "included";

        /**
         * The whole member, which is what a document asks for unless a caller
         * asked for less. {@code elements} names what to keep when they did —
         * the face's business, since which slots are the store's own and must
         * survive a projection is a domain question rather than a frame's.
         */
        public Member(String typeName, String id, long versionId, byte[] payload,
                String url, String role) {
            this(typeName, id, versionId, payload, url, role, null);
        }
    }

    Frame frame(String frameType, Facts facts);

    void member(Member member, OutputStream out) throws IOException;
}
