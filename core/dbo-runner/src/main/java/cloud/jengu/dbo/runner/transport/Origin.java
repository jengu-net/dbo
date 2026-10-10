package cloud.jengu.dbo.runner.transport;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;

/**
 * A tenant elsewhere, as a second place of it reads it: its declaration and
 * its two feeds, over whatever link reaches it.
 *
 * <p>Registered by whoever holds that link with synchronisation on, and
 * withdrawn when it is turned off or the link is let go. While one is
 * registered the place reads its origin; while none is, it serves what it
 * holds.
 */
public interface Origin {

    /** The tenant's code, which is the place's own. */
    String tenant();

    /**
     * The tenant's declaration as it is served now.
     *
     * @throws cloud.jengu.dbo.core.api.StoreUnreachableException when the link
     *         is down, which a place answers by serving the one it kept
     */
    String declaration();

    /** The tenant's records feed, read from where this place stands. */
    ChangeFeed records();

    /** The tenant's definitions feed, read from where this place stands. */
    ChangeFeed definitions();

    /**
     * The same definitions without what the tenant took from its face root,
     * for a place that takes its face from a root of its own. It shares the
     * position of {@link #definitions()}; a place reads one of the two.
     */
    ChangeFeed definitionsWithoutTheFace();

    /**
     * Where this place hands its own trail, or null when the link carries
     * none: the place then keeps its trail to itself.
     */
    default Trail trail() {
        return null;
    }
}
