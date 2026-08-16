package cloud.jengu.dbo.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A write: the whole payload, never a patch. {@code id} null on create-with-
 * generated-id; {@code expectedVersion} null skips the optimistic check.
 *
 * <p>{@code recordedVersion} and {@code recordedAt} are for <b>replaying a
 * history that already happened somewhere else</b> — an import carrying a
 * tenant's versions across from another store. Left null, the store assigns
 * the next version and the current time, which is what every ordinary write
 * wants.
 *
 * <p>{@code restoring} says something different and is easy to conflate with
 * them: that this write <b>re-establishes state that already existed</b>
 * rather than making a change. It is set on every write an import performs,
 * whether or not the history's own versions travel with it — the two are
 * orthogonal, and treating "carries a version" as "is a restore" would leave
 * the plainest restore announcing itself as news.
 *
 * <p>They exist because a version's timestamp is the evidence. A record's
 * history says what somebody knew and acted on at a moment; replaying it with
 * today's date preserves the sequence and destroys the meaning, and an audit
 * asking "what did the clinician see when they signed this?" would get an
 * answer that is confidently wrong.
 */
public record PutRequest(String typeName, String id, Long expectedVersion, byte[] payload,
                         Long recordedVersion, Instant recordedAt, boolean restoring) {

    public PutRequest {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(payload, "payload");
    }

    /** An ordinary write: the store assigns the version and the timestamp. */
    public PutRequest(String typeName, String id, Long expectedVersion, byte[] payload) {
        this(typeName, id, expectedVersion, payload, null, null, false);
    }

    /** Compatibility with callers that predate {@code restoring}. */
    public PutRequest(String typeName, String id, Long expectedVersion, byte[] payload,
            Long recordedVersion, Instant recordedAt) {
        this(typeName, id, expectedVersion, payload, recordedVersion, recordedAt,
                recordedVersion != null);
    }

    public static PutRequest create(String typeName, byte[] payload) {
        return new PutRequest(typeName, null, null, payload);
    }

    public static PutRequest update(String typeName, String id, long expectedVersion, byte[] payload) {
        return new PutRequest(typeName, id, expectedVersion, payload);
    }

    /**
     * One version of an object as it was recorded elsewhere, replayed in
     * ascending version order so the destination's history matches the
     * source's — same versions, same moments.
     */
    public static PutRequest restored(String typeName, String id, byte[] payload,
            long recordedVersion, Instant recordedAt) {
        return new PutRequest(typeName, id, null, payload,
                recordedVersion, Objects.requireNonNull(recordedAt, "recordedAt"), true);
    }

    /**
     * A restore that lets the destination assign its own version — the same
     * re-establishing of state, without the source's history travelling.
     */
    public static PutRequest restoring(String typeName, String id, byte[] payload) {
        return new PutRequest(typeName, id, null, payload, null, null, true);
    }

    /** Whether this write carries a history's own version and moment. */
    public boolean carriesRecordedHistory() {
        return recordedVersion != null;
    }

    /**
     * Whether this write re-establishes state rather than changing it, and so
     * must not be announced to anyone downstream (jengu-platform#872).
     */
    public boolean isRestore() {
        return restoring;
    }
}
