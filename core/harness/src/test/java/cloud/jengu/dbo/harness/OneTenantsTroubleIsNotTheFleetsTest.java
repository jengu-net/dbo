package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.stream.StepJoiner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant whose feed cannot be read is one tenant, not the fleet.
 *
 * <p>A tenant stops being readable while a deployment runs — its declaration
 * was refused and it went down, its storage went away, its cursor is not there
 * — and none of that is a fact about anybody else's work. The pass used to let
 * that out of its loop, so one tenant in trouble stopped every other tenant's
 * work being offered, and WHICH tenants stopped was decided by the order a
 * concurrent map happened to iterate in.
 *
 * <p><b>No world, no database, no tenant.</b> What is being proven is what the
 * loop does when one of its members throws, and a feed that throws is three
 * lines. Bringing a deployment up to arrange a broken tenant would cost a
 * minute to test a try/catch, and would prove it for one way of breaking
 * rather than for any.
 *
 * <p>Found by folding nine classes onto one runtime: a class whose whole point
 * is that an invalid declaration is refused took its tenant down, and every
 * class that ran after it failed reading a feed it had never heard of.
 */
class OneTenantsTroubleIsNotTheFleetsTest {

    @Test
    @DisplayName("a feed that cannot be read does not stop the tenants beside it being read")
    @Proving(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK)
    void oneUnreadableTenantDoesNotStopTheRest() {
        StepJoiner joiner = new StepJoiner(Map.of(), tenant -> Set.of());

        AtomicBoolean readable = new AtomicBoolean(false);
        // Several of them, because one would leave the claim resting on which
        // way a concurrent map iterated: with a broken feed on either side of
        // the healthy one, an order that reaches the healthy one first proves
        // as much as an order that reaches it last.
        joiner.follow("gone-before", unreadable());
        joiner.follow("still-here", healthy(readable));
        joiner.follow("gone-after", unreadable());

        assertDoesNotThrow(() -> joiner.joinOnce(100),
                "one tenant's feed threw out of the pass, so a deployment with a single "
                        + "tenant in trouble offers nobody's work");
        assertTrue(readable.get(),
                "the tenant that COULD be read was never read, so the pass stopped at the "
                        + "broken one and the tenants after it paid for it");
    }

    /** A tenant that has gone: its feed answers nothing, loudly. */
    private static ChangeFeed unreadable() {
        return new ChangeFeed() {
            @Override
            public FeedChunk<FeedItem> read(String cursor, int limit) {
                throw new IllegalStateException("consumer lookup failed");
            }

            @Override
            public FeedChunk<FeedItem> readFor(String consumer, int limit) {
                throw new IllegalStateException("consumer lookup failed");
            }

            @Override
            public void ack(String consumer, String cursor) {
            }

            @Override
            public void resetConsumer(String consumer, String cursor) {
            }

            @Override
            public long lag(String consumer) {
                return 0;
            }

            @Override
            public String cursorOf(String consumer) {
                return null;
            }
        };
    }

    /** One that is fine, and says it was asked. */
    private static ChangeFeed healthy(AtomicBoolean asked) {
        return new ChangeFeed() {
            @Override
            public FeedChunk<FeedItem> read(String cursor, int limit) {
                return readFor(null, limit);
            }

            @Override
            public FeedChunk<FeedItem> readFor(String consumer, int limit) {
                asked.set(true);
                return new FeedChunk<>(List.of(), null, false);
            }

            @Override
            public void ack(String consumer, String cursor) {
            }

            @Override
            public void resetConsumer(String consumer, String cursor) {
            }

            @Override
            public long lag(String consumer) {
                return 0;
            }

            @Override
            public String cursorOf(String consumer) {
                return null;
            }
        };
    }
}
