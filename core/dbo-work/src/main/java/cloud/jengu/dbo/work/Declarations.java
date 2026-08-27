package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has said they can run what, and whether they are still answering (#78).
 *
 * <p>Resolution walks these rather than the bundles installed here, which is
 * what makes a local implementation and a remote participant two candidates for
 * the same step, ordered by the overlay chain rather than by which machine they
 * happen to be on.
 *
 * <p><b>Presence is derived, not declared.</b> A participant is present while
 * its named cursor moves. No heartbeat and no lease service — and the diagnosis
 * is better than either, because "declared and not answering" is a different
 * sentence from "nothing is declared" and an operator needs to tell them apart.
 *
 * <p>The one trap worth naming: <b>a caught-up participant's cursor does not
 * move either.</b> Silence with nothing to read is not absence; silence with
 * work waiting is. So a declaration is only doubted when its consumer is behind
 * and has not moved.
 */
public final class Declarations {

    private final ObjectStore store;
    private final ChangeFeed feed;
    private final Duration patience;
    /** The last cursor seen for a consumer, and when it was seen to be there. */
    private final Map<String, Sighting> sightings = new ConcurrentHashMap<>();

    private record Sighting(String cursor, Instant at) {}

    /**
     * @param patience how long a consumer may be behind and unmoving before its
     *                 declaration stops being a candidate. Long enough that a
     *                 slow participant is not disqualified for being slow.
     */
    public Declarations(ObjectStore store, ChangeFeed feed, Duration patience) {
        this.store = store;
        this.feed = feed;
        this.patience = patience;
    }

    /**
     * One participant's claim to be a candidate.
     *
     * @param consumer the feed consumer name it pulls with — this is the whole
     *                 of presence, so a declaration without one can be resolved
     *                 to and never doubted
     */
    public record Declared(String process, String step, String name, String version,
            String provider, Scope scope, String consumer,
            java.util.Map<String, String> metadata) {

        public Declared {
            metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        }

        /** Compatibility with callers that predate {@code metadata} (#148). */
        public Declared(String process, String step, String name, String version,
                String provider, Scope scope, String consumer) {
            this(process, step, name, version, provider, scope, consumer, java.util.Map.of());
        }

        /**
         * The same declaration carrying this vitals block instead (#148):
         * extensible key/value, opaque to the engine — health, throughput,
         * whatever a component kind brings. Re-declaring REPLACES it, because
         * {@link Declarations#declare} is an idempotent update by key; a
         * candidate list must not become a metrics history. Presence stays
         * derived from the cursor — a self-reported "healthy" from a stuck
         * component is exactly the lie derived presence exists to catch — so
         * vitals annotate presence, never replace it.
         */
        public Declared withVitals(java.util.Map<String, String> vitals) {
            return new Declared(process, step, name, version, provider, scope, consumer,
                    vitals);
        }

        /** The key it is found by: one declaration per step, scope and name. */
        public String key() {
            return process + "/" + step + "/" + scope.wire() + "/" + name;
        }

        public Executor executor() {
            return new Executor(name, version, provider, scope);
        }
    }

    /**
     * Announces a participant, or re-announces it with a new version.
     *
     * <p>Idempotent by key, because a participant restarting is the same
     * participant — a second record per restart would make the candidate list a
     * history of deployments.
     */
    public Declared declare(Declared declared) {
        Optional<StoredObject> existing = byKey(declared.key());
        byte[] payload = payload(declared);
        if (existing.isEmpty()) {
            store.putIfAbsent(IdentityRef.identifier(ExecutorModel.KEY_SYSTEM, declared.key()),
                    PutRequest.create(ExecutorModel.TYPE, payload));
        } else {
            store.put(new PutRequest(ExecutorModel.TYPE, existing.get().id(),
                    existing.get().versionId(), payload));
        }
        return declared;
    }

    /** A participant that is going away for good, rather than being quiet. */
    public void withdraw(Declared declared) {
        byKey(declared.key()).ifPresent(stored ->
                store.delete(ExecutorModel.TYPE, stored.id(), stored.versionId()));
    }

    /** Everything declared for this tenant, present or not. */
    public List<Declared> all() {
        return store.select(Criteria.of(ExecutorModel.TYPE)).stream()
                .map(Declarations::read).toList();
    }

    /** What is declared for one step, present or not. */
    public List<Declared> forStep(String process, String step) {
        return store.select(Criteria.of(ExecutorModel.TYPE)
                        .eq("process", EnvelopeValue.of(process))
                        .eq("step", EnvelopeValue.of(step))).stream()
                .map(Declarations::read).toList();
    }

    /**
     * Whether this participant is answering.
     *
     * <p>Read from the cursor rather than asked of the participant: the thing
     * that cannot tell you it has stopped is the thing that has stopped.
     */
    public boolean present(Declared declared) {
        if (declared.consumer() == null) {
            // Nothing to read presence from. A declaration that cannot be
            // doubted is taken at its word rather than quietly dropped.
            return true;
        }
        String cursor = feed.cursorOf(declared.consumer());
        Sighting last = sightings.get(declared.consumer());
        if (last == null || !java.util.Objects.equals(last.cursor(), cursor)) {
            sightings.put(declared.consumer(), new Sighting(cursor, Instant.now()));
            return true;
        }
        if (feed.lag(declared.consumer()) == 0) {
            // Caught up. A participant with nothing to read is silent for the
            // same reason a healthy one is, and reading that as absence would
            // disqualify exactly the participants that are keeping up.
            sightings.put(declared.consumer(), new Sighting(cursor, Instant.now()));
            return true;
        }
        return last.at().isAfter(Instant.now().minus(patience));
    }

    /**
     * The candidates resolution should consider: declared, and answering.
     *
     * <p>Handed to {@link ExecutorResolution} as a supplier, so a participant
     * that goes quiet between two resolutions is not selected by the second —
     * the same reason candidates are asked for rather than held.
     */
    public List<ExecutorCandidate> candidates() {
        List<ExecutorCandidate> candidates = new ArrayList<>();
        for (Declared declared : all()) {
            if (!present(declared)) {
                continue;
            }
            candidates.add(new DeclaredCandidate(declared));
        }
        return candidates;
    }

    /** A declared participant, offered to resolution as a candidate for its own step. */
    private record DeclaredCandidate(Declared declared) implements ExecutorCandidate {

        @Override
        public Executor executor() {
            return declared.executor();
        }

        @Override
        public boolean willTake(Work work) {
            // A declaration is for a step, and that is the whole of what it
            // claims. Whether this participant may actually take the work is
            // its credential's business, decided where it tries.
            return declared.process().equals(work.process())
                    && declared.step().equals(work.step());
        }
    }

    /**
     * What an operator has to be able to tell apart: nothing declared, and what
     * is declared not answering.
     */
    public record Known(Declared declared, boolean present) {}

    public List<Known> known() {
        return all().stream().map(declared -> new Known(declared, present(declared))).toList();
    }

    private Optional<StoredObject> byKey(String key) {
        return store.getByIdentifier(ExecutorModel.TYPE, List.of(ExecutorModel.key(key)))
                .stream().findFirst();
    }

    private static Declared read(StoredObject stored) {
        Object json = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        Object consumer = ((Map<?, ?>) json).get("consumer");
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        if (((Map<?, ?>) json).get("metadata") instanceof Map<?, ?> block) {
            block.forEach((k, v) -> metadata.put(String.valueOf(k), String.valueOf(v)));
        }
        return new Declared(Json.str(json, "process"), Json.str(json, "step"),
                Json.str(json, "name"), Json.str(json, "version"), Json.str(json, "provider"),
                Scope.of(Json.str(json, "scope")),
                consumer == null ? null : consumer.toString(), metadata);
    }

    private static byte[] payload(Declared declared) {
        StringBuilder json = new StringBuilder(256)
                .append("{\"key\":").append(Json.quoted(declared.key()))
                .append(",\"process\":").append(Json.quoted(declared.process()))
                .append(",\"step\":").append(Json.quoted(declared.step()))
                .append(",\"name\":").append(Json.quoted(declared.name()))
                .append(",\"version\":").append(Json.quoted(declared.version()))
                .append(",\"provider\":").append(Json.quoted(declared.provider()))
                .append(",\"scope\":").append(Json.quoted(declared.scope().wire()));
        if (declared.consumer() != null) {
            json.append(",\"consumer\":").append(Json.quoted(declared.consumer()));
        }
        if (!declared.metadata().isEmpty()) {
            json.append(",\"metadata\":{");
            boolean first = true;
            for (var entry : new java.util.TreeMap<>(declared.metadata()).entrySet()) {
                json.append(first ? "" : ",").append(Json.quoted(entry.getKey()))
                        .append(':').append(Json.quoted(entry.getValue()));
                first = false;
            }
            json.append('}');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
