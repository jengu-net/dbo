package cloud.jengu.dbo.core.face;

/**
 * For types whose stored form is smaller than the thing itself
 * (REQ-DBO-CORE-DECLARED-TRUTH-FORM).
 *
 * <p>A CodeSystem is stored as a metadata shell while its concepts live in the
 * native form, which is what makes {@code $expand} a query rather than a parse.
 * The shell is honest FHIR — it says {@code content: not-present} — but a
 * dependent that received only the shell would hold a CodeSystem it cannot
 * answer anything about, and would have no way to rebuild what it is missing
 * from the stream alone.
 *
 * <p>So a stream carries the whole thing and each end keeps its own form:
 *
 * <ul>
 *   <li>{@link #forTransport} — at the source, the stored form is reassembled
 *       into what a reader expects to receive;</li>
 *   <li>{@link #storedFormOf} and {@link #keep} — at the destination, that is
 *       taken apart again, in two phases: the shell the engine stores is
 *       derived first, and the parts with their own home land only after the
 *       engine accepted the write — never for a shadowed item.</li>
 * </ul>
 *
 * <p><b>Why {@code storedFormOf} returns the shell instead of writing it.</b> The
 * sync engine writes the object under the SOURCE's object id, and its origin
 * bookkeeping — which is what shadowing is built on — is keyed by that id. A
 * codec that wrote the object itself would let the destination choose an
 * identity of its own (by canonical url, say), and the two keys would drift
 * apart silently. So the codec never writes the object: it handles only the
 * part the engine has no place for, and hands the rest straight back.
 *
 * <p>Engine-neutral by construction: nothing here names a resource, a version,
 * or a domain. A face declares which of its types work this way.
 *
 * <p><b>A face obligation, and deliberately not a declared capability.</b>
 * Reassembling a vocabulary means reading the concepts THIS TENANT holds, so a
 * grain codec holds the tenant's store — and {@link DomainFace} draws its line
 * exactly there. A declared capability is a pure function; something holding a
 * store is an actor, and an actor could write inside an engine transaction
 * where two faces over one store would disagree about who did what. So this is
 * constructed by the version and passed, which is the second of that contract's
 * three tiers rather than an exception to its first.
 */
public interface GrainCodec {

    /** Whether this type's stored form differs from what a stream should carry. */
    boolean handles(String typeName);

    /** At the source: the stored form, made whole for the wire. */
    byte[] forTransport(String typeName, byte[] storedPayload);

    /**
     * At the destination, phase one: what the engine should store — the shell —
     * derived from the wire form and NOTHING ELSE. Pure on purpose:
     * this runs before the engine has decided whether the write is even
     * accepted, and a transform that already moved the concepts into the
     * native form had replaced a local override's answers before shadowing
     * could protect them — the parked publication answered $lookup while the
     * protected record could not.
     */
    byte[] storedFormOf(String typeName, byte[] transportedPayload);

    /**
     * At the destination, phase two: the parts with their own home, put there.
     * Called only after the engine ACCEPTED the write — a shadowed item never
     * gets here, which is what keeps the local decision's answers its own.
     */
    void keep(String typeName, byte[] transportedPayload);

    /**
     * At the destination: the wire form taken apart. Parts with their own home
     * are written there; the returned bytes are what the engine stores.
     */
}
