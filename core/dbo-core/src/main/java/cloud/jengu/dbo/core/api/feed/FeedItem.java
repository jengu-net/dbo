package cloud.jengu.dbo.core.api.feed;

import java.time.Instant;

/**
 * One change, carrying the EXACT version's payload (joined from history) —
 * an UPDATED event delivers that version even when later versions exist.
 * Idempotent apply dedupes on (objectId, versionId)
 * (REQ-DBO-FEED-IDEMPOTENT-DELIVERY).
 */
public record FeedItem(
        long seq,
        String objectId,
        String typeName,
        long versionId,
        ChangeKind kind,
        Instant committedAt,
        byte[] payload,
        boolean deleted) {}
