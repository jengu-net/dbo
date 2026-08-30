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
 * Moving work and the data it names between two appliances of one tenant
 * (#80, ADR 0062).
 *
 * <p><b>There is no channel here, and that is the design.</b> This hands a
 * caller a batch and takes one back; a connector outside dbo carries the bytes,
 * authenticates, reconnects and frames. dbo holding a client for every external
 * system is the shape ADR 0060 refused, and the first channel-aware method here
 * would be the one that ends with a transport inside the engine.
 *
 * <p><b>Two appliances, one tenant.</b> Same code, same declarations, so a lane
 * is same-version replication: no converter chain, and the stored bytes travel
 * as they are.
 *
 * <p>What this owns is everything store-level: what the far side does not have
 * yet, an apply that is idempotent under replay <b>and safe under reorder</b>,
 * the epoch that makes a resumed-from-backup cursor detectable, and the marker
 * each side keeps about where the other said it had reached.
 */
public final class Lanes {

    /**
     * Where the far side's copy of a run is kept: under the appliance that
     * authored it. The convention itself is the run's ({@link
     * WorkModel#AUTHOR_SEPARATOR}), because the rules built on it are.
     */
    public static final String MIRROR_SEPARATOR = WorkModel.AUTHOR_SEPARATOR;

    /** The audit type, which travels but is never written like other content. */
    private static final String AUDIT = "AuditEntry";

    private final ObjectStore store;
    private final ChangeFeed workFeed;
    private final Runs runs;
    private final String appliance;
    private final cloud.jengu.dbo.core.api.AuditReplay auditReplay;

    /**
     * @param appliance this appliance's own name. It is not the tenant's — the
     *                  tenant is the same on both sides — and it is what a
     *                  mirrored record is filed under.
     */
    public Lanes(ObjectStore store, ChangeFeed workFeed, Runs runs, String appliance) {
        this(store, workFeed, runs, appliance, null);
    }

    /**
     * A lane that replicates the trail as well as the work (§7.8).
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
    public Lanes(ObjectStore store, ChangeFeed workFeed, Runs runs, String appliance,
            cloud.jengu.dbo.core.api.AuditReplay auditReplay) {
        this.store = store;
        this.workFeed = workFeed;
        this.runs = runs;
        this.appliance = appliance;
        this.auditReplay = auditReplay;
    }

    /** One lane's state: who it is with, under which epoch, and where both sides are. */
    public record Lane(String key, String peer, String epoch, String ourCursor,
            String theirMarker) {}

    /**
     * One object as it stands here, for a caller that is about to carry it
     * somewhere.
     *
     * @param recordedAt when the <b>source</b> recorded this version, carried
     *                   so the far side can replay it rather than restamp it.
     *                   A version's timestamp is evidence about when somebody
     *                   knew something, and an appliance that wrote the
     *                   arrival time would be saying the edge learned it when
     *                   the cloud heard about it — which is false for ordinary
     *                   content and, for an audit entry, is the whole of what
     *                   the entry was for.
     * @param work whether this is a run rather than the data a run named. The
     *             far side applies data first, so nothing arrives pointing at
     *             something absent.
     */
    public record Item(String typeName, String id, long version, Instant recordedAt,
            byte[] payload, boolean work, List<String> forRuns) {

        /** A run travelling on its own account. */
        public static Item work(String id, long version, Instant recordedAt, byte[] payload) {
            return new Item(WorkModel.TYPE, id, version, recordedAt, payload, true, List.of());
        }
    }

    /**
     * What to carry, and where it ends.
     *
     * <p>The cursor is the resume token: the sender does not advance until the
     * far side has said it applied, so a batch lost on the wire is re-sent
     * rather than skipped.
     */
    public record Batch(String epoch, String from, String cursor, List<Item> items) {

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /** What an apply did, for the connector that has to report it. */
    public record Applied(long applied, long skipped, List<String> refused) {}

    /** What a revocation pass removed, and what it kept because work still needs it. */
    public record Revoked(long removed, long kept) {}

    // ------------------------------------------------------------------ lane

    /**
     * The lane with this peer, minting an epoch if this is the first time.
     *
     * <p>A cursor is meaningless outside the lane instance that issued it: an
     * appliance restored from a backup resumes a position that no longer means
     * anything, and it looks perfectly healthy doing it. The epoch is what makes
     * that detectable — the same reason a tenant archive refuses to carry
     * delivery cursors at all.
     */
    public Lane open(String peer) {
        return read(peer).orElseGet(() -> write(new Lane(peer, peer, UuidV7.newId(), null, null)));
    }

    /** What this appliance has told the far side, and what it heard back. */
    public Optional<Lane> lane(String peer) {
        return read(peer);
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
                theirMarker));
    }

    // -------------------------------------------------------------- outbound

    /**
     * What this peer does not have yet: the runs since its cursor, and the data
     * those runs name.
     *
     * <p><b>Data before work</b>, so the far side never applies a run pointing
     * at something it has not got. <b>Bounded by what an item names</b> and
     * never by following references as far as they go — Patient → Encounter →
     * Observation → everything is how a bench ends up holding a register.
     */
    public Batch outbound(String peer, int limit, Set<String> processes) {
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
            if (run.isEmpty() || !processes.contains(run.get().process())) {
                // The lane declares which processes travel. An appliance's own
                // housekeeping is not the other side's business, and mirroring
                // it would put an edge's account of its own bring-up into the
                // cloud's.
                continue;
            }
            if (WorkModel.authoredElsewhere(run.get().key())) {
                // An appliance offers only what it authored (#151). A mirror
                // sent back is a NEW record at the far side — filed under this
                // appliance, prefixed again — so a pair that echoed would
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
            for (String reference : named(run.get())) {
                forRuns.computeIfAbsent(reference, key -> new ArrayList<>()).add(run.get().key());
            }
        }
        List<Item> items = new ArrayList<>();
        forRuns.forEach((reference, named) -> resolve(reference, named).ifPresent(items::add));
        items.addAll(work);
        // Data, then work, then the account of what was done — an entry names
        // the run it was part of, and a trail arriving before the run it joins
        // to would be readable only in hindsight.
        items.addAll(trail(travelling));
        return new Batch(lane.epoch(), appliance, chunk.nextCursor(), List.copyOf(items));
    }

    /**
     * Marks a batch as accepted by the far side, so the next one starts after
     * it.
     *
     * <p>Separate from building it on purpose: a batch that never arrived must
     * be sent again, and a sender that advanced on hand-off would have no way
     * to know which.
     */
    public Lane sent(String peer, Batch batch) {
        Lane lane = open(peer);
        return write(new Lane(lane.key(), lane.peer(), lane.epoch(), batch.cursor(),
                lane.theirMarker()));
    }

    /**
     * The entries recorded here for the runs that are travelling (§7.8).
     *
     * <p><b>Bounded by the work, like everything else on this lane.</b> An
     * entry joins to the run it was part of, so "the trail of what travelled"
     * is a query rather than a second mechanism — and the cloud gets the
     * edge's account of the work it is being told about, not the edge's whole
     * history.
     *
     * <p>Entries that arrived here from somewhere else do not go back out.
     * They carry the appliance that recorded them, and without that exclusion
     * two appliances would hand each other the same entry forever, each
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
     * <p><b>Runs land under the appliance that authored them.</b> Two
     * appliances running the same task write the same run key, and without the
     * namespace the second arrival silently replaces the first — which is
     * exactly the comparison this exists to make possible.
     */
    public Applied apply(String peer, Batch batch) {
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
        for (Item item : batch.items()) {
            try {
                boolean written;
                if (AUDIT.equals(item.typeName())) {
                    // The trail goes through the one admitted path (§7.8), and
                    // is never placed: what arrived for a piece of work leaves
                    // when that work closes, and an account of what happened
                    // is the one thing that must not. A bench's history is not
                    // a working copy.
                    written = admit(batch.from(), item);
                } else {
                    written = item.work() ? mirror(batch.from(), item) : replicate(item);
                }
                if (!item.work() && !AUDIT.equals(item.typeName())) {
                    // Note what brought it, so what arrives with work can leave
                    // with it. Without this the appliance cannot tell a copy
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
            }
        }
        return new Applied(applied, skipped, List.copyOf(refused));
    }

    /**
     * An entry another appliance recorded, through the refusal's one admission.
     *
     * <p>Loud when the port is absent rather than dropped: a lane wired
     * without it would converge on everything except the account of what
     * happened, and look healthy doing it — which is the shape of failure the
     * whole toolset is built to make impossible.
     */
    private boolean admit(String from, Item item) {
        if (auditReplay == null) {
            throw new IllegalStateException("this lane replicates no trail, and "
                    + from + " sent an audit entry — wire it with the AuditReplay port (§7.8)");
        }
        return auditReplay.replayAuditEntry(from, item.id(), item.version(), item.payload(),
                item.recordedAt());
    }

    /** True when it was written, false when this side already had it or better. */
    private boolean replicate(Item item) {
        Optional<StoredObject> here = store.get(item.typeName(), item.id());
        if (here.isPresent() && here.get().versionId() >= item.version()) {
            return false;
        }
        store.put(new PutRequest(item.typeName(), item.id(),
                here.map(StoredObject::versionId).orElse(null), item.payload(),
                item.version(), item.recordedAt(), true));
        return true;
    }

    /** A run from the other appliance, filed under it. */
    private boolean mirror(String from, Item item) {
        Object json = Json.parse(new String(item.payload(), StandardCharsets.UTF_8));
        String key = Json.str(json, "key");
        String mirrored = WorkModel.mirroredKey(from, key);
        byte[] payload = new String(item.payload(), StandardCharsets.UTF_8)
                .replaceFirst("\"key\":\"" + java.util.regex.Pattern.quote(key) + "\"",
                        "\"key\":\"" + java.util.regex.Matcher.quoteReplacement(mirrored) + "\"")
                .getBytes(StandardCharsets.UTF_8);
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
     * Removes what arrived for work that is over (#80).
     *
     * <p>A record stays while any work that brought it is still open, and goes
     * when the last of them closes — so a bench holds the people it is treating
     * and stops holding them afterwards, which is a sentence that can be said
     * to a regulator.
     *
     * <p>Revocation is local. The far side does not send withdrawals: this
     * appliance holds the runs and can see for itself which are closed, and a
     * withdrawal that had to arrive would leave a bench holding a register
     * every time the link was down.
     */
    public Revoked revoke() {
        long removed = 0;
        long kept = 0;
        Map<String, List<Placed>> byObject = new LinkedHashMap<>();
        for (StoredObject stored : store.select(Criteria.of(PlacementModel.TYPE))) {
            Placed placed = placed(stored);
            byObject.computeIfAbsent(placed.reference(), key -> new ArrayList<>()).add(placed);
        }
        for (Map.Entry<String, List<Placed>> entry : byObject.entrySet()) {
            boolean stillNeeded = entry.getValue().stream()
                    .anyMatch(placed -> runs.byKey(placed.run()).map(Run::open).orElse(false));
            if (stillNeeded) {
                kept++;
                continue;
            }
            String typeName = entry.getKey().substring(0, entry.getKey().indexOf('/'));
            String id = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
            store.get(typeName, id).ifPresent(here ->
                    store.delete(typeName, id, here.versionId()));
            entry.getValue().forEach(placed ->
                    store.delete(PlacementModel.TYPE, placed.id(), placed.version()));
            removed++;
        }
        return new Revoked(removed, kept);
    }

    /** One note that a record arrived with a piece of work. */
    private record Placed(String id, long version, String reference, String run) {}

    private void place(Batch batch, Item item) {
        String reference = item.typeName() + "/" + item.id();
        for (String named : item.forRuns()) {
            String run = batch.from() + MIRROR_SEPARATOR + named;
            String key = reference + " " + run;
            if (!store.getByIdentifier(PlacementModel.TYPE,
                    List.of(PlacementModel.key(key))).isEmpty()) {
                continue;
            }
            store.putIfAbsent(IdentityRef.identifier(PlacementModel.KEY_SYSTEM, key),
                    PutRequest.create(PlacementModel.TYPE, ("{\"key\":" + Json.quoted(key)
                            + ",\"reference\":" + Json.quoted(reference)
                            + ",\"run\":" + Json.quoted(run)
                            + ",\"type\":" + Json.quoted(item.typeName()) + "}")
                            .getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static Placed placed(StoredObject stored) {
        Object json = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        return new Placed(stored.id(), stored.versionId(), Json.str(json, "reference"),
                Json.str(json, "run"));
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
                    return new Lane(Json.str(json, "key"), Json.str(json, "peer"),
                            Json.str(json, "epoch"), optional(json, "ourCursor"),
                            optional(json, "theirMarker"));
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
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
