package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which participants sit behind which: the edges a seal past a router may
 * follow, and nothing else.
 *
 * <p><b>A report is the whole set.</b> A router says everything it routes,
 * every time, so an edge it leaves out is gone with the report that left it
 * out — and a router can no longer seal work to a routee it has stopped
 * routing. The store keeps no history of who was where; the version chain of
 * each row is the only trace.
 */
public final class Trackables {

    private final ObjectStore store;

    public Trackables(ObjectStore store) {
        this.store = store;
    }

    /**
     * A router's report of what sits behind it, to any depth.
     *
     * <p>A routee reported with no participant in front of it sits directly
     * behind the reporter. Whatever the reporter routed before, directly or
     * through a routee of its own, and does not name now, is removed.
     *
     * @param reporter the participant whose lane carried the report; trusted
     *                 about what is behind it exactly as it is about itself,
     *                 because there is no other path to ask
     * @return how many rows the report touched
     */
    public int routes(String reporter, List<Trackable> behind) {
        int touched = 0;
        Set<String> reported = new HashSet<>();
        for (Trackable routee : behind) {
            Trackable edge = new Trackable(routee.id(),
                    routee.routedBy() == null ? reporter : routee.routedBy());
            if (!byId(edge.id()).map(edge::equals).orElse(false)) {
                write(edge);
                touched++;
            }
            reported.add(edge.id());
        }
        for (Trackable earlier : reachableFrom(reporter)) {
            if (!reported.contains(earlier.id())) {
                remove(earlier.id());
                touched++;
            }
        }
        return touched;
    }

    /** One routee, however deep it sits. */
    public Optional<Trackable> byId(String id) {
        return held(id).map(Trackables::of);
    }

    /** What sits directly behind one participant. */
    public List<Trackable> behind(String router) {
        return store.select(Criteria.of(TrackableModel.TYPE)
                        .eq("routedBy", EnvelopeValue.of(router))).stream()
                .map(Trackables::of).toList();
    }

    /** Everything this router's last report named, at any depth. */
    private List<Trackable> reachableFrom(String router) {
        List<Trackable> found = new ArrayList<>();
        collect(router, found, new HashSet<>());
        return found;
    }

    private void collect(String router, List<Trackable> found, Set<String> seen) {
        if (!seen.add(router)) {
            // A cycle is a router reporting badly: the walk stops, and what
            // was found still answers.
            return;
        }
        for (Trackable child : behind(router)) {
            found.add(child);
            collect(child.id(), found, seen);
        }
    }

    private Optional<StoredObject> held(String id) {
        return store.getByIdentifier(TrackableModel.TYPE, List.of(TrackableModel.key(id)))
                .stream().findFirst();
    }

    private void write(Trackable trackable) {
        Optional<StoredObject> here = held(trackable.id());
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

    private void remove(String id) {
        held(id).ifPresent(row -> store.delete(TrackableModel.TYPE, row.id(), row.versionId()));
    }

    private static byte[] payload(Trackable trackable) {
        StringBuilder json = new StringBuilder(64)
                .append("{\"id\":").append(Json.quoted(trackable.id()));
        if (trackable.routedBy() != null) {
            json.append(",\"routedBy\":").append(Json.quoted(trackable.routedBy()));
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Trackable of(StoredObject stored) {
        Object node = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        return new Trackable(Json.str(node, "id"),
                ((java.util.Map<?, ?>) node).get("routedBy") == null ? null
                        : Json.str(node, "routedBy"));
    }
}
