package cloud.jengu.dbo.core.face;

import java.util.List;

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
     * Whether this store already holds exactly this, so nothing needs writing.
     *
     * <p><b>Only the codec can answer it for its own types.</b> A caller above
     * the face compares what was declared against what comes back from
     * {@link #forTransport}, and for a type whose whole form is ASSEMBLED that
     * comparison holds a source against a projection: the projection carries
     * what it derives — a concept count no declaration ever wrote — so the two
     * differ every time for a reason that is not a change, and the thing is
     * applied again on every pass. Safe, and not free: a zone of vocabularies
     * re-kept on every tick that changed nothing.
     *
     * <p>The codec has what that comparison lacks. It knows which elements are
     * derived, so it can normalise both sides the same way instead of holding
     * a written document against a computed one, and it can reach the parts
     * that live in their own home rather than inferring them from a count.
     *
     * <p><b>False is the answer whenever it cannot tell</b>, and the default.
     * A wrong "no" costs a write nobody needed; a wrong "yes" is an edit that
     * never lands and is never reported — so anything undecidable, unreadable
     * or merely unimplemented has to read as not held. That is also why this
     * is a default rather than an abstract method: a codec that has not
     * thought about it behaves exactly as one that says no.
     *
     * @param transportedPayload the wire form, which is what a declaration is
     */
    default boolean alreadyHolds(String typeName, byte[] transportedPayload) {
        return false;
    }

    /** One accepted item of a chunk, for {@link #keep(List)}. */
    record Part(String typeName, byte[] transportedPayload) {}

    /**
     * Phase two for a whole chunk the engine accepted together. The parts of
     * a chunk have one home, and a codec that knows the whole chunk can put
     * them there in one visit; one at a time, a first sync's terminology was
     * as many transactions as it had code systems, and the round trips were
     * the time. The default visits per item, which is correct and slow.
     */
    default void keep(List<Part> parts) {
        for (Part part : parts) {
            keep(part.typeName(), part.transportedPayload());
        }
    }
}
