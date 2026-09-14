package cloud.jengu.dbo.core.api;

/**
 * An extractor the DATABASE performs, named by the function that performs it.
 *
 * <p>The envelope, the exclusive claims and the reference edges all come off
 * one walk of the document. Done in the JVM that walk needs the payload in
 * this process, which for a reindex means shipping every payload back to it —
 * and it is the largest per-write consumer of the loaded context after
 * validation. Done where the bytes already are, it is a function call in the
 * statement that stores them.
 *
 * <p><b>The engine learns nothing about what it computes.</b> It knows a name
 * and the shape of what comes back; which paths a type is indexed by, and what
 * a token or a reference means, stay entirely on the other side of the name.
 * That is the same bargain the rest of this contract makes — the engine holds
 * no FHIR — and it is why this is a name rather than a query the engine
 * assembles.
 *
 * <p><b>Still an {@link EnvelopeExtractor}.</b> The Java implementation is the
 * reference one: it is what the database's answer is compared against before a
 * type is served from the database at all, and it is what a store with no such
 * function falls back to. A codec that could only be performed in the database
 * would have nothing to be checked against.
 *
 * <p><b>What a database extractor does not answer.</b> A canonical type's
 * identity claim is the registration's knowledge rather than the document's —
 * a url in a document that is not of such a type is an ordinary value — so a
 * function reading the document alone cannot produce it, and a type whose
 * identity is canonical needs that claim added by whoever calls the function.
 */
public interface DatabaseExtractor extends EnvelopeExtractor {

    /**
     * The function's name, as SQL would write it.
     *
     * <p>It takes the payload and the type name, and returns an object of
     * {@code envelope}, {@code identifiers} and {@code references} — the three
     * parts of one walk.
     */
    String functionName();
}
