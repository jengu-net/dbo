package cloud.jengu.dbo.core.face;

import java.util.List;

/**
 * Reading, checking and writing one object's payload (§1).
 *
 * <p>Bytes are the boundary and a document is what is inside it. An interface
 * where every operation took bytes would make one write parse and discard once
 * per step — read, validate, extract, hash — which is more allocation than
 * holding a model, not less, and would make a seam built for efficiency cost
 * it. So a face parses once and hands back a document; the engine passes that
 * document back to the same face and never looks inside it.
 *
 * <p>{@code D} is the face's own representation and is never named above it.
 * The engine holds a {@code Payloads<?>}, which is what keeps a third party's
 * types from crossing a bundle boundary
 * (REQ-DBO-CONT-PRIVATE-DEPENDENCIES) while still parsing once.
 *
 * <p>Nothing here is FHIR: a type is a string, a payload is bytes. A face for a
 * domain with no clinical vocabulary implements the same three methods.
 */
public interface Payloads<D> {

    /**
     * Reads a payload into this face's own representation.
     *
     * @param typeName the declared type, for a face whose payloads do not say
     *                 what they are
     * @throws IllegalArgumentException if the bytes are not this face's format
     */
    D read(String typeName, byte[] payload);

    /**
     * What this document says it is.
     *
     * <p>Asked of the document rather than of the bytes so a caller that has
     * already read it does not read it again — the write path needs the type
     * and the verdict, and they used to cost a parse each.
     */
    String typeOf(D document);

    /**
     * What is wrong with a document, worst first; empty means nothing is.
     *
     * <p>A list rather than a thrown refusal, because the caller asking
     * whether a resource would be accepted and the write that accepts it must
     * be able to share one answer.
     */
    List<String> validate(String typeName, D document);

    /**
     * Renders a document back to bytes.
     *
     * <p>Not the way to answer a read: a payload that was stored is returned as
     * it was stored (REQ-DBO-CORE-PAYLOAD-IS-TRUTH), and rendering it again
     * would hand a reader a copy differing in whitespace and key order at
     * least. This is for a document the face itself built.
     */
    byte[] write(D document);
}
