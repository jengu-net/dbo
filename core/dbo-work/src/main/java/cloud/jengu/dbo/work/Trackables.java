package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is out there, and who last saw it.
 *
 * <p><b>Only a connected worker reports, and it may report for others.</b>
 * That is the whole mechanism: {@link #routes} takes the tree a worker can see
 * and normalises it into one row per trackable, so the state of an instrument
 * three hops away is stored exactly as the state of the connector is. The
 * store never reaches down the chain — it has no path — and never invents a
 * freshness rule about what comes back, because it has no way to evaluate one
 * and a single threshold across a serial line and a socket would be wrong for
 * both.
 *
 * <p><b>Replaced, never accumulated.</b> A report is the current state of the
 * things behind a router, and the previous one is not history worth keeping
 * here — the version chain already keeps it, and a state record that grew
 * would become the metrics history this deliberately is not.
 */
public final class Trackables {

    private final ObjectStore store;

    public Trackables(ObjectStore store) {
        this.store = store;
    }

    /**
     * A connected worker reporting what it can see behind it.
     *
     * <p>Each trackable is written under its own id and stamped with this
     * reporter as the observer — not with the parent, because the two are
     * different questions. A router two hops up is who to ask; the parent is
     * where the thing sits.
     *
     * @param reporter the connected worker whose report this is; it is trusted
     *                 about what is behind it exactly as it is trusted about
     *                 itself, because there is no other path to ask
     * @return how many rows the report touched
     */
    public int routes(String reporter, List<Trackable> behind) {
        Instant seen = Instant.now();
        int written = 0;
        for (Trackable trackable : behind) {
            write(new Trackable(trackable.id(), trackable.kind(), trackable.routedBy(),
                    trackable.state(), new Trackable.Attested(reporter, seen)));
            written++;
        }
        return written;
    }

    /** A worker's own state, where presence is the cursor's business rather than a claim. */
    public void reports(Trackable itself) {
        if (!itself.reportsForItself()) {
            throw new IllegalArgumentException("'" + itself.id() + "' names a router, so it is "
                    + "something somebody else saw — report it through routes(), which "
                    + "records who saw it");
        }
        write(itself);
    }

    /** One trackable, however deep it sits. */
    public Optional<Trackable> byId(String id) {
        return store.getByIdentifier(TrackableModel.TYPE, List.of(TrackableModel.key(id)))
                .stream().findFirst().map(Trackables::of);
    }

    /** What sits directly behind one router — the tree, one level at a time. */
    public List<Trackable> behind(String router) {
        return store.select(Criteria.of(TrackableModel.TYPE)
                        .eq("routedBy", EnvelopeValue.of(router))).stream()
                .map(Trackables::of).toList();
    }

    /**
     * Everything one connected worker last reported, at any depth.
     *
     * <p>The other question an operator asks: not "what is behind this box"
     * but "what does this connector account for at all", which is the set a
     * silent connector stops speaking for.
     */
    public List<Trackable> observedBy(String reporter) {
        return store.select(Criteria.of(TrackableModel.TYPE)
                        .eq("observedBy", EnvelopeValue.of(reporter))).stream()
                .map(Trackables::of).toList();
    }

    public List<Trackable> all() {
        return store.select(Criteria.of(TrackableModel.TYPE)).stream()
                .map(Trackables::of).toList();
    }

    private void write(Trackable trackable) {
        Optional<StoredObject> here = store
                .getByIdentifier(TrackableModel.TYPE, List.of(TrackableModel.key(trackable.id())))
                .stream().findFirst();
        byte[] payload = payload(trackable);
        if (here.isEmpty()) {
            store.putIfAbsent(
                    IdentityRef.identifier(TrackableModel.ID_SYSTEM, trackable.id()),
                    PutRequest.create(TrackableModel.TYPE, payload));
            return;
        }
        store.put(new PutRequest(TrackableModel.TYPE, here.get().id(),
                here.get().versionId(), payload));
    }

    private static byte[] payload(Trackable trackable) {
        StringBuilder json = new StringBuilder(128)
                .append("{\"id\":").append(Json.quoted(trackable.id()))
                .append(",\"kind\":").append(Json.quoted(trackable.kind()));
        if (trackable.routedBy() != null) {
            json.append(",\"routedBy\":").append(Json.quoted(trackable.routedBy()));
        }
        if (trackable.attested() != null) {
            json.append(",\"observedBy\":").append(Json.quoted(trackable.attested().observedBy()))
                    .append(",\"observedAt\":")
                    .append(Json.quoted(trackable.attested().at().toString()));
        }
        if (!trackable.state().isEmpty()) {
            json.append(",\"state\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : trackable.state().entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append(Json.quoted(entry.getKey())).append(':')
                        .append(Json.quoted(entry.getValue()));
            }
            json.append('}');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Trackable of(StoredObject stored) {
        Object node = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        Map<?, ?> fields = (Map<?, ?>) node;
        Map<String, String> state = new LinkedHashMap<>();
        if (fields.get("state") instanceof Map<?, ?> declared) {
            declared.forEach((key, value) ->
                    state.put(String.valueOf(key), String.valueOf(value)));
        }
        Trackable.Attested attested = fields.get("observedBy") == null ? null
                : new Trackable.Attested(Json.str(node, "observedBy"),
                        Instant.parse(Json.str(node, "observedAt")));
        return new Trackable(Json.str(node, "id"), Json.str(node, "kind"),
                fields.get("routedBy") == null ? null : Json.str(node, "routedBy"),
                state, attested);
    }

    /** Every trackable reachable from a router, depth first — the whole subtree. */
    public List<Trackable> subtree(String router) {
        List<Trackable> found = new ArrayList<>();
        collect(router, found, new java.util.HashSet<>());
        return found;
    }

    private void collect(String router, List<Trackable> found, java.util.Set<String> seen) {
        if (!seen.add(router)) {
            // A cycle is a router reporting badly, which is a fact about that
            // router rather than something to throw over: the walk stops and
            // what was found still answers.
            return;
        }
        for (Trackable child : behind(router)) {
            found.add(child);
            collect(child.id(), found, seen);
        }
    }
}
