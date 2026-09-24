package cloud.jengu.dbo.core.api.feed;

/**
 * The outbox change feed of one domain: ordered, replayable, gap-free.
 * Delivery is at-least-once; ack carries the cursor and an interrupted
 * consumer resumes from its last acked position
 * (REQ-DBO-FEED-PUSH-ACK-RESUME semantics; the push transport rides the
 * routing layer later).
 */
public interface ChangeFeed {

    /** Anonymous read from an opaque cursor (null = from the beginning). */
    FeedChunk<FeedItem> read(String cursor, int limit);

    /** Read for a named durable consumer, from its last acked position. */
    FeedChunk<FeedItem> readFor(String consumer, int limit);

    /** Acknowledge progress. Monotonic: a stale ack is a no-op. */
    void ack(String consumer, String cursor);

    /** Explicit replay: move the consumer back (or forward) to a cursor; null = beginning. */
    void resetConsumer(String consumer, String cursor);

    /** The consumer's last acked cursor, or null if it never acked. */
    String cursorOf(String consumer);

    /** Undelivered event count behind the head (REQ-DBO-FEED-NAMED-CONSUMERS observability). */
    long lag(String consumer);
}
