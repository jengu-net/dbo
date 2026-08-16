package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.ValueKind;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.testmodel.GadgetModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dbo#5 proof matrix: the feed primitive over the outbox, and keyset pagination. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedIT {

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static DataSource ds;
    static PgObjectStore store;
    static ChangeFeed feed;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("FeedIT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(jdbcUrl);
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());
        ds = pg;
        store = new PgObjectStore(ds, GadgetModel.registrations());
        feed = new PgChangeFeed(ds, GadgetModel.DOMAIN);
    }

    @AfterAll
    void down() {
    }

    private static byte[] gadget(String serial, String vendor, String name, int weight) {
        return ("{\"serial\":\"%s\",\"vendor\":\"%s\",\"name\":\"%s\",\"weightGrams\":%d}"
                .formatted(serial, vendor, name, weight)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] reading(String metric, int value) {
        return ("{\"metric\":\"%s\",\"value\":%d,\"gadget\":\"g\"}".formatted(metric, value))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** REQ-DBO-FEED-ONE-PRIMITIVE: commit-ordered chunks with EXACT-version payloads. */
    @Test
    void theChangeFeedDeliversEveryVersionWithItsOwnPayload() {
        PutResult r = store.put(PutRequest.create("Gadget", gadget("F-100", "feedco", "One", 1)));
        store.put(PutRequest.update("Gadget", r.id(), 1, gadget("F-100", "feedco", "Two", 2)));
        store.delete("Gadget", r.id(), 2L);

        List<FeedItem> mine = drainAll().stream().filter(i -> i.objectId().equals(r.id())).toList();
        assertEquals(3, mine.size());
        assertEquals(ChangeKind.CREATED, mine.get(0).kind());
        assertEquals(ChangeKind.UPDATED, mine.get(1).kind());
        assertEquals(ChangeKind.DELETED, mine.get(2).kind());
        // the UPDATED event still carries version 2's payload after the delete
        assertTrue(new String(mine.get(1).payload(), StandardCharsets.UTF_8).contains("\"Two\""));
        assertTrue(mine.get(2).deleted());
        assertTrue(mine.get(0).seq() < mine.get(1).seq() && mine.get(1).seq() < mine.get(2).seq());
    }

    /** REQ-DBO-FEED-IDEMPOTENT-DELIVERY + PUSH-ACK-RESUME: unacked → redelivered; acked → resumed after. */
    @Test
    void unackedItemsRedeliverAndAckResumesAfterCrash() {
        store.put(PutRequest.create("Gadget", gadget("F-200", "ackco", "A", 1)));
        store.put(PutRequest.create("Gadget", gadget("F-201", "ackco", "B", 2)));

        String consumer = "test.ack-" + System.nanoTime();
        FeedChunk<FeedItem> first = feed.readFor(consumer, 1000);
        FeedChunk<FeedItem> again = feed.readFor(consumer, 1000);
        assertEquals(itemKeys(first), itemKeys(again), "unacked items must redeliver identically");

        feed.ack(consumer, first.nextCursor());
        // a "crashed and restarted" consumer = a fresh feed instance, same store
        ChangeFeed rebooted = new PgChangeFeed(ds, GadgetModel.DOMAIN);
        assertEquals(0, rebooted.readFor(consumer, 1000).items().size());
        assertEquals(0, rebooted.lag(consumer));

        store.put(PutRequest.create("Gadget", gadget("F-202", "ackco", "C", 3)));
        List<FeedItem> next = rebooted.readFor(consumer, 1000).items();
        assertEquals(1, next.size());
        assertEquals(1, rebooted.lag(consumer));
    }

    /** Stale acks are no-ops; explicit reset replays history (REQ-DBO-FEED-NAMED-CONSUMERS). */
    @Test
    void staleAckIsIgnoredAndResetReplays() {
        store.put(PutRequest.create("Gadget", gadget("F-300", "replayco", "R1", 1)));
        store.put(PutRequest.create("Gadget", gadget("F-301", "replayco", "R2", 2)));

        String consumer = "test.replay-" + System.nanoTime();
        FeedChunk<FeedItem> chunk = feed.readFor(consumer, 1000);
        assertTrue(chunk.items().size() >= 2);
        String mid = cursorAfter(chunk.items().get(0));
        feed.ack(consumer, chunk.nextCursor());
        feed.ack(consumer, mid); // stale — must not move the cursor back
        assertEquals(chunk.nextCursor(), feed.cursorOf(consumer));

        feed.resetConsumer(consumer, mid);
        List<FeedItem> replayed = feed.readFor(consumer, 1000).items();
        assertEquals(itemKeys(new FeedChunk<>(chunk.items().subList(1, chunk.items().size()), null, true)),
                itemKeys(new FeedChunk<>(replayed, null, true)));
    }

    /** The xid barrier: parallel writers, ack-looped consumer — every commit delivered exactly once, in order. */
    @Test
    @Timeout(120)
    void concurrentWritersLoseNoEventsAndProduceNoDuplicates() throws Exception {
        String consumer = "test.gapfree-" + System.nanoTime();
        feed.resetConsumer(consumer, feed.read(null, 1).items().isEmpty() ? null : lastCursorOfFeed());

        int writers = 4;
        int perWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch done = new CountDownLatch(writers);
        List<Throwable> writerFailures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perWriter; i++) {
                        store.put(PutRequest.create("Reading", reading("w" + writer, i)));
                    }
                } catch (Throwable t) {
                    writerFailures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        Set<String> delivered = new HashSet<>();
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            FeedChunk<FeedItem> chunk = feed.readFor(consumer, 17);
            for (FeedItem item : chunk.items()) {
                // dbo#25: delivery order is (xact_id, seq)-major — commit
                // fencing outranks strict seq order; the PROMISE is
                // exactly-once, asserted via the delivered set below
                assertTrue(delivered.add(item.objectId() + "@" + item.versionId()),
                        "duplicate delivery of " + item.objectId());
            }
            if (chunk.nextCursor() != null) {
                feed.ack(consumer, chunk.nextCursor());
            }
            if (done.getCount() == 0 && chunk.drained()
                    && delivered.size() >= writers * perWriter) {
                break;
            }
            Thread.sleep(20);
        }
        pool.shutdown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(), writerFailures.stream().map(Throwable::toString).toList(),
                "writer threads must not fail");
        // the xmin barrier is CLUSTER-GLOBAL: a long transaction in ANY
        // database of the instance delays delivery (liveness, not loss).
        // On shortfall, name the pinner so the next occurrence is a diagnosis.
        assertEquals((long) writers * perWriter, delivered.size(),
                "every committed write must be delivered exactly once; open transactions: "
                        + activeTransactions());
    }

    private String activeTransactions() {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("""
                     SELECT datname, state, now() - xact_start AS age, left(query, 120)
                     FROM pg_stat_activity
                     WHERE backend_xid IS NOT NULL OR backend_xmin IS NOT NULL
                     ORDER BY xact_start""");
             var rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append("[db=").append(rs.getString(1))
                  .append(" state=").append(rs.getString(2))
                  .append(" age=").append(rs.getString(3))
                  .append(" q=").append(rs.getString(4)).append("] ");
            }
            return sb.isEmpty() ? "none" : sb.toString();
        } catch (Exception e) {
            return "unavailable: " + e.getMessage();
        }
    }

    /** REQ-DBO-FEED-KEYSET-CURSORS: pages advance strictly, no duplicates, stable under mid-pagination writes. */
    @Test
    void keysetPagesNeverDuplicateUnderConcurrentWrites() {
        for (int i = 0; i < 10; i++) {
            store.put(PutRequest.create("Gadget", gadget("F-4" + String.format("%02d", i), "pageco", "G" + i, 100 + i * 10)));
        }
        Criteria pageOf3 = Criteria.of("Gadget")
                .eq("vendor", EnvelopeValue.token("urn:vendor", "pageco"))
                .sortBy("weightGrams", ValueKind.NUMBER, true)
                .limit(3);

        List<String> collected = new ArrayList<>();
        FeedChunk<StoredObject> page = store.page(pageOf3, null);
        collected.addAll(page.items().stream().map(StoredObject::id).toList());

        // mid-pagination interference: a new lighter row (behind the cursor)
        // and an update to a row already passed
        store.put(PutRequest.create("Gadget", gadget("F-499", "pageco", "Lightest", 1)));
        StoredObject passed = page.items().get(0);
        store.put(PutRequest.update("Gadget", passed.id(),
                passed.versionId(), gadget("F-400", "pageco", "G0 renamed", 100)));

        while (!page.drained()) {
            page = store.page(pageOf3, page.nextCursor());
            collected.addAll(page.items().stream().map(StoredObject::id).toList());
        }

        assertEquals(collected.size(), Set.copyOf(collected).size(),
                "keyset pagination must never show a row twice");
        assertEquals(10, collected.size(),
                "rows present at pagination start must all appear (inserts behind the cursor do not)");
    }

    /** Default ordering (last_updated, id) pages without an explicit sort. */
    @Test
    void defaultOrderingPagesWithoutExplicitSort() {
        for (int i = 0; i < 5; i++) {
            store.put(PutRequest.create("Gadget", gadget("F-5" + i, "defco", "D" + i, i)));
        }
        Criteria criteria = Criteria.of("Gadget")
                .eq("vendor", EnvelopeValue.token("urn:vendor", "defco"))
                .limit(2);
        List<String> collected = new ArrayList<>();
        FeedChunk<StoredObject> page = store.page(criteria, null);
        while (true) {
            collected.addAll(page.items().stream().map(StoredObject::id).toList());
            if (page.drained()) break;
            page = store.page(criteria, page.nextCursor());
        }
        assertEquals(5, collected.size());
        assertEquals(5, Set.copyOf(collected).size());
        // continuing past the end yields an empty, drained chunk
        FeedChunk<StoredObject> beyond = store.page(criteria, page.nextCursor());
        assertTrue(beyond.items().isEmpty() && beyond.drained());
        assertNull(beyond.nextCursor());
    }

    // ------------------------------------------------------------- plumbing

    private List<FeedItem> drainAll() {
        List<FeedItem> all = new ArrayList<>();
        String cursor = null;
        while (true) {
            FeedChunk<FeedItem> chunk = feed.read(cursor, 500);
            all.addAll(chunk.items());
            if (chunk.drained()) {
                return all;
            }
            cursor = chunk.nextCursor();
        }
    }

    private String lastCursorOfFeed() {
        List<FeedItem> all = drainAll();
        return all.isEmpty() ? null : cursorAfter(all.get(all.size() - 1));
    }

    private String cursorAfter(FeedItem item) {
        // cursors are opaque to consumers; the harness derives one by reading
        // up to the item through the public api
        String cursor = null;
        while (true) {
            FeedChunk<FeedItem> chunk = feed.read(cursor, 1);
            if (chunk.items().isEmpty()) {
                throw new IllegalStateException("item not found in feed");
            }
            cursor = chunk.nextCursor();
            if (chunk.items().get(0).seq() == item.seq()) {
                return cursor;
            }
        }
    }

    private static List<String> itemKeys(FeedChunk<FeedItem> chunk) {
        return chunk.items().stream().map(i -> i.objectId() + "@" + i.versionId()).toList();
    }
}
