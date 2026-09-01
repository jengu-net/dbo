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
     * The shape stamp of one accepted document: for each shape it declares
     * and the pack publishes a version for, {@code shape|version} as
     * resolved by the validation that just ran
     * (REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED). The default is stampless — a
     * face over a model with no shape declarations has nothing to say, and
     * that is an answer, not a defect.
     */
    default java.util.List<String> writtenUnder(D document) {
        return java.util.List.of();
    }

    /**
     * What this document says it is.
     *
     * <p>Asked of the document rather than of the bytes so a caller that has
     * already read it does not read it again — the write path needs the type
     * and the verdict, and they used to cost a parse each.
     */
    String typeOf(D document);

    /**
     * One thing a face has to say about a document.
     *
     * @param severity {@code error}, {@code warning} or {@code information} —
     *                 the distinction a binding's strength turns on: a required
     *                 binding violated is a refusal, a preferred one is advice,
     *                 and answering both as "error" is worse than answering
     *                 neither, because callers learn to ignore the outcome
     * @param location where in the document, so somebody can find it
     */
    record Issue(String severity, String location, String message) {

        public static final String ERROR = "error";

        public boolean refuses() {
            return ERROR.equals(severity);
        }
    }

    /**
     * Everything a face has to say about a document, at every severity.
     *
     * <p>{@link #validate} is the refusing half of this and nothing else: a
     * write is held to the errors, and a caller asking {@code $validate} is
     * told the rest as well. One evaluation, two readings — two evaluations
     * would disagree eventually, which is the property this pair exists to
     * keep.
     *
     * @param shapeReference the shape to hold it to, or null for the shape the
     *                       document claims
     */
    default List<Issue> check(String typeName, D document, String shapeReference) {
        return (shapeReference == null ? validate(typeName, document)
                : validate(typeName, document, shapeReference)).stream()
                .map(message -> new Issue(Issue.ERROR, typeName, message))
                .toList();
    }

    /**
     * What is wrong with a document, worst first; empty means nothing is.
     *
     * <p>A list rather than a thrown refusal, because the caller asking
     * whether a resource would be accepted and the write that accepts it must
     * be able to share one answer.
     */
    List<String> validate(String typeName, D document);

    /**
     * The same, against the shape a <b>step</b> declares it consumes or
     * produces.
     *
     * <p>Not new machinery, deliberately: this face already validates a
     * document against the shape the document itself claims, and this is the
     * same act with the shape named by somebody else. A separate step-validation
     * path would be a second answer to "is this acceptable", and second answers
     * drift from the first.
     *
     * <p>{@code shapeReference} is <b>opaque to the engine</b>, which cannot
     * compare a shape any more than it can name one. What it means is the
     * face's: for a FHIR face it is a profile canonical, for another domain it
     * is whatever that domain pins shapes with.
     *
     * <p>A face that cannot resolve the reference says so as an issue rather
     * than passing the document: a shape nobody can find is not a shape a
     * document conformed to.
     */
    default List<String> validate(String typeName, D document, String shapeReference) {
        return List.of("this face validates against a document's own shape and not against "
                + "a named one, so '" + shapeReference + "' cannot be checked here");
    }

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
