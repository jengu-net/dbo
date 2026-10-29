package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.testmodel.GadgetModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feed's outbox, let go of behind its readers.
 *
 * <p>The work feed kept every event ever written. A busy place held fourteen
 * million of them, and a reader new to the feed, or one that fell behind, read
 * them all. What may go is what every reader has read past, and nothing a
 * reader has not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AFeedLetsGoOfWhatEveryReaderHasReadPastIT {

    private PgObjectStore store;
    private PgChangeFeed feed;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AFeedLetsGoOfWhatEveryReaderHasReadPastIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, GadgetModel.registrations());
        feed = new PgChangeFeed(ds, GadgetModel.DOMAIN);
    }

    @Test
    @DisplayName("the outbox lets go of what every reader has read past, a reader that has read "
            + "nothing holds all of it, and a reader dropped holds nothing")
    @Proving(DboPromises.FEED_A_FEED_LETS_GO_OF_WHAT_EVERY_READER_HAS_READ_PAST)
    void whatEveryReaderPassedGoes() {
        for (int i = 0; i < 100; i++) {
            store.put(PutRequest.create("Gadget", gadget(i)));
        }
        read("fast", 80);
        read("slow", 30);

        PgChangeFeed.Pruned nobodyYet = feed.prune(List.of(
                new PgChangeFeed.Reader("lane to the site", null, Instant.now())), 1_000_000);
        assertEquals(0, nobodyYet.removed(), "a reader that had read nothing was read past");
        assertEquals("lane to the site", nobodyYet.heldBy().name());

        PgChangeFeed.Pruned pruned = feed.prune(List.of(), 1_000_000);
        assertEquals(29, pruned.removed(), "not exactly what the slowest reader had read past");
        assertEquals("slow", pruned.heldBy().name());
        assertEquals(70, feed.readFor("slow", 1_000).items().size(),
                "the slowest reader lost something it had not read");
        assertEquals(20, feed.readFor("fast", 1_000).items().size(),
                "the fastest reader lost something it had not read");

        assertTrue(feed.dropConsumer("slow"));
        PgChangeFeed.Pruned afterDrop = feed.prune(List.of(), 1_000_000);
        assertEquals(50, afterDrop.removed(), "a dropped reader still held the outbox");
        assertEquals("fast", afterDrop.heldBy().name());
        assertEquals(20, feed.readFor("fast", 1_000).items().size());
    }

    /** Reads this many events as this consumer, and acks them. */
    private void read(String consumer, int events) {
        FeedChunk<FeedItem> chunk = feed.readFor(consumer, events);
        assertEquals(events, chunk.items().size());
        feed.ack(consumer, chunk.nextCursor());
    }

    private static byte[] gadget(int i) {
        return ("{\"serial\":\"g-%d\",\"vendor\":\"acme\",\"name\":\"gadget %d\",\"weightGrams\":%d}"
                .formatted(i, i, i)).getBytes(StandardCharsets.UTF_8);
    }
}
