package cloud.jengu.dbo.core.api.feed;

import java.time.Instant;

/**
 * One change, carrying the EXACT version's payload and its schema version
 * (joined from history) — an UPDATED event delivers that version even when
 * later versions exist, and consumers convert at apply (§6). Idempotent
 * apply dedupes on (objectId, versionId) (REQ-DBO-FEED-IDEMPOTENT-DELIVERY).
 */
public record FeedItem(
        long seq,
        String objectId,
        String typeName,
        long versionId,
        ChangeKind kind,
        Instant committedAt,
        byte[] payload,
        boolean deleted,
        String payloadVersion,
        java.util.List<String> shape) {

    /** Compatibility with callers that predate {@code shape} (#131): the
     * written-under stamp travels the wire beside {@code payloadVersion}, so
     * a mirrored copy keeps the stamp of the store that validated it. */
    public FeedItem(long seq, String objectId, String typeName, long versionId,
            ChangeKind kind, Instant committedAt, byte[] payload, boolean deleted,
            String payloadVersion) {
        this(seq, objectId, typeName, versionId, kind, committedAt, payload, deleted,
                payloadVersion, null);
    }
}
