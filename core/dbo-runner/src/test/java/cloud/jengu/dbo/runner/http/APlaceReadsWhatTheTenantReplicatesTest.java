package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.core.api.feed.FeedSelection;
import cloud.jengu.dbo.core.face.GrainCodec;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.transport.Access;
import cloud.jengu.dbo.runner.transport.LaneVerbService;
import cloud.jengu.dbo.runner.transport.Place;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second place of a tenant reads the tenant's feeds across a real HTTP door,
 * as the engine that keeps it up to date would read a feed beside it.
 *
 * <p>The tenant here is two feeds held in memory, with a position per
 * consumer; what is asserted is what crosses, and under whose position.
 */
class APlaceReadsWhatTheTenantReplicatesTest {

    @Test
    @DisplayName("a place reads the definitions and the types the tenant takes from upstream, "
            + "never a type the tenant authors, and a type stored in parts arrives whole")
    @Proving(DboPromises.SYNC_A_PLACE_READS_ONLY_WHAT_THE_TENANT_DID_NOT_AUTHOR)
    void aPlaceReadsOnlyWhatIsReplicated() throws Exception {
        Tenant tenant = new Tenant();
        WireLane site = tenant.laneFor("site-a");

        List<FeedItem> records = site.placeFeed(Place.RECORDS).readFor("ignored", 50,
                FeedSelection.ofTypes(Set.of("Organization", "Patient"))).items();
        List<FeedItem> definitions = site.placeFeed(Place.DEFINITIONS)
                .readFor("ignored", 50, FeedSelection.EVERYTHING).items();

        assertEquals(List.of("Organization"), records.stream().map(FeedItem::typeName).toList(),
                "a place was handed a type the tenant authors");
        assertEquals(List.of("StructureDefinition", "CodeSystem"),
                definitions.stream().map(FeedItem::typeName).toList());
        assertEquals("whole", new String(definitions.get(1).payload(), StandardCharsets.UTF_8),
                "a type the tenant stores in parts crossed in parts");
    }

    @Test
    @DisplayName("a credential that holds no place reads nothing, and is told so")
    @Proving(DboPromises.SYNC_A_PLACE_READS_ONLY_WHAT_THE_TENANT_DID_NOT_AUTHOR)
    void noPlaceReadsNothing() throws Exception {
        Tenant tenant = new Tenant();
        ChangeFeed feed = tenant.laneFor("worker").placeFeed(Place.DEFINITIONS);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> feed.readFor("ignored", 50, FeedSelection.EVERYTHING));

