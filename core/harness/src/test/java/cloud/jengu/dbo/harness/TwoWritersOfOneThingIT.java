package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.core.api.feed.FeedSelection;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ContentDependency;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import cloud.jengu.dbo.terminology.Concept;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Two writers of one thing at the same moment, each where the race lives.
 *
 * <p>A tenant coming up drains its face chain itself while the reconciler's
 * round is reading every wired stream, and it can take one code system from
 * its face and from a zone. Each of those put two writers on one object, and
 * each lost one of them to a duplicate key: a definition's version 1 in the
 * history, a concept's row in the native form. The loser's whole unit rolled
 * back, and with it the bring-up that was waiting for it.
 *
 * <p>Each test holds the first writer at the point where the race was open
 * until the second has had its chance to get there too, so the interleaving
 * that failed is the one that runs — rather than one a fast machine never
 * produces. The hold gives up after a few seconds, because once the race is
 * closed the second writer is waiting for the first, and never arrives.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoWritersOfOneThingIT {

    private static final String TOKEN = "TwoWritersOfOneThingIT";

    /** Long enough for the second writer to reach the open race, if it can. */
    private static final long HOLD_SECONDS = 3;

    private PGSimpleDataSource sourceDs;
    private PGSimpleDataSource targetDs;

    @BeforeAll
    void up() {
        sourceDs = ds(SharedPostgres.urlFor(TOKEN + "_source"));
        targetDs = ds(SharedPostgres.urlFor(TOKEN + "_target"));
    }

    @Test
    @DisplayName("a stream read by two callers at once applies each change once")
    @Proving(DboPromises.SYNC_A_STREAM_APPLIES_EACH_CHANGE_ONCE)
    void aStreamReadTwiceAtOnceAppliesEachChangeOnce() throws Exception {
        List<FhirTypeConfig> types = List.of(FhirTypeConfig.canonical("ValueSet"));
        R4Personality sourceP = new R4Personality(types);
        R4Personality targetP = new R4Personality(types);
        R4Store source = new R4Store(new PgObjectStore(sourceDs, sourceP.registrations()), sourceP,
                "https://source.test");
        PgObjectStore target = new PgObjectStore(targetDs, targetP.registrations());
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String> published = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            published.add(source.putCanonical("""
                    {"resourceType":"ValueSet","status":"active",
                     "url":"https://source.test/vs/%s-%d","name":"Published%d"}"""
                    .formatted(run, i, i)).id());
        }
        String definitions = cloud.jengu.dbo.core.api.Domains.DEFINITIONS;
        // Both callers are held after reading and before applying, which is
        // where a bring-up's drain and a round met: one cursor, one chunk, read
        // by both before either acked it.
        CyclicBarrier bothRead = new CyclicBarrier(2);
        ChangeFeed held = new HeldAfterReading(new PgChangeFeed(sourceDs, definitions), bothRead);
        ContentSyncEngine stream = new ContentSyncEngine(
                new ContentDependency("source", Set.of("ValueSet")), held,
                target, targetDs, definitions, "4.0", List.of(), "sync.source." + run);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> drain = callers.submit(() -> stream.syncOnce(500));
            Future<Integer> round = callers.submit(() -> stream.syncOnce(500));
            int seen = drain.get(60, TimeUnit.SECONDS) + round.get(60, TimeUnit.SECONDS);
            assertEquals(published.size(), seen,
                    "the published changes were read more than once between the two callers");
        } finally {
            callers.shutdownNow();
        }
        for (String id : published) {
            assertEquals(1, target.get("ValueSet", id).orElseThrow().versionId(),
                    "a change was applied twice: the copy has a version for each reader");
        }
    }

    @Test
    @DisplayName("an object created by two writers at once is created once and then changed")
    @Proving(DboPromises.CORE_TWO_WRITERS_OF_ONE_OBJECT_TAKE_TURNS)
    void twoWritersCreatingOneObjectBothLand() throws Exception {
        CyclicBarrier bothDeciding = new CyclicBarrier(2);
        PgObjectStore store = new PgObjectStore(targetDs, heldWhileDeciding(bothDeciding));
        String id = UUID.randomUUID().toString();

        List<Long> versions = twoAtOnce(
                () -> store.put(new PutRequest("Encounter", id, null, encounter("first")))
                        .versionId(),
                () -> store.put(new PutRequest("Encounter", id, null, encounter("second")))
                        .versionId());

        assertEquals(List.of(1L, 2L), versions.stream().sorted().toList(),
                "the two writes were not one creation and one change");
        assertEquals(2, store.get("Encounter", id).orElseThrow().versionId());
    }

    @Test
    @DisplayName("objects created by two units at once are created once and then changed")
    @Proving(DboPromises.CORE_TWO_WRITERS_OF_ONE_OBJECT_TAKE_TURNS)
    void twoUnitsCreatingTheSameObjectsBothLand() throws Exception {
        CyclicBarrier bothDeciding = new CyclicBarrier(2);
        PgObjectStore store = new PgObjectStore(targetDs, heldWhileDeciding(bothDeciding));
        String one = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();

        // The shape a stream's chunk is applied in: a set, as one unit.
        twoAtOnce(
                () -> store.transact(List.of(
                        new PutRequest("Encounter", one, null, encounter("a")),
                        new PutRequest("Encounter", other, null, encounter("a")))).size(),
                () -> store.transact(List.of(
                        new PutRequest("Encounter", one, null, encounter("b")),
                        new PutRequest("Encounter", other, null, encounter("b")))).size());

        assertEquals(2, store.get("Encounter", one).orElseThrow().versionId());
        assertEquals(2, store.get("Encounter", other).orElseThrow().versionId());
    }

    @Test
    @DisplayName("a code system imported twice at once ends on the import that finished last")
    @Proving(DboPromises.CORE_TWO_WRITERS_OF_ONE_OBJECT_TAKE_TURNS)
    void twoImportsOfOneCodeSystemBothLand() throws Exception {
        TerminologyStore terms = new TerminologyStore(targetDs);
        String system = "https://terms.dbo.test/" + UUID.randomUUID();
        // The first import is held with its old concepts deleted and none of
        // its own written yet, which is where the second one used to slip in,
        // write the same codes and commit — leaving the first to meet them.
        CountDownLatch firstIsCopying = new CountDownLatch(1);
        CountDownLatch letTheFirstGoOn = new CountDownLatch(1);
        Iterator<Concept> held = new Iterator<>() {
            private final Iterator<Concept> concepts = concepts("first").iterator();
            private boolean waited;

            @Override
            public boolean hasNext() {
                return concepts.hasNext();
            }

            @Override
            public Concept next() {
                if (!waited) {
                    waited = true;
                    firstIsCopying.countDown();
                    try {
                        letTheFirstGoOn.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                }
                return concepts.next();
            }
        };
        ExecutorService importers = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = importers.submit(() -> terms.importSystem(system, "1", held));
            firstIsCopying.await(30, TimeUnit.SECONDS);
            Future<Long> second = importers.submit(() ->
                    terms.importSystem(system, "2", concepts("second").iterator()));
            boolean secondFinishedFirst;
            try {
                second.get(HOLD_SECONDS, TimeUnit.SECONDS);
                secondFinishedFirst = true;
            } catch (TimeoutException waitingForTheFirst) {
                secondFinishedFirst = false;
            }
            letTheFirstGoOn.countDown();
            assertEquals(3L, first.get(60, TimeUnit.SECONDS));
            assertEquals(3L, second.get(60, TimeUnit.SECONDS));
            assertFalse(secondFinishedFirst,
                    "the second import replaced the system while the first was still writing it");
        } finally {
            letTheFirstGoOn.countDown();
            importers.shutdownNow();
        }
        assertEquals(3, terms.conceptCount(system));
        assertEquals("second", terms.lookup(system, "a").orElseThrow().display(),
                "the import that finished last is not the one that stands");
        assertEquals("2", terms.systemVersion(system).orElseThrow());
    }

    // ----------------------------------------------------------------- shapes

    /** A feed that holds each reader after its read until the other has read too, or gives up. */
    private static final class HeldAfterReading implements ChangeFeed {
        private final ChangeFeed feed;
        private final CyclicBarrier bothRead;

        HeldAfterReading(ChangeFeed feed, CyclicBarrier bothRead) {
            this.feed = feed;
            this.bothRead = bothRead;
        }

        private FeedChunk<FeedItem> held(FeedChunk<FeedItem> chunk) {
            try {
                bothRead.await(HOLD_SECONDS, TimeUnit.SECONDS);
            } catch (Exception theOtherIsNotComing) {
                // Broken or timed out: the other reader is waiting for this
                // one, which is the race closed.
            }
            return chunk;
        }

        @Override
        public FeedChunk<FeedItem> read(String cursor, int limit) {
            return feed.read(cursor, limit);
        }

        @Override
        public FeedChunk<FeedItem> readFor(String consumer, int limit) {
            return held(feed.readFor(consumer, limit));
        }

        @Override
        public FeedChunk<FeedItem> readFor(String consumer, int limit, FeedSelection wanted) {
            return held(feed.readFor(consumer, limit, wanted));
        }

        @Override
        public void ack(String consumer, String cursor) {
            feed.ack(consumer, cursor);
        }

        @Override
        public void resetConsumer(String consumer, String cursor) {
            feed.resetConsumer(consumer, cursor);
        }

        @Override
        public String cursorOf(String consumer) {
            return feed.cursorOf(consumer);
        }

        @Override
        public long lag(String consumer) {
            return feed.lag(consumer);
        }
    }

    /**
     * The face's registrations, with the envelope taken only once both writers
     * are deciding — which is after each has looked for the object's current
     * row and before either has written one.
     */
    private static List<TypeRegistration> heldWhileDeciding(CyclicBarrier bothDeciding) {
        R4Personality face = new R4Personality(List.of(FhirTypeConfig.internal("Encounter")));
        return face.registrations().stream()
                .map(type -> new TypeRegistration(type.typeName(), type.domain(),
                        type.identityClass(), type.identitySystems(), type.handling(),
                        (name, payload) -> {
                            try {
                                bothDeciding.await(HOLD_SECONDS, TimeUnit.SECONDS);
                            } catch (Exception theOtherIsNotComing) {
                                // as above: the other writer waits on this one
                            }
                            return type.extractor().extract(name, payload);
                        }, type.indexes(), type.payloadVersion()))
                .toList();
    }

    @SafeVarargs
    private static <T> List<T> twoAtOnce(java.util.concurrent.Callable<T>... writers)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(writers.length);
        try {
            List<Future<T>> running = new ArrayList<>();
            for (java.util.concurrent.Callable<T> writer : writers) {
                running.add(pool.submit(writer));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> one : running) {
                out.add(one.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] encounter(String marker) {
        return """
                {"resourceType":"Encounter","status":"finished",
                 "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"},
                 "serviceType":{"text":"%s"}}""".formatted(marker)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<Concept> concepts(String display) {
        return List.of(Concept.of("a", display), Concept.of("b", display),
                Concept.of("c", display));
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(SharedPostgres.username());
        ds.setPassword(SharedPostgres.password());
        return ds;
    }
}
