package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.StepId;
import cloud.jengu.dbo.core.process.Steps;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The catalogue's second door: steps introduced over the link.
 *
 * <p>The groundwork left this door open on purpose — a step id is opaque and
 * globally stable, fixed with the record rather than the catalogue, so a
 * participant bringing its own capability is an addition, never a migration.
 * The introduction is recorded through the tenant's store like anything else:
 * a credential that may not write there is refused by the authority, not by
 * this model, and <b>an introduction grants its introducer nothing</b> — the
 * declaration binds the introducer exactly as it binds anybody.
 *
 * <p><b>One id, one definition.</b> Re-introduction by the same participant
 * replaces — a restart is the same participant. A different participant
 * bringing an <em>identical</em> declaration co-introduces, because scaling
 * is parallel runners and a fleet is not a conflict. What is refused, by
 * name, is a different <em>definition</em> for one id — another introducer's
 * conflicting declaration, or an id the installed catalogue already declares
 * — and the {@linkplain #composedWith() composed view} refuses an id that
 * BOTH doors declare, because a module installed after an introduction is a
 * collision arriving late, not an override.
 */
public final class Introductions {

    private final ObjectStore store;

    /** The in-container catalogue, which an introduction may not collide with. */
    private final Steps installed;

    public Introductions(ObjectStore store, Steps installed) {
        this.store = store;
        this.installed = installed;
    }

    /** One introduced step, with its provenance. */
    public record Introduced(StepDeclaration step, String introducer) {}

    /**
     * Records a step arriving over the link, or re-records it.
     *
     * <p><b>Scaling must be possible</b>, and scaling here means parallel
     * runners: replicas of one participant each introduce the step they
     * perform, racing each other on the way up. So what is idempotent is
     * wider than one introducer — the same introducer replaces (a restart is
     * the same participant, and an upgrade is its new definition), and a
     * <em>different</em> introducer bringing an <b>identical declaration</b>
     * co-introduces: one record, first bringer's name as provenance, no
     * refusal — that is a fleet, not a conflict. What collides is a
     * different <em>definition</em> for the same id, whoever brings it, and
     * a race lost on the write is re-read rather than thrown: somebody else
     * introducing the same step is the normal case of a replica set.
     */
    public Introduced introduce(StepDeclaration step, String introducer) {
        String id = step.id().toString();
        if (installed.byId(id).isPresent()) {
            throw new Collision(id, "a module installed here", introducer);
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<StoredObject> existing = byId(id);
            try {
                if (existing.isEmpty()) {
                    store.putIfAbsent(IdentityRef.identifier(IntroductionModel.KEY_SYSTEM, id),
                            PutRequest.create(IntroductionModel.TYPE,
                                    payload(step, introducer)));
                    // putIfAbsent under a race may have kept a rival's write:
                    // loop once more and judge what is actually there.
                    if (byId(id).map(stored -> read(stored).step().equals(step)
                                    || read(stored).introducer().equals(introducer))
                            .orElse(false)) {
                        return new Introduced(step, introducer);
                    }
                    continue;
                }
                Introduced current = read(existing.get());
                if (current.introducer().equals(introducer)) {
                    store.put(new PutRequest(IntroductionModel.TYPE, existing.get().id(),
                            existing.get().versionId(), payload(step, introducer)));
                    return new Introduced(step, introducer);
                }
                if (current.step().equals(step)) {
                    // A replica of the fleet: same definition, another hand.
                    // The record stands with its first bringer's provenance.
                    return new Introduced(step, current.introducer());
                }
                throw new Collision(id, current.introducer(), introducer);
            } catch (cloud.jengu.dbo.core.api.VersionConflictException
                    | cloud.jengu.dbo.core.api.IdentityConflictException raced) {
                // Somebody wrote between the read and the write — a replace
                // raced (version) or a create raced the identity claim. For
                // a fleet coming up, the expected case: the next pass reads
                // what won and judges it.
            }
        }
        throw new IllegalStateException("introducing '" + id + "' kept losing the write "
                + "race — the record is being replaced faster than it can be read");
    }

    /** A participant taking its step away for good, rather than being quiet. */
    public void withdraw(String stepId, String introducer) {
        byId(stepId).ifPresent(stored -> {
            if (!read(stored).introducer().equals(introducer)) {
                throw new Collision(stepId, read(stored).introducer(), introducer);
            }
            store.delete(IntroductionModel.TYPE, stored.id(), stored.versionId());
        });
    }

    /** Everything introduced here, with who introduced it. */
    public List<Introduced> all() {
        return store.select(Criteria.of(IntroductionModel.TYPE)).stream()
                .map(Introductions::read).toList();
    }

    /**
     * The whole catalogue this tenant works from: installed modules AND this
     * store's introductions, one {@link Steps} view. An id both doors declare
     * is refused at the lookup — loudly, naming both declarers — because
     * answering with either would silently pick a winner in a collision.
     */
    public Steps composedWith() {
        return new Steps() {
            @Override
            public Optional<StepDeclaration> byId(String id) {
                Optional<StepDeclaration> module = installed.byId(id);
                Optional<StoredObject> introduced = Introductions.this.byId(id);
                if (module.isPresent() && introduced.isPresent()) {
                    throw new Collision(id, "a module installed here",
                            read(introduced.get()).introducer());
                }
                return module.or(() -> introduced.map(stored -> read(stored).step()));
            }

            @Override
            public Set<String> ids() {
                Set<String> ids = new TreeSet<>(installed.ids());
                all().forEach(introduced -> ids.add(introduced.step().id().toString()));
                return ids;
            }
        };
    }

    /** One id, two definitions — a collision, never an override. */
    public static final class Collision extends RuntimeException {
        Collision(String stepId, String holder, String claimant) {
            super("step '" + stepId + "' is already defined by " + holder
                    + ", and '" + claimant + "' brings a DIFFERENT definition — a step id "
                    + "is globally stable, so this is a collision rather than an override. "
                    + "(An identical declaration would have co-introduced: a fleet of "
                    + "parallel runners is not a conflict.)");
        }
    }

    private Optional<StoredObject> byId(String stepId) {
        return store.getByIdentifier(IntroductionModel.TYPE,
                List.of(IntroductionModel.key(stepId))).stream().findFirst();
    }

    // ------------------------------------------------------- the codec

    private static byte[] payload(StepDeclaration step, String introducer) {
        StringBuilder json = new StringBuilder(256)
                .append("{\"id\":").append(Json.quoted(step.id().toString()))
                .append(",\"version\":").append(Json.quoted(step.version()))
                .append(",\"introducer\":").append(Json.quoted(introducer));
        strings(json, "reads", new TreeSet<>(step.reads()));
        strings(json, "writes", new TreeSet<>(step.writes()));
        step.consumes().ifPresent(shape ->
                json.append(",\"consumes\":").append(Json.quoted(shape)));
        step.produces().ifPresent(shape ->
                json.append(",\"produces\":").append(Json.quoted(shape)));
        step.overridable().ifPresent(scope ->
                json.append(",\"overridable\":").append(Json.quoted(scope)));
        strings(json, "actions", new TreeSet<>(step.actions()));
        if (!step.slots().isEmpty()) {
            json.append(",\"slots\":{");
            boolean first = true;
            for (Map.Entry<String, String> slot : step.slots().entrySet()) {
                json.append(first ? "" : ",").append(Json.quoted(slot.getKey()))
                        .append(':').append(Json.quoted(slot.getValue()));
                first = false;
            }
            json.append('}');
        }
        strings(json, "milestones", step.milestones());
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void strings(StringBuilder json, String field, Iterable<String> values) {
        StringBuilder list = new StringBuilder();
        for (String value : values) {
            list.append(list.isEmpty() ? "" : ",").append(Json.quoted(value));
        }
        if (!list.isEmpty()) {
            json.append(",\"").append(field).append("\":[").append(list).append(']');
        }
    }

    private static Introduced read(StoredObject stored) {
        Map<?, ?> json = (Map<?, ?>) Json.parse(
                new String(stored.payload(), StandardCharsets.UTF_8));
        StepDeclaration step = new StepDeclaration(
                StepId.of(String.valueOf(json.get("id"))),
                String.valueOf(json.get("version")),
                Set.copyOf(strings(json, "reads")),
                Set.copyOf(strings(json, "writes")),
                optional(json, "consumes"), optional(json, "produces"),
                optional(json, "overridable"),
                Set.copyOf(strings(json, "actions")),
                slots(json),
                strings(json, "milestones"));
        return new Introduced(step, String.valueOf(json.get("introducer")));
    }

    private static List<String> strings(Map<?, ?> json, String field) {
        if (!(json.get(field) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private static Map<String, String> slots(Map<?, ?> json) {
        Map<String, String> slots = new LinkedHashMap<>();
        if (json.get("slots") instanceof Map<?, ?> declared) {
            declared.forEach((name, shape) ->
                    slots.put(String.valueOf(name), String.valueOf(shape)));
        }
        return slots;
    }

    private static Optional<String> optional(Map<?, ?> json, String field) {
        Object value = json.get(field);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }
}