        assertTrue(refused.getMessage().contains("holds no place"), refused.getMessage());
        assertEquals(Map.of(), tenant.positions, "a position moved for a credential refused");
    }

    @Test
    @DisplayName("a place reads on from where it acknowledged, under a position of its own that "
            + "it cannot rewind")
    @Proving(DboPromises.SYNC_A_PLACE_READS_ON_FROM_WHERE_IT_ACKNOWLEDGED)
    void aPlaceReadsOnFromWhereItAcknowledged() throws Exception {
        Tenant tenant = new Tenant();
        ChangeFeed siteA = tenant.laneFor("site-a").placeFeed(Place.DEFINITIONS);
        ChangeFeed siteB = tenant.laneFor("site-b").placeFeed(Place.DEFINITIONS);

        FeedChunk<FeedItem> first = siteA.readFor("ignored", 1, FeedSelection.EVERYTHING);
        siteA.ack("ignored", first.nextCursor());
        FeedChunk<FeedItem> second = siteA.readFor("ignored", 1, FeedSelection.EVERYTHING);
        FeedChunk<FeedItem> another = siteB.readFor("ignored", 1, FeedSelection.EVERYTHING);

        assertEquals("StructureDefinition", first.items().get(0).typeName());
        assertEquals("CodeSystem", second.items().get(0).typeName(),
                "a place read again from where it had already acknowledged");
        assertEquals("StructureDefinition", another.items().get(0).typeName(),
                "two places shared one position");
        assertEquals(Set.of("place.site-a.definitions"), tenant.positions.keySet(),
                "the position was not kept under the place's own name");
        assertThrows(UnsupportedOperationException.class,
                () -> siteA.resetConsumer("ignored", null));
        assertThrows(UnsupportedOperationException.class, () -> siteA.read(null, 1));
    }

    /**
     * A tenant with a records feed, a definitions feed and a grain, behind a
     * lane door whose tokens are the participants' names. {@code site-a} and
     * {@code site-b} hold a place; {@code worker} only works.
     */
    private static final class Tenant {

        /** Each consumer's acknowledged position: how many items it has applied. */
        final Map<String, Integer> positions = new ConcurrentHashMap<>();

        private final ChangeFeed records = new Feed(List.of(
                item(1, "Organization", "from the zone"),
                item(2, "Patient", "authored here")), positions);

        private final ChangeFeed definitions = new Feed(List.of(
                item(1, "StructureDefinition", "a profile"),
                item(2, "CodeSystem", "stored in parts")), positions);

        private final GrainCodec grain = new GrainCodec() {
            @Override
            public boolean handles(String typeName) {
                return "CodeSystem".equals(typeName);
            }

            @Override
            public byte[] forTransport(String typeName, byte[] storedPayload) {
                return "whole".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] storedFormOf(String typeName, byte[] transportedPayload) {
                return transportedPayload;
            }

            @Override
            public void keep(String typeName, byte[] transportedPayload) {
            }
        };

        WireLane laneFor(String participant) throws Exception {
            LaneVerbService verbs = new LaneVerbService(
                    authorization -> {
                        String who = authorization.substring("Bearer ".length());
                        return new Access.Grant(who, Lane.Entitlement.ofSteps(), false,
                                who.startsWith("site-"));
                    },
                    null,
                    (asking, identity, entitlement) -> {
                        throw new AssertionError("a place's verb reached a lane");
                    },
                    () -> Optional.of(new Place(records, definitions, Set.of("Organization"),
                            grain)));
            com.sun.net.httpserver.HttpServer server =
                    com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/work", new LaneHandler("/work", verbs));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return HttpLane.to(URI.create("http://localhost:" + server.getAddress().getPort()
                            + "/work"), () -> participant, "clinic", participant,
                    new Executor(participant, "1", "example.site", Scope.BASELINE));
        }
    }

    /** A feed of fixed items, its cursor the count of items read past. */
    private record Feed(List<FeedItem> items, Map<String, Integer> positions)
            implements ChangeFeed {

        @Override
        public FeedChunk<FeedItem> readFor(String consumer, int limit, FeedSelection wanted) {
            int from = positions.getOrDefault(consumer, 0);
            List<FeedItem> chunk = new ArrayList<>();
            int at = from;
            while (at < items.size() && chunk.size() < limit) {
                FeedItem item = items.get(at++);
                if (wanted.types().isEmpty() || wanted.types().contains(item.typeName())) {
                    chunk.add(item);
                }
            }
            return new FeedChunk<>(chunk, String.valueOf(at), at >= items.size());
        }

        @Override
        public FeedChunk<FeedItem> readFor(String consumer, int limit) {
            return readFor(consumer, limit, FeedSelection.EVERYTHING);
        }

        @Override
        public void ack(String consumer, String cursor) {
            positions.merge(consumer, Integer.parseInt(cursor), Math::max);
        }

        @Override
        public FeedChunk<FeedItem> read(String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetConsumer(String consumer, String cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String cursorOf(String consumer) {
            return String.valueOf(positions.getOrDefault(consumer, 0));
        }

        @Override
        public long lag(String consumer) {
            return items.size() - positions.getOrDefault(consumer, 0);
        }
    }

    private static FeedItem item(long seq, String type, String payload) {
        return new FeedItem(seq, type + "-" + seq, type, 1, ChangeKind.CREATED,
                Instant.parse("2026-10-10T00:00:00Z"), payload.getBytes(StandardCharsets.UTF_8),
                false, "r5");
    }
}
