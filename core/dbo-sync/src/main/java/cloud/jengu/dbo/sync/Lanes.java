package cloud.jengu.dbo.sync;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Moving work and the data it names between two sites of one tenant
 *.
 *
 * <p><b>There is no channel here, and that is the design.</b> This hands a
 * caller a batch and takes one back; a connector outside dbo carries the bytes,
 * authenticates, reconnects and frames. The store never opens a connection
 * outwards, and the first channel-aware method here would be the one that ends
 * with a transport inside the engine.
 *
 * <p><b>Two sites, one tenant.</b> Same code, same declarations, so a lane
 * is same-version replication: no converter chain, and the stored bytes travel
 * as they are.
 *
 * <p>What this owns is everything store-level: what the far side does not have
 * yet, an apply that is idempotent under replay <b>and safe under reorder</b>,
 * the epoch that makes a resumed-from-backup cursor detectable, and the marker
 * each side keeps about where the other said it had reached.
 *
 * <p><b>A connector cannot read "caught up" from this lane's cursor, and an
 * empty batch does not mean it either.</b> {@code Lane} and {@code Placement}
 * are records in the work domain, and the work domain's feed is what
 * {@link #outbound} reads — so every {@link #sent} writes an event into this
 * lane's own input and the cursor never stops moving. The filter skips that
 * bookkeeping, which is why empty batches also appear while the lane is
 * genuinely still draining. The only honest measure is <em>nothing arrived for
 * a few rounds</em>.
 *
 * <p>Said here rather than only in a task document because it is the first
 * thing a second connector would get wrong, and the API gives no other hint of
 * it. Note it is the mirror of the participant's trap: there a caught-up
 * cursor looks stalled, here a caught-up lane looks busy.
 */
public final class Lanes {

    /**
     * Where the far side's copy of a run is kept: under the site that
     * authored it. The convention itself is the run's ({@link
     * WorkModel#AUTHOR_SEPARATOR}), because the rules built on it are.
     */
    public static final String MIRROR_SEPARATOR = WorkModel.AUTHOR_SEPARATOR;

    /** The audit type, which travels but is never written like other content. */
    private static final String AUDIT = "AuditEntry";
    /** What content kept whole travels as: not a record, and applied as none. */
    public static final String CONTENT = "dbo.content";
    /** A tenant's content, as a record that points at it names it. */
    private static final java.util.regex.Pattern POINTED_AT = java.util.regex.Pattern.compile(
            "/blob/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /** The run a copy is filed under in its placement: none, so revocation never takes it. */
    static final String COPY = "copy:";

    /**
     * Whose position on this tenant's work feed is how far a place has taken
     * the work it performs: a lane of its own beside the one the place hands
     * home on, whose epoch is the place's.
     */
    static final String PERFORMED_AT = "performed-at:";
    /** A run as it travels to the place that performs it: its address left here. */
    private static final java.util.regex.Pattern ADDRESSED_TO_A_PLACE =
            java.util.regex.Pattern.compile(",\"for\":\\{\"place\":\"(?:[^\"\\\\]|\\\\.)*\"\\}");

    private final ObjectStore store;
    private final ChangeFeed workFeed;
    private final ChangeFeed contentFeed;
    private final Set<String> admitted;
    private final Runs runs;
    private final String site;
    private final cloud.jengu.dbo.core.api.AuditReplay auditReplay;

    /**
     * @param site this site's own name. It is not the tenant's — the
     *             tenant is the same on both sides — and it is what a
     *             mirrored record is filed under.
     */
    public Lanes(ObjectStore store, ChangeFeed workFeed, Runs runs, String site) {
        this(store, workFeed, runs, site, null);
    }

    /**
     * A lane that replicates the trail as well as the work.
     *
     * <p>Audit is the one thing here that cannot be written the way everything
     * else is: direct writes to the type are refused for every caller, and
     * rightly. The port is that refusal's one admission.
     *
     * <p><b>Wiring it is the declaration that this lane carries the trail</b>,
     * and it governs both directions — this lane offers its entries outbound
     * and admits the peer's inbound. That is one switch rather than two
     * because they are one arrangement: a pair where one side sent a trail the
     * other would not admit is a lane that refuses half of what it is given,
     * which is worse than a pair that agreed to carry none. A lane built
     * without it neither offers nor admits, and says so loudly if an entry
     * arrives anyway — a store with no audit type is a legitimate deployment,
     * and a trail that silently did not replicate is not.
     *
     * @param auditReplay the admitted path, or null on a lane that carries no
     *                    trail
     */
    public Lanes(ObjectStore store, ChangeFeed workFeed, Runs runs, String site,
            cloud.jengu.dbo.core.api.AuditReplay auditReplay) {
        this(store, workFeed, runs, site, auditReplay, null, Set.of());
    }

    /**
     * The same, with the lane's second bound: a content feed to carry
     * declarations from by type, and the types this lane admits by type at
     * all. What is not admitted does not travel and is refused by name when
     * asked for — a consumer's own promise that a site holds no register of
     * people rests on that refusal.
     */
    /**
     * As above, for a tenant whose changes are on two feeds.
     *
     * <p>What a tenant declares is a set of types, and since its definitions
     * became a domain of their own the split runs through that set — a value
     * set on one feed and an observation on the other. The bound is over what
     * was declared, so it has to be over both.
     */
    public Lanes(ObjectStore store, ChangeFeed workFeed, Runs runs, String site,
            cloud.jengu.dbo.core.api.AuditReplay auditReplay, ChangeFeed recordFeed,
            ChangeFeed definitionsFeed, Set<String> admitted) {
        this(store, workFeed, runs, site, auditReplay,
                new BothFeeds(recordFeed, definitionsFeed), admitted);
    }

    public Lanes(ObjectStore store, ChangeFeed workFeed, Runs runs, String site,
            cloud.jengu.dbo.core.api.AuditReplay auditReplay, ChangeFeed contentFeed,
            Set<String> admitted) {
        this.store = store;
        this.workFeed = workFeed;
        this.contentFeed = contentFeed;
        this.admitted = Set.copyOf(admitted);
        this.runs = runs;
        this.site = site;
        this.auditReplay = auditReplay;
    }

    /**
     * One lane's state: who it is with, under which epoch, and where both sides are.
     *
     * @param covers the processes its position on the work feed has been read
     *               for, or null for a lane that has never said
     * @param askedHeld how far this side has asked the peer which of its records
     *               from before it marked the peer holds: null before it began,
     *               the id of the last record asked of, or {@link #ASKED_OF_ALL}
     * @param handedAgain how far this side has handed the peer again what the
     *               peer declined before it knew an internal record by its id
     *               alone, in the same terms
     */
    public record Lane(String key, String peer, String epoch, String ourCursor,
            String theirMarker, String contentCursor, List<String> covers, String askedHeld,
            String handedAgain) {

        /** A lane that has not handed again what its peer declined. */
        public Lane(String key, String peer, String epoch, String ourCursor, String theirMarker,
                String contentCursor, List<String> covers, String askedHeld) {
            this(key, peer, epoch, ourCursor, theirMarker, contentCursor, covers, askedHeld,
                    null);
        }

        /** A lane that has not asked what its peer holds from before it marked. */
        public Lane(String key, String peer, String epoch, String ourCursor, String theirMarker,
                String contentCursor, List<String> covers) {
            this(key, peer, epoch, ourCursor, theirMarker, contentCursor, covers, null, null);
        }

        /** What {@code askedHeld} says once every record from before has been asked of. */
        public static final String ASKED_OF_ALL = "all";

        /** A lane before the second bound: no position on the content feed. */
        public Lane(String key, String peer, String epoch, String ourCursor, String theirMarker) {
            this(key, peer, epoch, ourCursor, theirMarker, null);
        }

        /** A lane that has not said which processes its position was read for. */
        public Lane(String key, String peer, String epoch, String ourCursor, String theirMarker,
                String contentCursor) {
            this(key, peer, epoch, ourCursor, theirMarker, contentCursor, null);
        }
    }

    /**
     * One object as it stands here, for a caller that is about to carry it
     * somewhere.
     *
     * @param recordedAt when the <b>source</b> recorded this version, carried
     *                   so the far side can replay it rather than restamp it.
     *                   A version's timestamp is evidence about when somebody
     *                   knew something, and a site that wrote the
     *                   arrival time would be saying it learned it when
     *                   the cloud heard about it — which is false for ordinary
     *                   content and, for an audit entry, is the whole of what
     *                   the entry was for.
     * @param work whether this is a run rather than the data a run named. The
     *             far side applies data first, so nothing arrives pointing at
     *             something absent.
     */
    public record Item(String typeName, String id, long version, Instant recordedAt,
            byte[] payload, boolean work, List<String> forRuns, boolean copy) {

        /** An item of the by-work bound, or a run: what every batch carried before the second bound. */
        public Item(String typeName, String id, long version, Instant recordedAt, byte[] payload,
                boolean work, List<String> forRuns) {
            this(typeName, id, version, recordedAt, payload, work, forRuns, false);
        }

        /** A run travelling on its own account. */
        public static Item work(String id, long version, Instant recordedAt, byte[] payload) {
            return new Item(WorkModel.TYPE, id, version, recordedAt, payload, true, List.of());
        }

        /**
         * A copy: a declaration travelling by type, or a version a run
         * produced. Filed under its source, read-only there, shadowed by a
         * local override, and never revoked by work — a declaration is not
         * work-bound, and what a run produced outlives the run.
         */
        public static Item copy(String typeName, String id, long version, Instant recordedAt,
                byte[] payload) {
            return new Item(typeName, id, version, recordedAt, payload, false, List.of(), true);
        }

        /**
         * Content kept whole, travelling with the run it goes home with: its
         * media type, kind and bytes, under the key the place gave it.
         */
        public static Item content(String key, Instant put, byte[] payload, String run) {
            return new Item(CONTENT, key, 1, put, payload, false, List.of(run), false);
        }
    }

    /**
     * What to carry, and where it ends.
     *
     * <p>The cursor is the resume token: the sender does not advance until the
     * far side has said it applied, so a batch lost on the wire is re-sent
     * rather than skipped.
     */
    public record Batch(String epoch, String from, String cursor, List<Item> items,
            String contentCursor) {

        /** A batch of the by-work bound alone. */
        public Batch(String epoch, String from, String cursor, List<Item> items) {
            this(epoch, from, cursor, items, null);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /** What an apply did, for the connector that has to report it. */
    /**
     * @param held the copies of the far side's records this side now holds,
     *             each {@code type/id/version}: written now or held already at
     *             that version or newer, and never one kept on the far side
     *             because this side holds a record of its own in its place
     * @param kept the copies this side did not take because it holds a record
     *             of its own in their place, each {@code type/id/version} and
     *             the record held instead, as {@code type/id/version Type/id}
     */
    public record Applied(long applied, long skipped, List<String> refused, List<String> held,
            List<String> kept) {

        public Applied {
            refused = refused == null ? List.of() : List.copyOf(refused);
            held = held == null ? List.of() : List.copyOf(held);
            kept = kept == null ? List.of() : List.copyOf(kept);
        }

        /** Compatibility with callers that predate {@code held}. */
        public Applied(long applied, long skipped, List<String> refused) {
            this(applied, skipped, refused, List.of());
        }

        /** Compatibility with callers that predate {@code kept}. */
        public Applied(long applied, long skipped, List<String> refused, List<String> held) {
            this(applied, skipped, refused, held, List.of());
        }
    }

    /** What a revocation pass removed, and what it kept because work still needs it. */
    public record Revoked(long removed, long kept) {}

    // ------------------------------------------------------------------ lane

    /**
     * The lane with this peer, minting an epoch if this is the first time.
     *
     * <p>A cursor is meaningless outside the lane instance that issued it: a
     * site restored from a backup resumes a position that no longer means
     * anything, and it looks perfectly healthy doing it. The epoch is what makes
     * that detectable — the same reason a tenant archive refuses to carry
     * delivery cursors at all.
     */
    public Lane open(String peer) {
        return read(peer).orElseGet(() -> write(new Lane(peer, peer, UuidV7.newId(), null, null)));
    }

    /** What this site has told the far side, and what it heard back. */
    public Optional<Lane> lane(String peer) {
        return read(peer);
    }

    /**
     * Where each lane has read the work feed to, and when it last moved.
     *
     * <p>A lane reads that feed anonymously, from a cursor it keeps on its own
     * record, so the feed's consumers do not include it: whatever lets go of
     * the feed asks here as well.
     */
    public List<Reading> readings() {
        List<Reading> readings = new ArrayList<>();
        try (java.util.stream.Stream<StoredObject> lanes = cloud.jengu.dbo.core.api.Answered
                .pagedBy(store::page, Criteria.of(LaneModel.TYPE))) {
            lanes.forEach(stored -> {
                Object json = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
                readings.add(new Reading(Json.str(json, "peer"), optional(json, "ourCursor"),
                        stored.lastUpdated()));
            });
        }
        return readings;
    }

    /**
     * One lane's place in the work feed.
     *
     * @param cursor null for a lane that has sent nothing yet
     */
    public record Reading(String peer, String cursor, Instant since) {
    }

    /**
     * Records where the far side said it had reached.
     *
     * <p>Echoed rather than inferred: "where is the far side" should be a store
     * fact, not the channel's opinion about its own delivery.
     */
    public Lane mark(String peer, String theirMarker) {
        Lane lane = open(peer);
        return write(new Lane(lane.key(), lane.peer(), lane.epoch(), lane.ourCursor(),
                theirMarker, lane.contentCursor(), lane.covers(), lane.askedHeld(),
                lane.handedAgain()));
    }

    /**
     * Records the processes this lane's position is read for from here on.
     *
     * <p>Kept with the position because the two are one fact: a run the
     * position moved past while its process was not among these was never
     * looked at, and only the lane can say so after a restart.
     */
    public Lane covering(String peer, Set<String> processes) {
        Lane lane = open(peer);
        return write(new Lane(lane.key(), lane.peer(), lane.epoch(), lane.ourCursor(),
                lane.theirMarker(), lane.contentCursor(),
                List.copyOf(new java.util.TreeSet<>(processes)), lane.askedHeld(),
                lane.handedAgain()));
    }

    /**
     * Records how far this side has asked the peer what it holds of the
     * records from before it marked: kept with the lane, so a restart goes on
     * from there and a lane that has asked of all of them never asks again.
     *
     * @param through the id of the last record asked of, or {@link Lane#ASKED_OF_ALL}
     */
    public Lane askedHeld(String peer, String through) {
        Lane lane = open(peer);
        return write(new Lane(lane.key(), lane.peer(), lane.epoch(), lane.ourCursor(),
                lane.theirMarker(), lane.contentCursor(), lane.covers(), through,
                lane.handedAgain()));
    }

    /**
     * Records how far this side has handed the peer again what the peer
     * declined as its own, kept with the lane as {@link #askedHeld} is.
     *
     * @param through the id of the last record handed again, or {@link Lane#ASKED_OF_ALL}
     */
    public Lane handedAgain(String peer, String through) {
        Lane lane = open(peer);
        return write(new Lane(lane.key(), lane.peer(), lane.epoch(), lane.ourCursor(),
                lane.theirMarker(), lane.contentCursor(), lane.covers(), lane.askedHeld(),
                through));
    }

    // -------------------------------------------------------------- outbound

    /**
     * The work addressed to a place, for it to perform: the runs since where
     * the place last took, each with what its slots and items name first, so
     * nothing arrives at the place pointing at a record it let go of.
     *
     * <p>A run goes without its address, which is this side's account of who
     * performs it: at the place its runners take it as any of its own. What
     * a run names goes while the run is open, and the place keeps it for as
     * long as the run is.
     *
     * @param through the position the place took the last batch through, which
     *                is where it now stands; null to read again from where it last
     *                said it stood
     */
    public Batch toBePerformedAt(String place, String through, int limit) {
        Lane lane = open(PERFORMED_AT + place);
        if (through != null && !through.equals(lane.ourCursor())) {
            lane = write(new Lane(lane.key(), lane.peer(), lane.epoch(), through,
                    lane.theirMarker(), lane.contentCursor(), lane.covers(), lane.askedHeld(),
                    lane.handedAgain()));
        }
        FeedChunk<FeedItem> chunk = workFeed.read(lane.ourCursor(), limit);
        Map<String, Item> work = new LinkedHashMap<>();
        Map<String, List<String>> forRuns = new LinkedHashMap<>();
        for (FeedItem event : chunk.items()) {
            if (!WorkModel.TYPE.equals(event.typeName()) || work.containsKey(event.objectId())) {
                continue;
            }
            Optional<StoredObject> stored = store.get(WorkModel.TYPE, event.objectId());
            if (stored.isEmpty() || stored.get().deleted()) {
                continue;
            }
            Run run = Run.of(stored.get());
            if (run.addressee() == null || !place.equals(run.addressee().place())) {
                continue;
            }
            work.put(stored.get().id(), Item.work(stored.get().id(), stored.get().versionId(),
                    stored.get().lastUpdated(), ADDRESSED_TO_A_PLACE.matcher(
                            new String(stored.get().payload(), StandardCharsets.UTF_8))
                            .replaceFirst("").getBytes(StandardCharsets.UTF_8)));
            if (run.open()) {
                for (String reference : performedOver(run)) {
                    forRuns.computeIfAbsent(reference, key -> new ArrayList<>()).add(run.key());
                }
            }
        }
        List<Item> items = new ArrayList<>();
        forRuns.forEach((reference, named) -> resolve(reference, named).ifPresent(items::add));
        items.addAll(work.values());
        String cursor = chunk.nextCursor() != null ? chunk.nextCursor() : lane.ourCursor();
        return new Batch(lane.epoch(), site, cursor, List.copyOf(items));
    }

    /** What a run is performed over: its item, its parts' items, and every record its slots refer to. */
    private List<String> performedOver(Run run) {
        List<String> over = new ArrayList<>(named(run));
        run.inputs().values().forEach(filled -> {
            if (filled.referred()) {
                filled.values().forEach(over::add);
            }
        });
        return over.stream().distinct().toList();
    }

    /**
     * The records named, as they stand here, as copies for the peer: what a
     * place hands its tenant again. The lane's positions do not move, so
     * nothing else is handed with them and nothing is skipped for them.
     *
     * @param references each {@code type/id}; one this side no longer holds is left out
     */
    public Batch again(String peer, List<String> references) {
        Lane lane = open(peer);
        List<Item> items = new ArrayList<>();
        for (String reference : references) {
            heldHere(reference).filter(stored -> !stored.deleted()).ifPresent(stored ->
                    items.add(Item.copy(stored.typeName(), stored.id(), stored.versionId(),
                            stored.lastUpdated(), stored.payload())));
        }
        if (this.references != null) {
            Set<String> handed = new java.util.HashSet<>();
            items.forEach(item -> handed.add(item.typeName() + "/" + item.id()));
            items.replaceAll(item -> Item.copy(item.typeName(), item.id(), item.version(),
                    item.recordedAt(), askedAsWhatTheyAre(item.payload(), handed)));
        }
        return new Batch(lane.epoch(), site, lane.ourCursor(), List.copyOf(items),
                lane.contentCursor());
    }

    /**
     * What this peer does not have yet: the runs since its cursor, and the data
     * those runs name.
     *
     * <p><b>Data before work</b>, so the far side never applies a run pointing
     * at something it has not got. <b>Bounded by what an item names</b> and
     * never by following references as far as they go — Patient → Encounter →
     * Observation → everything is how a site ends up holding a register.
     */
    public Batch outbound(String peer, int limit, Set<String> processes) {
        return outbound(peer, limit, processes, Set.of());
    }

    /**
     * The lane's two bounds in one batch: the runs of the processes asked
     * for, with what they name and what they produced; and every version of
     * the types asked for since the peer's position on the content feed.
     *
     * <p>Declarations by type — terminology, canonicals, the tenant's own
     * definitions, none of it about anybody — and patient data by work are
     * deliberately different bounds. A type this lane does not admit is
     * refused by name rather than quietly left out.
     */
    public Batch outbound(String peer, int limit, Set<String> processes, Set<String> types) {
        return outbound(peer, limit, processes, types, true, process -> { }, () -> { });
    }

    /**
     * What a place sends home: the runs it performed, what they produced and
     * their trail — never the records their inputs named. Those came from
     * home, and handed back they would arrive as working copies of the
     * tenant's own records, to be removed when the work closed.
     */
    public Batch outboundHome(String peer, int limit, Set<String> processes) {
        return outboundHome(peer, limit, processes, process -> { }, () -> { });
    }

    /**
     * What a place sends home, saying the process of each run it read past
     * because its process was not asked for, which is work that stays here,
     * and whether the read reached the end of the feed. A read that comes
     * back empty of work is no end: this lane's own position is kept on the
     * feed it reads, so marking a batch sent puts one more event on it.
     */
    public Batch outboundHome(String peer, int limit, Set<String> processes,
            java.util.function.Consumer<String> passedOver, Runnable atTheEnd) {
        return outbound(peer, limit, processes, Set.of(), false, passedOver, atTheEnd);
    }

    /**
     * Notes, as a copy is applied, which peer it came from — so a reader of
     * the record can be told by its source which place made it.
     */
    public Lanes recordingWhence(java.util.function.BiConsumer<Item, String> whence) {
        this.whence = whence;
        return this;
    }

    private volatile java.util.function.BiConsumer<Item, String> whence;

    /**
     * How records crossing between this store and another name the records
     * they point at: by what those say they are, never by an id one store
     * minted. Without it a record crosses as it was written, ids and all.
     */
    public Lanes namingAcrossStores(cloud.jengu.dbo.core.face.ReferenceResolution references) {
        this.references = references;
        return this;
    }

    private volatile cloud.jengu.dbo.core.face.ReferenceResolution references;

    /**
     * Where the tenant's content kept whole is: what a place hands home with
     * its runs, and where the tenant keeps what a place handed. Without it a
     * run goes home without its content and content arriving is refused.
     */
    public Lanes carryingContent(cloud.jengu.dbo.core.api.BlobStore content) {
        this.content = content;
        return this;
    }

    private volatile cloud.jengu.dbo.core.api.BlobStore content;

    /** Who is told of each record a place handed home that this side did not take. */
    public Lanes sayingWhatItDidNotTake(Untaken untaken) {
        this.untaken = untaken;
        return this;
    }

    private volatile Untaken untaken;

    /** A record a place handed home and its tenant did not take. */
    @FunctionalInterface
    public interface Untaken {

        /** It stayed on the place: the tenant holds the record it is about as its own. */
        String KEPT_ON_THE_PLACE = "kept-on-the-place";

        /** It was refused, and the place was told why. */
        String REFUSED = "refused";

        /**
         * @param from    the place, by the credential that handed it
         * @param run     the run that produced it, as filed here, or null
         * @param outcome {@link #KEPT_ON_THE_PLACE} or {@link #REFUSED}
         * @param holds   the tenant's own record it was about, when it was kept
         */
        void said(String from, Item item, String run, String outcome, String holds);
    }

    private Batch outbound(String peer, int limit, Set<String> processes, Set<String> types,
            boolean withInputs, java.util.function.Consumer<String> passedOver,
            Runnable atTheEnd) {
        for (String type : types) {
            if (!admitted.contains(type)) {
                throw new IllegalStateException(site + ": this lane does not carry '" + type
                        + "' by type; it admits " + new java.util.TreeSet<>(admitted));
            }
        }
        if (!types.isEmpty() && contentFeed == null) {
            throw new IllegalStateException(site
                    + ": this lane has no content feed to carry declarations from");
        }
        Lane lane = open(peer);
        FeedChunk<FeedItem> chunk = workFeed.read(lane.ourCursor(), limit);
        List<Item> work = new ArrayList<>();
        List<String> travelling = new ArrayList<>();
        Map<String, List<String>> forRuns = new LinkedHashMap<>();
        for (FeedItem event : chunk.items()) {
            if (!WorkModel.TYPE.equals(event.typeName())) {
                continue;
            }
            Optional<Run> run = runs.byId(event.objectId());
            if (run.isPresent() && !travels(run.get(), processes)) {
                passedOver.accept(run.get().process());
            }
            if (run.isEmpty() || !travels(run.get(), processes)) {
                // The lane declares which processes travel. A site's own
                // housekeeping is not the other side's business, and mirroring
                // it would put one site's account of its own bring-up into the
                // cloud's.
                continue;
            }
            if (WorkModel.authoredElsewhere(run.get().key())) {
                // A site offers only what it authored. A mirror
                // sent back is a NEW record at the far side — filed under this
                // site, prefixed again — so a pair that echoed would
                // deepen a key and add a run every round, for ever. The same
                // rule the trail already obeys: what arrived from elsewhere
                // does not go back out.
                continue;
            }
            store.get(WorkModel.TYPE, event.objectId()).ifPresent(stored ->
                    work.add(Item.work(stored.id(), stored.versionId(), stored.lastUpdated(),
                            stored.payload())));
            travelling.add(run.get().key());
            // Which run named it travels with it: a record is here because a
            // particular piece of work needed it, and it leaves when that work
            // is over rather than when any work is.
            if (withInputs) {
                for (String reference : named(run.get())) {
                    forRuns.computeIfAbsent(reference, key -> new ArrayList<>())
                            .add(run.get().key());
                }
            }
        }
        if (chunk.drained()) {
            atTheEnd.run();
        }
        // A feed read at its end names no cursor. Marked sent, that would put
        // the position back at the start of the feed, to be read again.
        String cursor = chunk.nextCursor() != null ? chunk.nextCursor() : lane.ourCursor();
        return assembled(lane, cursor, work, travelling, forRuns, types, withInputs, limit);
    }

    /**
     * Whether a run goes where the processes asked for go: a run of one of
     * them, or an act said of such a run. An act is written as a run of its
     * own under the run it was said of, so the trail of who moved the work,
     * when and why goes with the work rather than staying where it was said.
     */
    private boolean travels(Run run, Set<String> processes) {
        if (processes.contains(run.process())) {
            return true;
        }
        if (!Runs.ACTS.equals(run.process()) || run.parent() == null) {
            return false;
        }
        return runs.byKey(run.parent()).map(said -> processes.contains(said.process()))
                .orElse(false);
    }

    /**
     * Every run of a process this place authored, from its store rather than
     * its work feed, handed in batches of at most {@code limit} runs.
     *
     * <p>For a process the lane's position moved past while it was not among
     * those the lane covers: the feed past that position was never looked at
     * for it, and may already have been let go of, while the runs are still
     * here. Each batch carries the lane's own epoch and position, so handing
     * one moves nothing.
     *
     * @return what {@code hand} returned for each batch, summed
     */
    public int everyRunOf(String peer, String process, int limit,
            java.util.function.ToIntFunction<Batch> hand) {
        return runsOf(open(peer), process, null, limit, hand);
    }

    /**
     * Moves the position at a peer to the head of the work feed, having
     * handed from the store every run of the processes asked for that
     * changed since the oldest event the position had not read. Returns how
     * many items went.
     *
     * <p>For a position so far behind that reading on to the head would hold
     * the round for hours: the runs that matter are found by what they are
     * rather than by reading past everything else the feed holds. The head is
     * taken first, so a run written while they are handed is read from the
     * feed after it, and nothing falls between the two.
     *
     * @param skew how far before the oldest unread event a run's own time may
     *             fall: a run is stamped when it is written, and its event
     *             when its transaction commits
     */
    public int caughtUpToTheHead(String peer, Set<String> processes, java.time.Duration skew,
            int limit, java.util.function.ToIntFunction<Batch> hand) {
        Lane lane = open(peer);
        String head = workFeed.headCursor();
        List<FeedItem> oldest = workFeed.read(lane.ourCursor(), 1).items();
        if (head == null || oldest.isEmpty()) {
            return 0;
        }
        java.time.Instant since = oldest.get(0).committedAt().minus(skew);
        int handed = 0;
        for (String process : new java.util.TreeSet<>(processes)) {
            handed += runsOf(lane, process, since, limit, hand);
        }
        write(new Lane(lane.key(), lane.peer(), lane.epoch(), head, lane.theirMarker(),
                lane.contentCursor(), lane.covers(), lane.askedHeld(),
                lane.handedAgain()));
        return handed;
    }

    private int runsOf(Lane lane, String process, java.time.Instant since, int limit,
            java.util.function.ToIntFunction<Batch> hand) {
        Criteria asked = Criteria.of(WorkModel.TYPE).eq("process", EnvelopeValue.of(process));
        if (since != null) {
            asked = asked.lastUpdated(Criteria.RangeOp.GE, since);
        }
        int handed = 0;
        List<Item> work = new ArrayList<>();
        List<String> travelling = new ArrayList<>();
        try (java.util.stream.Stream<StoredObject> found = cloud.jengu.dbo.core.api.Answered
                .pagedBy(store::page, asked)) {
            java.util.Iterator<StoredObject> each = found.iterator();
            while (each.hasNext()) {
                StoredObject stored = each.next();
                Run run = Run.of(stored);
                if (!process.equals(run.process()) || WorkModel.authoredElsewhere(run.key())) {
                    continue;
                }
                work.add(Item.work(stored.id(), stored.versionId(), stored.lastUpdated(),
                        stored.payload()));
                travelling.add(run.key());
                // What was said of it goes with it, found under it here: an
                // act is written as its own run, and is read past by process.
                for (Run act : runs.items(run)) {
                    if (Runs.ACTS.equals(act.process())) {
                        Optional<StoredObject> said = store.get(WorkModel.TYPE, act.id());
                        if (said.isPresent()) {
                            work.add(Item.work(said.get().id(), said.get().versionId(),
                                    said.get().lastUpdated(), said.get().payload()));
                            travelling.add(act.key());
                        }
                    }
                }
                if (travelling.size() >= limit) {
                    handed += hand.applyAsInt(assembled(lane, lane.ourCursor(), work, travelling,
                            Map.of(), Set.of(), false, limit));
                    work = new ArrayList<>();
                    travelling = new ArrayList<>();
                }
            }
        }
        if (!travelling.isEmpty()) {
            handed += hand.applyAsInt(assembled(lane, lane.ourCursor(), work, travelling,
                    Map.of(), Set.of(), false, limit));
        }
        return handed;
    }

    /**
     * A batch of the runs given: data first, then the runs, then their trail.
     *
     * @param cursor where the lane stands once the far side has taken it
     */
    private Batch assembled(Lane lane, String cursor, List<Item> work, List<String> travelling,
            Map<String, List<String>> forRuns, Set<String> types, boolean withInputs,
            int limit) {
        List<Item> items = new ArrayList<>();
        forRuns.forEach((reference, named) -> resolve(reference, named).ifPresent(items::add));
        // What a travelling run produced travels with it: individually while
        // the run still names every version; past the cap the receiver holds
        // the watermark in the run itself and asks by type from it.
        // And the content a travelling run put, or a record it produced
        // points at, each piece once, with the run it goes home with.
        Map<String, String> carried = new LinkedHashMap<>();
        for (String key : travelling) {
            runs.byKey(key).ifPresent(run -> run.produced().versions().forEach(produced ->
                    producedVersion(produced).ifPresent(copy -> {
                        items.add(copy);
                        if (!withInputs) {
                            java.util.regex.Matcher pointed = POINTED_AT.matcher(
                                    new String(copy.payload(), StandardCharsets.UTF_8));
                            while (pointed.find()) {
                                carried.putIfAbsent(pointed.group(1), key);
                            }
                        }
                    })));
            if (!withInputs && content != null) {
                content.keysPutBy(key).forEach(blob -> carried.putIfAbsent(blob, key));
            }
        }
        List<Item> contentItems = new ArrayList<>();
        carried.forEach((blob, run) -> carriedContent(blob, run).ifPresent(contentItems::add));
        String contentCursor = lane.contentCursor();
        if (!types.isEmpty()) {
            FeedChunk<FeedItem> content = contentFeed.read(lane.contentCursor(), limit);
            for (FeedItem event : content.items()) {
                if (!types.contains(event.typeName()) || event.deleted()
                        || event.kind() == cloud.jengu.dbo.core.api.feed.ChangeKind.DELETED) {
                    continue;
                }
                store.get(event.typeName(), event.objectId()).ifPresent(stored -> items.add(
                        Item.copy(stored.typeName(), stored.id(), stored.versionId(),
                                stored.lastUpdated(), stored.payload())));
            }
            contentCursor = content.nextCursor();
        }
        if (!withInputs && references != null) {
            // HOME, NAMING BY WHAT THINGS ARE. What a run here produced points
            // at records by this store's ids, and an id this store minted is
            // nothing at home. Asked as questions, they are answered there by
            // the tenant's own ids.
            Set<String> handed = new java.util.HashSet<>();
            items.forEach(item -> handed.add(item.typeName() + "/" + item.id()));
            items.replaceAll(item -> item.copy() ? Item.copy(item.typeName(), item.id(),
                    item.version(), item.recordedAt(), askedAsWhatTheyAre(item.payload(), handed))
                    : item);
        }
        items.addAll(0, contentItems);
        items.addAll(work);
        // Data, then work, then the account of what was done — an entry names
        // the run it was part of, and a trail arriving before the run it joins
        // to would be readable only in hindsight.
        items.addAll(trail(travelling));
        return new Batch(lane.epoch(), site, cursor, List.copyOf(items), contentCursor);
    }

    /**
     * A record's references by id, turned into what each referent says it is.
     *
     * <p>Left as it is where the id means the same at home: a record
     * travelling in the same batch, which arrives under its own id. A
     * referent this store does not hold, or one that says nothing it is
     * claimed under, is left too — home answers whether it holds that id, and
     * refuses the record if it does not.
     */
    private byte[] askedAsWhatTheyAre(byte[] payload, Set<String> handed) {
        return references.named(payload, reference -> {
            if (handed.contains(reference)) {
                return reference;
            }
            String typeName = reference.substring(0, reference.indexOf('/'));
            Optional<StoredObject> held = heldHere(reference);
            if (held.isEmpty()) {
                return reference;
            }
            cloud.jengu.dbo.core.api.Identifier claim = cloud.jengu.dbo.core.api.IdentityClaims
                    .of(store, typeName, held.get().payload());
            return claim == null ? reference
                    : cloud.jengu.dbo.core.api.IdentityClaims.question(typeName, claim);
        });
    }

    /** The record a reference by id names in this store, if this store holds its type at all. */
    private Optional<StoredObject> heldHere(String reference) {
        int slash = reference.indexOf('/');
        try {
            return store.get(reference.substring(0, slash), reference.substring(slash + 1));
        } catch (RuntimeException notAType) {
            return Optional.empty();
        }
    }

    /** The exact version a run named as produced, from history, as a copy. */
    private Optional<Item> producedVersion(String produced) {
        String[] parts = produced.split("/");
        if (parts.length != 3) {
            return Optional.empty();
        }
        long version;
        try {
            version = Long.parseLong(parts[2]);
        } catch (NumberFormatException notAVersion) {
            return Optional.empty();
        }
        return store.history(parts[0], parts[1]).stream()
                .filter(stored -> stored.versionId() == version && !stored.deleted())
                .findFirst()
                .map(stored -> Item.copy(stored.typeName(), stored.id(), stored.versionId(),
                        stored.lastUpdated(), stored.payload()));
    }

    /**
     * Marks a batch as accepted by the far side, so the next one starts after
     * it.
     *
     * <p>Separate from building it on purpose: a batch that never arrived must
     * be sent again, and a sender that advanced on hand-off would have no way
     * to know which.
     */
    /**
     * Marks what the peer answered it now holds as held there, before the
     * position moves past it.
     *
     * <p>Marked first: a lane that moved and then failed to mark would leave
     * what its peer holds unmarked for ever, as nothing is handed again. One
     * that marked and then failed to move hands the batch again, the peer
     * holds it already and says so, and the marks are written again.
     *
     * @param held each {@code type/id/version}, as the peer answered
     * @return how many of them this store holds marked
     */
    public int heldAt(String peer, List<String> held) {
        List<cloud.jengu.dbo.core.api.HeldElsewhere> marks = new ArrayList<>();
        for (String version : held) {
            String[] parts = version.split("/");
            if (parts.length != 3) {
                continue;
            }
            try {
                marks.add(new cloud.jengu.dbo.core.api.HeldElsewhere(parts[0], parts[1],
                        Long.parseLong(parts[2]), peer));
            } catch (IllegalArgumentException unreadable) {
                // Not a version this side wrote: nothing here to mark.
            }
        }
        return marks.isEmpty() ? 0 : store.markHeldElsewhere(marks);
    }

    public Lane sent(String peer, Batch batch) {
        Lane lane = open(peer);
        return write(new Lane(lane.key(), lane.peer(), lane.epoch(), batch.cursor(),
                lane.theirMarker(),
                batch.contentCursor() != null ? batch.contentCursor() : lane.contentCursor(),
                lane.covers(), lane.askedHeld(),
                lane.handedAgain()));
    }

    /**
     * The entries recorded here for the runs that are travelling.
     *
     * <p><b>Bounded by the work, like everything else on this lane.</b> An
     * entry joins to the run it was part of, so "the trail of what travelled"
     * is a query rather than a second mechanism — and the cloud gets the
     * site's account of the work it is being told about, not the site's whole
     * history.
     *
     * <p>Entries that arrived here from somewhere else do not go back out.
     * They carry the site that recorded them, and without that exclusion
     * two sites would hand each other the same entry forever, each
     * finding it new-to-the-other by a claim it had never made.
     */
    private List<Item> trail(List<String> travelling) {
        // Asked of the lane, not of the store: there is no way to ask a store
        // whether it knows a type, and asking one that does not know AuditEntry
        // fails the whole batch over a trail nobody wired.
        if (auditReplay == null || travelling.isEmpty()) {
            return List.of();
        }
        List<Item> entries = new ArrayList<>();
        for (String run : travelling) {
            store.select(Criteria.of(AUDIT)
                            .eq("run", EnvelopeValue.of(run))
                            .missing("appliance", true))
                    .forEach(stored -> entries.add(new Item(AUDIT, stored.id(),
                            stored.versionId(), stored.lastUpdated(), stored.payload(),
                            false, List.of())));
        }
        return entries;
    }

    /** What a run says it was about — depth zero, which is the whole bound today. */
    private List<String> named(Run run) {
        List<String> named = new ArrayList<>();
        for (Run child : runs.items(run)) {
            if (child.item() != null && child.item().reference() != null) {
                named.add(child.item().reference());
            }
        }
        if (run.item() != null && run.item().reference() != null) {
            named.add(run.item().reference());
        }
        return named;
    }

    /** The record a reference names, when it is a record here at all. */
    private Optional<Item> resolve(String reference, List<String> forRuns) {
        if (!reference.contains("/")) {
            // Work is about all sorts of things — an endpoint, a file, a code —
            // and only some of them are records here. What is not a record does
            // not travel as one.
            return Optional.empty();
        }
        String typeName = reference.substring(0, reference.indexOf('/'));
        String id = reference.substring(reference.indexOf('/') + 1);
        return store.get(typeName, id).map(stored -> new Item(typeName, stored.id(),
                stored.versionId(), stored.lastUpdated(), stored.payload(), false,
                List.copyOf(forRuns)));
    }

    // --------------------------------------------------------------- inbound

    /**
     * Applies what a peer sent.
     *
     * <p><b>Idempotent under replay and safe under reorder.</b> A batch re-sent
     * after a link drop applies once; a batch arriving after a newer one does
     * not put the older version back. The comparison is the source version, so
     * neither property depends on the connector being careful.
     *
     * <p><b>Runs land under the site that authored them.</b> Two
     * sites running the same task write the same run key, and without the
     * namespace the second arrival silently replaces the first — which is
     * exactly the comparison this exists to make possible.
     */
    public Applied apply(String peer, Batch batch) {
        return apply(peer, batch, null);
    }

    /**
     * Applies what a place of this tenant handed home: its runs, what they
     * produced, and their trail.
     *
     * <p><b>Only what is the place's own is taken.</b> What a run produced
     * arrives naming other records by what they say they are, and is stored
     * pointing at the tenant's own; one pointing at something the tenant does
     * not hold is refused, naming the reference, rather than stored pointing
     * at nothing. A record the tenant holds as its own — the configuration's,
     * or anybody's here — is never replaced from a place, found by its id or
     * by what it says: the place's write stays on the place. What was not
     * taken is said, by the place and record it was about.
     */
    public Applied applyFromAPlace(String peer, Batch batch) {
        return apply(peer, batch, new Home(batch));
    }

    /**
     * Which of a place's versions this side holds as the place's, at that
     * version or newer: what a place that marked late asks of what it handed
     * before. Nothing is written. A record held here as this side's own, or
     * not held at all, is not answered, so it stays unmarked on the place.
     *
     * @param from     the place, by the credential that asks
     * @param versions each {@code type/id/version}
     * @return those of them held here, as asked
     */
    public List<String> heldFromAPlace(String from, List<String> versions) {
        List<String> held = new ArrayList<>();
        for (String version : versions) {
            String[] parts = version.split("/");
            if (parts.length != 3) {
                continue;
            }
            long asked;
            try {
                asked = Long.parseLong(parts[2]);
            } catch (NumberFormatException unreadable) {
                continue;
            }
            String reference = parts[0] + "/" + parts[1];
            Optional<StoredObject> here;
            try {
                here = store.get(parts[0], parts[1]);
            } catch (RuntimeException notAnId) {
                // Not a record this side could hold: nothing to answer.
                continue;
            }
            if (here.isPresent() && here.get().versionId() >= asked
                    && !store.getByIdentifier(PlacementModel.TYPE, List.of(PlacementModel.key(
                            reference + " " + COPY + from))).isEmpty()) {
                held.add(version);
            }
        }
        return List.copyOf(held);
    }

    /** What applying a place's batch needs to know about the whole of it. */
    private static final class Home {
        /** Every record the batch carries, by reference: those arrive under their own ids. */
        final Set<String> handed = new java.util.HashSet<>();
        /** Which run produced each version, filed as it is filed here. */
        final Map<String, String> producedBy = new java.util.HashMap<>();

        Home(Batch batch) {
            for (Item item : batch.items()) {
                handed.add(item.typeName() + "/" + item.id());
                if (!item.work()) {
                    continue;
                }
                try {
                    Run run = Run.of(new StoredObject(item.id(), item.typeName(), item.version(),
                            item.recordedAt(), item.payload(), false, null, null, null));
                    run.produced().versions().forEach(version -> producedBy.put(version,
                            WorkModel.mirroredKey(batch.from(), run.key())));
                } catch (RuntimeException unreadable) {
                    // A run nobody can read names no record here: what it
                    // produced is still said, without the run.
                }
            }
        }

        String producerOf(Item item) {
            return producedBy.get(item.typeName() + "/" + item.id() + "/" + item.version());
        }
    }

    /**
     * One piece of content as it goes home, read as the machinery's own for
     * the run it goes with: handing it on discloses it to nobody here.
     *
     * <p>Content sealed to a person stays: that person's key is this place's,
     * and nobody at home could open what it sealed. Content gone — erased, or
     * past its window — has nothing left to hand.
     */
    private Optional<Item> carriedContent(String blob, String run) {
        if (content == null) {
            return Optional.empty();
        }
        Optional<cloud.jengu.dbo.core.api.BlobStore.Blob> held;
        try (cloud.jengu.dbo.core.api.Caller.InRun reading =
                     cloud.jengu.dbo.core.api.Caller.writingFor(run)) {
            held = content.get(blob);
        } catch (cloud.jengu.dbo.core.api.BlobStore.ErasedException
                 | cloud.jengu.dbo.core.api.BlobStore.WindowPassedException gone) {
            return Optional.empty();
        }
        if (held.isEmpty() || held.get().person() != null) {
            return Optional.empty();
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        carried.put("media", held.get().media());
        if (held.get().kind() != null) {
            carried.put("kind", held.get().kind());
        }
        carried.put("content", java.util.Base64.getEncoder().encodeToString(
                held.get().content()));
        return Optional.of(Item.content(blob, held.get().written() == null ? Instant.now()
                        : held.get().written(),
                cloud.jengu.dbo.core.wire.RecordWire.write(carried)
                        .getBytes(StandardCharsets.UTF_8), run));
    }

    /**
     * Content a place handed home, kept under the key it gave it and the run
     * it came with, filed under that place.
     */
    private boolean arriveContent(String from, Item item) {
        if (content == null) {
            throw new IllegalStateException("this side keeps no content, so what a place "
                    + "handed of it cannot be kept");
        }
        Object carried = cloud.jengu.dbo.core.wire.RecordWire.read(
                new String(item.payload(), StandardCharsets.UTF_8));
        String run = item.forRuns().isEmpty() ? null
                : WorkModel.mirroredKey(from, item.forRuns().get(0));
        try (cloud.jengu.dbo.core.api.Caller.InRun keeping =
                     cloud.jengu.dbo.core.api.Caller.writingFor(run)) {
            return content.arrive(item.id(),
                    java.util.Base64.getDecoder().decode(Json.str(carried, "content")),
                    optional(carried, "media"),
                    optional(carried, "kind"),
                    item.recordedAt());
        }
    }

    private Applied apply(String peer, Batch batch, Home home) {
        Lane lane = adopt(peer, batch);
        if (!lane.epoch().equals(batch.epoch())) {
            // A cursor from another lane instance. Refusing is inconvenient at
            // the moment somebody is restoring something, which is the correct
            // direction for the inconvenience to point.
            return new Applied(0, 0, List.of("this lane is running under epoch " + lane.epoch()
                    + " and the batch carries " + batch.epoch()
                    + " — a peer resumed from a copy cannot resume its position"));
        }
        long applied = 0;
        long skipped = 0;
        List<String> refused = new ArrayList<>();
        List<String> held = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Item item : batch.items()) {
            try {
                boolean written;
                if (CONTENT.equals(item.typeName())) {
                    written = arriveContent(batch.from(), item);
                } else if (AUDIT.equals(item.typeName())) {
                    // The trail goes through the one admitted path, and
                    // is never placed: what arrived for a piece of work leaves
                    // when that work closes, and an account of what happened
                    // is the one thing that must not. A site's history is not
                    // a working copy.
                    written = admit(batch.from(), item);
                } else {
                    written = item.copy() ? copy(batch.from(), item, home, held, kept)
                            : item.work() && home != null && performedAt(batch.from(), item)
                                    ? performedThere(batch.from(), item)
                            : item.work() ? mirror(batch.from(), item) : replicate(item);
                }
                if (!item.work() && !item.copy() && !AUDIT.equals(item.typeName())
                        && !CONTENT.equals(item.typeName())) {
                    // Note what brought it, so what arrives with work can leave
                    // with it. Without this the site cannot tell a copy
                    // from something of its own, and keeps everything.
                    place(batch, item);
                }
                if (written) {
                    applied++;
                } else {
                    // Already here, at this version or a newer one. Which is
                    // the answer, not a failure: a re-sent batch and a batch
                    // that arrived late look identical from here.
                    skipped++;
                }
            } catch (RuntimeException e) {
                // One record nobody can apply is a line in the answer, not a
                // stalled lane.
                refused.add(item.typeName() + "/" + item.id() + ": " + e.getMessage());
                if (home != null && item.copy()) {
                    try {
                        tell(batch.from(), item, home, Untaken.REFUSED, null);
                    } catch (RuntimeException unsaid) {
                        refused.add(item.typeName() + "/" + item.id()
                                + ": and the trail could not say so: " + unsaid.getMessage());
                    }
                }
            }
        }
        return new Applied(applied, skipped, List.copyOf(refused), List.copyOf(held),
                List.copyOf(kept));
    }

    /**
     * An entry another site recorded, through the refusal's one admission.
     *
     * <p>Loud when the port is absent rather than dropped: a lane wired
     * without it would converge on everything except the account of what
     * happened, and look healthy doing it — which is the shape of failure the
     * whole toolset is built to make impossible.
     */
    private boolean admit(String from, Item item) {
        if (auditReplay == null) {
            throw new IllegalStateException("this lane replicates no trail, and "
                    + from + " sent an audit entry — wire it with the AuditReplay port");
        }
        return auditReplay.replayAuditEntry(from, item.id(), item.version(), item.payload(),
                item.recordedAt());
    }

    /**
     * A copy from the far side: written under its source's authority, byte
     * for byte, and noted as a copy from that site so nothing revokes
     * it and the note answers why this site holds it. A record this
     * site authored itself stands in front of it — the local override
     * wins, as it does for a streamed copy — and the copy is skipped.
     */
    private boolean copy(String from, Item item, Home home, List<String> held,
            List<String> kept) {
        String reference = item.typeName() + "/" + item.id();
        String key = reference + " " + COPY + from;
        Optional<StoredObject> here = store.get(item.typeName(), item.id());
        boolean noted = !store.getByIdentifier(PlacementModel.TYPE,
                List.of(PlacementModel.key(key))).isEmpty();
        if (here.isPresent() && !noted) {
            if (home != null) {
                tell(from, item, home, Untaken.KEPT_ON_THE_PLACE, reference);
                kept.add(reference + "/" + item.version() + " " + reference);
            }
            return false;
        }
        String version = reference + "/" + item.version();
        if (here.isPresent() && here.get().versionId() >= item.version()) {
            held.add(version);
            return false;
        }
        byte[] payload = item.payload();
        if (home != null && references != null) {
            // Whose it is first: what a record says it is does not depend on
            // what it points at, and a record the tenant holds as its own
            // stays on the place whatever its references would have answered.
            Optional<String> own = ownRecordSaying(item.typeName(), item.id(), payload);
            if (own.isPresent()) {
                tell(from, item, home, Untaken.KEPT_ON_THE_PLACE, own.get());
                kept.add(reference + "/" + item.version() + " " + own.get());
                return false;
            }
            payload = answeredHere(payload, home);
        }
        // A change where it lands, not a restore: whoever watches this site
        // is told of it as of any other change, at the version and moment
        // the far side recorded.
        store.put(PutRequest.copied(item.typeName(), item.id(),
                here.map(StoredObject::versionId).orElse(null), payload,
                item.version(), item.recordedAt()),
                cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);
        held.add(version);
        java.util.function.BiConsumer<Item, String> noting = whence;
        if (noting != null) {
            noting.accept(item, from);
        }
        if (!noted) {
            store.putIfAbsent(IdentityRef.identifier(PlacementModel.KEY_SYSTEM, key),
                    PutRequest.create(PlacementModel.TYPE, ("{\"key\":" + Json.quoted(key)
                            + ",\"reference\":" + Json.quoted(reference)
                            + ",\"run\":" + Json.quoted(COPY + from)
                            + ",\"type\":" + Json.quoted(item.typeName()) + "}")
                            .getBytes(StandardCharsets.UTF_8)));
        }
        return true;
    }

    /**
     * A place's record with its references answered here: each question by
     * the record it names here, and each id held to being one this tenant
     * holds or one arriving beside it.
     *
     * @throws IllegalArgumentException naming the reference that could not be
     *         answered — the record is refused, not stored pointing at nothing
     */
    private byte[] answeredHere(byte[] payload, Home home) {
        byte[] answered = references.resolved(payload,
                cloud.jengu.dbo.core.face.ReferenceResolution.Resolver.NOTHING);
        return references.named(answered, reference -> {
            if (home.handed.contains(reference) || heldHere(reference).isPresent()) {
                return reference;
            }
            throw new IllegalArgumentException("the reference '" + reference + "' names "
                    + "nothing this tenant holds — an id one store minted means nothing in "
                    + "another, and a record pointing at nothing is refused rather than stored");
        });
    }

    /**
     * The tenant's own record this one says it is, when it is another record:
     * the configuration's, or anybody's here. A place's write of it is the
     * place's, and does not replace what the tenant holds.
     */
    private Optional<String> ownRecordSaying(String typeName, String id, byte[] payload) {
        cloud.jengu.dbo.core.api.Identifier claim =
                cloud.jengu.dbo.core.api.IdentityClaims.of(store, typeName, payload);
        if (claim == null) {
            return Optional.empty();
        }
        return store.getByIdentifier(typeName, List.of(claim)).stream()
                .filter(found -> !found.id().equals(id))
                .map(found -> typeName + "/" + found.id())
                .findFirst();
    }

    private void tell(String from, Item item, Home home, String outcome, String holds) {
        Untaken saying = untaken;
        if (saying != null) {
            saying.said(from, item, home.producerOf(item), outcome, holds);
        }
    }

    /**
     * Whether a place of this tenant goes by this name: one that has handed
     * its work here, which is how this side comes to hold a lane with it.
     */
    public boolean aPlace(String place) {
        return !place.startsWith(PERFORMED_AT) && read(place).isPresent();
    }

    /**
     * The place that made the record, when this side holds it as a copy of
     * one of its places': asked of each place by name, never by reading every
     * note.
     *
     * @param reference {@code type/id}
     */
    public Optional<String> placeThatMade(String reference) {
        for (Reading reading : readings()) {
            String peer = reading.peer();
            if (peer == null || peer.startsWith(PERFORMED_AT)) {
                continue;
            }
            if (!store.getByIdentifier(PlacementModel.TYPE,
                    List.of(PlacementModel.key(reference + " " + COPY + peer))).isEmpty()) {
                return Optional.of(peer);
            }
        }
        return Optional.empty();
    }

    /** Whether this site holds the record as a copy from another, and from which. */
    public Optional<String> copiedFrom(String typeName, String id) {
        String reference = typeName + "/" + id;
        for (StoredObject stored : store.select(Criteria.of(PlacementModel.TYPE))) {
            Placed placed = placed(stored);
            if (placed.reference().equals(reference) && placed.run().startsWith(COPY)) {
                return Optional.of(placed.run().substring(COPY.length()));
            }
        }
        return Optional.empty();
    }

    /** True when it was written, false when this side already had it or better. */
    private boolean replicate(Item item) {
        return replicate(item, false);
    }

    /**
     * The same, written as a change where {@code announced}: a run handed
     * between the two places is work for whoever reads this side's work
     * feed, and a run written as a restore would never be offered to them.
     */
    private boolean replicate(Item item, boolean announced) {
        Optional<StoredObject> here = store.get(item.typeName(), item.id());
        if (here.isPresent() && here.get().versionId() >= item.version()) {
            return false;
        }
        Long seen = here.map(StoredObject::versionId).orElse(null);
        if (announced) {
            store.put(PutRequest.copied(item.typeName(), item.id(), seen, item.payload(),
                    item.version(), item.recordedAt()),
                    cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);
        } else {
            store.put(new PutRequest(item.typeName(), item.id(), seen, item.payload(),
                    item.version(), item.recordedAt(), true));
        }
        return true;
    }

    /** Whether a run a place hands home is this side's own, addressed to that place to perform. */
    private boolean performedAt(String place, Item item) {
        return store.get(WorkModel.TYPE, item.id()).filter(stored -> !stored.deleted())
                .map(Run::of)
                .map(run -> run.addressee() != null && place.equals(run.addressee().place()))
                .orElse(false);
    }

    /**
     * The place's version of a run this side addressed to it: this side's own
     * run, moved as the place moved it, and still addressed to the place.
     */
    private boolean performedThere(String place, Item item) {
        String text = new String(item.payload(), StandardCharsets.UTF_8);
        if (!ADDRESSED_TO_A_PLACE.matcher(text).find()) {
            int end = text.lastIndexOf('}');
            text = text.substring(0, end) + ",\"for\":{\"place\":" + Json.quoted(place) + "}"
                    + text.substring(end);
        }
        return replicate(new Item(item.typeName(), item.id(), item.version(), item.recordedAt(),
                text.getBytes(StandardCharsets.UTF_8), true, item.forRuns()), true);
    }

    // -------------------------------------------------------------- performed

    /**
     * Applies the work a place's tenant addressed to it: what the runs name
     * first, each kept here while the run that brought it is open, then the
     * runs under the tenant's own ids and keys, for this place's runners to
     * take.
     *
     * <p>A record this place already holds is not noted: it is the place's,
     * and stays when the run ends. A run that names a record neither side
     * handed ends failed, saying which, rather than waiting for an input
     * nothing will bring.
     *
     * <p>No position is kept here. The tenant keeps how far the place took,
     * moved by the place saying so, so a batch applied and not said is
     * handed again and applies once.
     */
    public Applied performing(String from, Batch batch) {
        long applied = 0;
        long skipped = 0;
        List<String> refused = new ArrayList<>();
        List<String> arrived = new ArrayList<>();
        for (Item item : batch.items()) {
            try {
                boolean absent = !item.work() && store.get(item.typeName(), item.id())
                        .filter(stored -> !stored.deleted()).isEmpty();
                if (replicate(item, item.work())) {
                    applied++;
                } else {
                    skipped++;
                }
                if (item.work()) {
                    arrived.add(item.id());
                } else if (absent) {
                    placeFor(item, item.forRuns());
                }
            } catch (RuntimeException e) {
                refused.add(item.typeName() + "/" + item.id() + ": " + e.getMessage());
            }
        }
        for (String id : arrived) {
            store.get(WorkModel.TYPE, id).map(Run::of).filter(Run::open).ifPresent(run -> {
                // Held where it was started, nobody here may take it, and
                // whoever holds it there cannot reach it here.
                if (run.claimed(java.time.Instant.now())) {
                    runs.refused(run, "it is held by " + run.assignment().claimant() + " at "
                            + from + ", which handed the work, and nobody here may take work "
                            + "somebody holds");
                    return;
                }
                List<String> missing = new ArrayList<>();
                run.inputs().forEach((slot, filled) -> {
                    if (!filled.referred()) {
                        return;
                    }
                    for (String reference : filled.values()) {
                        int slash = reference.indexOf('/');
                        if (slash <= 0 || store.get(reference.substring(0, slash),
                                reference.substring(slash + 1)).filter(stored -> !stored.deleted())
                                .isEmpty()) {
                            missing.add(slot + ": " + reference);
                        }
                    }
                });
                if (!missing.isEmpty()) {
                    runs.refused(run, "its input " + String.join(", ", missing) + " is held "
                            + "neither here nor by " + from + ", which handed the work");
                }
            });
        }
        return new Applied(applied, skipped, List.copyOf(refused));
    }

    // ------------------------------------------------------------ brought back

    /**
     * What the work open here names and this side no longer holds, each
     * {@code type/id} with the keys of the runs that name it: what a place
     * that let go of a record asks its tenant for, as the tenant holds it.
     * At most {@code limit} records, so a place asks for years of them over
     * rounds.
     */
    public Map<String, List<String>> unheldByOpenWork(int limit) {
        Map<String, List<String>> unheld = new LinkedHashMap<>();
        Map<String, Boolean> held = new java.util.HashMap<>();
        try (java.util.stream.Stream<Run> open = runs.openWork()) {
            open.forEach(run -> Runs.over(run).values().forEach(reference -> {
                if (!reference.contains("/")) {
                    return;
                }
                if (!unheld.containsKey(reference) && unheld.size() >= limit) {
                    return;
                }
                if (held.computeIfAbsent(reference, this::holds)) {
                    return;
                }
                List<String> named = unheld.computeIfAbsent(reference, key -> new ArrayList<>());
                if (!named.contains(run.key())) {
                    named.add(run.key());
                }
            }));
        }
        return unheld;
    }

    /** Each {@code type/id} the work open here names, with the keys of the runs that name it. */
    private Map<String, List<String>> namedByOpenWork() {
        Map<String, List<String>> named = new java.util.HashMap<>();
        try (java.util.stream.Stream<Run> open = runs.openWork()) {
            open.forEach(run -> Runs.over(run).values().forEach(reference -> {
                List<String> keys = named.computeIfAbsent(reference, key -> new ArrayList<>());
                if (!keys.contains(run.key())) {
                    keys.add(run.key());
                }
            }));
        }
        return named;
    }

    /** Whether a reference names a record this side holds, undeleted. */
    private boolean holds(String reference) {
        return heldHere(reference).filter(stored -> !stored.deleted()).isPresent();
    }

    /**
     * The records a place handed this side, as they stand here: what work the
     * place started itself names and the place let go of. A record this side
     * holds as its own, or does not hold, is left out, so a place is handed
     * back what it handed and nothing else.
     *
     * @param place      the place, by the credential that asks
     * @param references each {@code type/id}
     */
    public Batch broughtFor(String place, List<String> references) {
        List<Item> items = new ArrayList<>();
        for (String reference : references) {
            heldHere(reference).filter(stored -> !stored.deleted())
                    .filter(stored -> !store.getByIdentifier(PlacementModel.TYPE, List.of(
                            PlacementModel.key(reference + " " + COPY + place))).isEmpty())
                    .ifPresent(stored -> items.add(new Item(stored.typeName(), stored.id(),
                            stored.versionId(), stored.lastUpdated(), stored.payload(), false,
                            List.of())));
        }
        return new Batch(null, site, null, List.copyOf(items));
    }

    /**
     * Applies what a place's tenant handed back of what its open work names,
     * each record kept here while a run naming it is open and let go of when
     * the last ends, as handed work's are. Then a run naming a record neither
     * side holds ends failed, saying which, rather than waiting for an input
     * nothing will bring.
     *
     * @param wanted what was asked for, as {@link #unheldByOpenWork} said it;
     *               what the batch carries beyond it is not taken
     */
    public Applied broughtBack(String from, Batch batch, Map<String, List<String>> wanted) {
        long applied = 0;
        long skipped = 0;
        List<String> refused = new ArrayList<>();
        Set<String> answered = new java.util.HashSet<>();
        for (Item item : batch.items()) {
            String reference = item.typeName() + "/" + item.id();
            List<String> named = wanted.get(reference);
            if (named == null) {
                continue;
            }
            answered.add(reference);
            try {
                Optional<StoredObject> here = store.get(item.typeName(), item.id());
                if (here.isPresent() && here.get().versionId() >= item.version()) {
                    skipped++;
                } else {
                    // Over what an earlier run's ending let go of, the version
                    // goes above it: a version only climbs.
                    Optional<StoredObject> last = here.isPresent() ? here
                            : store.history(item.typeName(), item.id()).stream()
                                    .reduce((older, newer) -> newer);
                    store.put(new PutRequest(item.typeName(), item.id(),
                            last.map(StoredObject::versionId).orElse(null), item.payload(),
                            last.map(stored -> Math.max(stored.versionId() + 1, item.version()))
                                    .orElse(item.version()),
                            item.recordedAt(), true));
                    applied++;
                }
                placeFor(item, named);
                // Work asked for before it came waits for it no longer.
                named.forEach(key -> runs.byKey(key).ifPresent(runs::waited));
            } catch (RuntimeException e) {
                refused.add(reference + ": " + e.getMessage());
            }
        }
        Set<String> said = new java.util.HashSet<>();
        wanted.forEach((reference, named) -> {
            if (answered.contains(reference)) {
                return;
            }
            for (String key : named) {
                if (!said.add(key)) {
                    continue;
                }
                // A run somebody holds is theirs to end.
                runs.byKey(key).filter(Run::open)
                        .filter(run -> !run.claimed(Instant.now())).ifPresent(run -> {
                    List<String> missing = Runs.over(run).entrySet().stream()
                            .filter(over -> over.getValue().contains("/")
                                    && !answered.contains(over.getValue())
                                    && !holds(over.getValue()))
                            .map(Map.Entry::getKey).toList();
                    if (!missing.isEmpty()) {
                        runs.refused(run, "its input " + String.join(", ", missing)
                                + " is held neither here nor, as this place's, by " + from);
                    }
                });
            }
        });
        return new Applied(applied, skipped, List.copyOf(refused));
    }

    /** A run from the other site, filed under it. */
    private boolean mirror(String from, Item item) {
        Object json = Json.parse(new String(item.payload(), StandardCharsets.UTF_8));
        String key = Json.str(json, "key");
        String mirrored = WorkModel.mirroredKey(from, key);
        String text = new String(item.payload(), StandardCharsets.UTF_8)
                .replaceFirst("\"key\":\"" + java.util.regex.Pattern.quote(key) + "\"",
                        "\"key\":\"" + java.util.regex.Matcher.quoteReplacement(mirrored) + "\"");
        // Its parent filed the same way, so the run it was started under is
        // found where the two of them are kept here — under the site, beside
        // the run, and not among this side's own.
        String parent = ((java.util.Map<?, ?>) json).get("parent") instanceof String named
                && !WorkModel.authoredElsewhere(named) ? named : null;
        if (parent != null) {
            text = text.replaceFirst("\"parent\":\"" + java.util.regex.Pattern.quote(parent) + "\"",
                    "\"parent\":\"" + java.util.regex.Matcher.quoteReplacement(
                            WorkModel.mirroredKey(from, parent)) + "\"");
        }
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        Optional<StoredObject> here = store.getByIdentifier(WorkModel.TYPE,
                List.of(WorkModel.key(mirrored))).stream().findFirst();
        if (here.isPresent() && here.get().versionId() >= item.version()) {
            return false;
        }
        if (here.isEmpty()) {
            store.putIfAbsent(IdentityRef.identifier(WorkModel.KEY_SYSTEM, mirrored),
                    PutRequest.create(WorkModel.TYPE, payload));
            return true;
        }
        store.put(new PutRequest(WorkModel.TYPE, here.get().id(), here.get().versionId(),
                payload, item.version(), item.recordedAt(), true));
        return true;
    }

    // ------------------------------------------------------------ revocation

    /**
     * Removes what arrived for work that is over.
     *
     * <p>A record stays while any work that brought it is still open, and goes
     * when the last of them closes — so a site holds the people it is treating
     * and stops holding them afterwards, which is a sentence that can be said
     * to a regulator.
     *
     * <p>Revocation is local. The far side does not send withdrawals: this
     * site holds the runs and can see for itself which are closed, and a
     * withdrawal that had to arrive would leave a site holding a register
     * every time the link was down.
     *
     * <p>A record the work changed here since it arrived stays, as this
     * side's own, and its retention lets go of it as of anything this side
     * records: its tenant may not hold the change yet, and the change starts
     * further work over it, which a step's watch starts on its own time. A
     * record other work open here names stays too, noted as here for that
     * work to go when it ends: letting go of either hands that work a slot it
     * cannot fill until a later round brings the record back.
     */
    public Revoked revoke() {
        long removed = 0;
        long kept = 0;
        Map<String, List<Placed>> byObject = new LinkedHashMap<>();
        for (StoredObject stored : store.select(Criteria.of(PlacementModel.TYPE))) {
            Placed placed = placed(stored);
            byObject.computeIfAbsent(placed.reference(), key -> new ArrayList<>()).add(placed);
        }
        Map<String, List<String>> named = null;
        for (Map.Entry<String, List<Placed>> entry : byObject.entrySet()) {
            boolean stillNeeded = entry.getValue().stream()
                    .anyMatch(placed -> placed.run().startsWith(COPY)
                            || runs.byKey(placed.run()).map(Run::open).orElse(false));
            if (stillNeeded) {
                kept++;
                continue;
            }
            String typeName = entry.getKey().substring(0, entry.getKey().indexOf('/'));
            String id = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
            Instant arrived = entry.getValue().stream().map(Placed::at)
                    .max(java.util.Comparator.naturalOrder()).orElseThrow();
            Optional<StoredObject> here = store.get(typeName, id);
            if (here.isPresent() && here.get().lastUpdated() != null
                    && here.get().lastUpdated().isAfter(arrived)) {
                entry.getValue().forEach(placed ->
                        store.delete(PlacementModel.TYPE, placed.id(), placed.version()));
                kept++;
                continue;
            }
            if (named == null) {
                named = namedByOpenWork();
            }
            List<String> namers = named.getOrDefault(entry.getKey(), List.of());
            if (!namers.isEmpty()) {
                placeFor(entry.getKey(), namers);
                entry.getValue().forEach(placed ->
                        store.delete(PlacementModel.TYPE, placed.id(), placed.version()));
                kept++;
                continue;
            }
            here.ifPresent(held -> store.delete(typeName, id, held.versionId()));
            entry.getValue().forEach(placed ->
                    store.delete(PlacementModel.TYPE, placed.id(), placed.version()));
            removed++;
        }
        return new Revoked(removed, kept);
    }

    /** One note that a record arrived with a piece of work, and when it was made. */
    private record Placed(String id, long version, String reference, String run, Instant at) {}

    private void place(Batch batch, Item item) {
        placeFor(item, item.forRuns().stream()
                .map(named -> batch.from() + MIRROR_SEPARATOR + named).toList());
    }

    /** Notes the record as here for each of these runs, by their keys here. */
    private void placeFor(Item item, List<String> runKeys) {
        placeFor(item.typeName() + "/" + item.id(), runKeys);
    }

    private void placeFor(String reference, List<String> runKeys) {
        String typeName = reference.substring(0, reference.indexOf('/'));
        for (String run : runKeys) {
            String key = reference + " " + run;
            if (!store.getByIdentifier(PlacementModel.TYPE,
                    List.of(PlacementModel.key(key))).isEmpty()) {
                continue;
            }
            store.putIfAbsent(IdentityRef.identifier(PlacementModel.KEY_SYSTEM, key),
                    PutRequest.create(PlacementModel.TYPE, ("{\"key\":" + Json.quoted(key)
                            + ",\"reference\":" + Json.quoted(reference)
                            + ",\"run\":" + Json.quoted(run)
                            + ",\"type\":" + Json.quoted(typeName) + "}")
                            .getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static Placed placed(StoredObject stored) {
        Object json = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        return new Placed(stored.id(), stored.versionId(), Json.str(json, "reference"),
                Json.str(json, "run"), stored.lastUpdated());
    }

    // ------------------------------------------------------------- lane state

    private Lane adopt(String peer, Batch batch) {
        Optional<Lane> existing = read(peer);
        if (existing.isPresent()) {
            return existing.get();
        }
        // First contact: the opening side minted the epoch and this side pins
        // it. Two lanes cannot both be first, because the second one has a
        // record by then.
        return write(new Lane(peer, peer, batch.epoch(), null, null));
    }

    private Optional<Lane> read(String peer) {
        return store.getByIdentifier(LaneModel.TYPE, List.of(LaneModel.key(peer)))
                .stream().findFirst().map(stored -> {
                    Object json = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
                    Object covers = ((Map<?, ?>) json).get("covers");
                    return new Lane(Json.str(json, "key"), Json.str(json, "peer"),
                            Json.str(json, "epoch"), optional(json, "ourCursor"),
                            optional(json, "theirMarker"), optional(json, "contentCursor"),
                            covers instanceof List<?> named
                                    ? named.stream().map(String::valueOf).toList() : null,
                            optional(json, "askedHeld"), optional(json, "handedAgain"));
                });
    }

    private Lane write(Lane lane) {
        byte[] payload = payload(lane);
        Optional<StoredObject> existing = store.getByIdentifier(LaneModel.TYPE,
                List.of(LaneModel.key(lane.key()))).stream().findFirst();
        if (existing.isEmpty()) {
            store.putIfAbsent(IdentityRef.identifier(LaneModel.KEY_SYSTEM, lane.key()),
                    PutRequest.create(LaneModel.TYPE, payload));
        } else {
            store.put(new PutRequest(LaneModel.TYPE, existing.get().id(),
                    existing.get().versionId(), payload));
        }
        return read(lane.peer()).orElse(lane);
    }

    private static String optional(Object json, String field) {
        Object value = ((Map<?, ?>) json).get(field);
        return value == null ? null : value.toString();
    }

    private static byte[] payload(Lane lane) {
        StringBuilder json = new StringBuilder(160)
                .append("{\"key\":").append(Json.quoted(lane.key()))
                .append(",\"peer\":").append(Json.quoted(lane.peer()))
                .append(",\"epoch\":").append(Json.quoted(lane.epoch()));
        if (lane.ourCursor() != null) {
            json.append(",\"ourCursor\":").append(Json.quoted(lane.ourCursor()));
        }
        if (lane.theirMarker() != null) {
            json.append(",\"theirMarker\":").append(Json.quoted(lane.theirMarker()));
        }
        if (lane.contentCursor() != null) {
            json.append(",\"contentCursor\":").append(Json.quoted(lane.contentCursor()));
        }
        if (lane.covers() != null) {
            json.append(",\"covers\":[");
            for (int i = 0; i < lane.covers().size(); i++) {
                json.append(i == 0 ? "" : ",").append(Json.quoted(lane.covers().get(i)));
            }
            json.append(']');
        }
        if (lane.askedHeld() != null) {
            json.append(",\"askedHeld\":").append(Json.quoted(lane.askedHeld()));
        }
        if (lane.handedAgain() != null) {
            json.append(",\"handedAgain\":").append(Json.quoted(lane.handedAgain()));
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
