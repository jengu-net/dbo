package cloud.jengu.dbo.core.api.feed;

import java.util.List;

/**
 * The one feed contract (§10, REQ-DBO-FEED-ONE-PRIMITIVE):
 * {@code (source, cursor) → bounded chunk + next cursor}. Cursors are opaque
 * — consumers hold them, never parse them.
 */
public record FeedChunk<T>(List<T> items, String nextCursor, boolean drained) {}
