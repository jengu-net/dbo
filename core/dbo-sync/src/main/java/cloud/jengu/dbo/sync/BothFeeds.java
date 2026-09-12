package cloud.jengu.dbo.sync;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Two feeds read as one, for a reader whose subject spans both.
 *
 * <p>A tenant's changes are on two feeds since its definitions became a domain
 * of their own: what happened here, and what its face gave it. Most readers
 * want one or the other and say which. A lane's declaration bound wants both,
 * because what a tenant declares is a set of types and the split runs straight
 * through it — a code system on one feed, an observation on the other.
 *
 * <p><b>One position, made of two.</b> The reader holds a single cursor
 * because that is what a reader of a feed holds, and this packs a position for
 * each side into it. Neither side's cursor is read here: they are the other
 * feeds' to mint and to understand, and this only keeps them together.
 *
 * <p><b>Order holds within a feed and not across.</b> Each side is delivered
 * in its own order and nothing is skipped on either, which is what a consumer
 * of changes needs. A total order over both would be a claim about when two
 * unrelated things happened relative to each other, and nothing in the
 * database says that.
 */
final class BothFeeds implements ChangeFeed {

    private static final String SEPARATOR = "|";

    private final ChangeFeed first;
    private final ChangeFeed second;

    BothFeeds(ChangeFeed first, ChangeFeed second) {
        this.first = first;
        this.second = second;
    }

    /**
     * Reads the first side, then as much of the second as the limit leaves.
     *
     * <p>Draining in that order rather than sharing the limit, so a caller
     * that reads until it is given nothing gets everything from both: a side
     * that is behind cannot be starved by one that is busy, because the busy
     * one runs out first and then the whole limit is the other's.
     */
    @Override
    public FeedChunk<FeedItem> read(String cursor, int limit) {
        String firstCursor = sideOf(cursor, 0);
        String secondCursor = sideOf(cursor, 1);

        FeedChunk<FeedItem> fromFirst = first.read(firstCursor, limit);
        List<FeedItem> items = new ArrayList<>(fromFirst.items());
        String nextFirst = fromFirst.nextCursor();
        String nextSecond = secondCursor;

        boolean drained = fromFirst.drained();
        int left = limit - items.size();
        if (left > 0) {
            FeedChunk<FeedItem> fromSecond = second.read(secondCursor, left);
            items.addAll(fromSecond.items());
            nextSecond = fromSecond.nextCursor();
            // Drained only when neither side has anything left: a caller that
            // stops on the first side running out would leave the other's
            // changes sitting there.
            drained = drained && fromSecond.drained();
        } else {
            drained = false;
        }
        return new FeedChunk<>(items, packed(nextFirst, nextSecond), drained);
    }

    @Override
    public FeedChunk<FeedItem> readFor(String consumer, int limit) {
        return read(cursorOf(consumer), limit);
    }

    @Override
    public void ack(String consumer, String cursor) {
        first.ack(consumer, sideOf(cursor, 0));
        second.ack(consumer, sideOf(cursor, 1));
    }

    @Override
    public void resetConsumer(String consumer, String cursor) {
        first.resetConsumer(consumer, sideOf(cursor, 0));
        second.resetConsumer(consumer, sideOf(cursor, 1));
    }

    @Override
    public String cursorOf(String consumer) {
        return packed(first.cursorOf(consumer), second.cursorOf(consumer));
    }

    /** Behind by as much as both sides are behind together. */
    @Override
    public long lag(String consumer) {
        return first.lag(consumer) + second.lag(consumer);
    }

    // -------------------------------------------------------------- cursors

    private static String packed(String firstSide, String secondSide) {
        String raw = (firstSide == null ? "" : firstSide)
                + SEPARATOR + (secondSide == null ? "" : secondSide);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * One side's position out of a packed one.
     *
     * <p>Anything that is not a position this packed reads as the beginning of
     * both — which is what a null cursor means, and is the only safe reading:
     * a consumer whose stored position cannot be understood has to be given
     * everything rather than told it is up to date.
     */
    private static String sideOf(String cursor, int side) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException notOneOfOurs) {
            return null;
        }
        int at = raw.indexOf(SEPARATOR);
        if (at < 0) {
            return null;
        }
        String one = side == 0 ? raw.substring(0, at) : raw.substring(at + 1);
        return one.isEmpty() ? null : one;
    }
}
