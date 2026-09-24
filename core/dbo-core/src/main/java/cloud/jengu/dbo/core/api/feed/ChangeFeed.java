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

    /**
     * The same, narrowed to what the consumer actually wants.
     *
     * <p>Selection at the upstream rather than a filter at the dependent. A
     * dependent that takes four types of a version and discards the rest has
     * always worked — it read everything and threw most of it away — and the
     * cost of that is paid by the upstream's feed, the network and the
     * dependent's own parse, once per item nobody wanted.
     *
     * <p>Defaulted so that a feed which cannot narrow still answers: it
     * returns everything, exactly as it did, and the dependent's own guard
     * keeps the promise that nothing undeclared is applied. A feed that DOES
     * narrow makes that guard cheap rather than load-bearing.
     */
    default FeedChunk<FeedItem> readFor(String consumer, int limit, FeedSelection wanted) {
        return readFor(consumer, limit);
    }

    /** Acknowledge progress. Monotonic: a stale ack is a no-op. */
    void ack(String consumer, String cursor);

    /** Explicit replay: move the consumer back (or forward) to a cursor; null = beginning. */
    void resetConsumer(String consumer, String cursor);

    /** The consumer's last acked cursor, or null if it never acked. */
    String cursorOf(String consumer);

    /** Undelivered event count behind the head (REQ-DBO-FEED-NAMED-CONSUMERS observability). */
    long lag(String consumer);
}
