package cloud.jengu.dbo.core.face;

import java.util.List;
import java.util.Objects;

/**
 * A face's {@link Payloads} that reads one payload once, however many parts of
 * a write ask for it (REQ-DBO-VER-ONE-READ-PER-REQUEST).
 *
 * <p>Accepting a write asks the same bytes two questions in the face — what
 * type is this, and is it acceptable — and one more in the engine, which
 * extracts the searchable envelope. The first two share a document because the
 * caller holds it. The third is on the other side of {@code ObjectStore.put},
 * and the document cannot travel there:
 *
 * <ul>
 *   <li>In {@code PutRequest} it would put an opaque handle in a value type,
 *       and every decorator that rebuilds a request would have to carry it.</li>
 *   <li>As an argument to {@code put} it would be a parameter every decorator
 *       must pass along, where forgetting one loses the parse silently.</li>
 * </ul>
 *
 * <p>Worse than either, a document that travels beside the bytes can arrive
 * <em>next to different bytes</em>: the isolation decorator rewrites a person's
 * payload, so an envelope extracted from the document it was handed would index
 * what the payload no longer says — the identifying values §14 exists to keep
 * out of the clear.
 *
 * <p>So nothing travels. The document is remembered against the identity of the
 * array it was read from, and is handed back only to a caller reading
 * <em>that</em> array. A decorator that rewrites the payload therefore misses
 * and the engine reads what it is actually storing: the mechanism cannot be
 * wrong, only unused.
 *
 * <p>Held for one thread and handed out once. A write is one thread from the
 * face to the engine, so the hit lands where it is meant to; a hit clears the
 * slot, so a completed write leaves nothing behind, and a read that is never
 * claimed — a refused write — is displaced by the next read on that thread.
 *
 * <p>A document is handed on rather than copied, so what reads one must not
 * change it. The write path does not: it asks the type, asks for the verdict,
 * and walks it for the envelope.
 */
public final class ReadOnce<D> implements Payloads<D> {

    private final Payloads<D> delegate;
    private final ThreadLocal<Slot<D>> held = new ThreadLocal<>();

    /** The one document a thread has read and nobody has claimed yet. */
    private record Slot<D>(byte[] payload, String typeName, D document) {}

    public ReadOnce(Payloads<D> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public D read(String typeName, byte[] payload) {
        Slot<D> slot = held.get();
        held.remove();
        if (slot != null && slot.payload() == payload && answers(slot.typeName(), typeName)) {
            return slot.document();
        }
        D document = delegate.read(typeName, payload);
        held.set(new Slot<>(payload, typeName, document));
        return document;
    }

    /**
     * Whether a document read under one type hint answers for another. A hint
     * is for a face whose payloads do not say what they are, so a document read
     * without one was read from bytes that said — and says the same thing to
     * the next caller. A document read <em>under</em> a hint is that hint's.
     */
    private static boolean answers(String read, String asked) {
        return read == null || read.equals(asked);
    }

    @Override
    public String typeOf(D document) {
        return delegate.typeOf(document);
    }

    @Override
    public List<String> validate(String typeName, D document) {
        return delegate.validate(typeName, document);
    }

    @Override
    public byte[] write(D document) {
        return delegate.write(document);
    }
}
