package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One run, as it stands (#46).
 *
 * <p>A read-only view over the stored record: advancing a run is
 * {@link Runs}' business, because every advance is a version, a history link
 * and a feed event — checkpoints, never a heartbeat.
 */
public record Run(String id, long versionId, String key, String process, String step,
        RunKind kind, Holder holder, String parent, String correlation,
        Map<String, Long> tally, Item item, java.util.List<String> domains) {

    /**
     * The storage domains this run's work concerned — {@code r4}, {@code
     * identity}, a config domain. What a step will declare once steps are
     * declared; stamped on the run meanwhile, because it is what decides
     * whether a face renders this run at all.
     */
    public java.util.List<String> domains() {
        return domains;
    }

    /**
     * One thing a run was over, when the run <em>is</em> that thing: item work
     * lives in child runs rather than in a growing parent, because every update
     * to a parent would rewrite it, and because a person fixes one thing at a
     * time — a single card saying "two problems" cannot be half done.
     */
    public record Item(String reference, Failure failure, String message) {}

    /** Whether anybody is owed anything. */
    public boolean open() {
        return holder != Holder.NOBODY;
    }

    /** Whether this is work waiting for a human rather than for a clock. */
    public boolean needsAPerson() {
        return holder == Holder.PERSON;
    }

    static Run of(StoredObject stored) {
        Object json = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        Map<String, Long> tally = new LinkedHashMap<>();
        Object counts = ((Map<?, ?>) json).get("tally");
        if (counts instanceof Map<?, ?> map) {
            map.forEach((name, value) -> {
                if (value instanceof Number count) {
                    tally.put(name.toString(), count.longValue());
                }
            });
        }
        Item item = null;
        if (((Map<?, ?>) json).get("item") instanceof Map<?, ?> raw) {
            item = new Item(str(raw, "reference"),
                    raw.get("failure") == null ? null
                            : Failure.valueOf(str(raw, "failure").toUpperCase(java.util.Locale.ROOT)),
                    str(raw, "message"));
        }
        java.util.List<String> domains = new java.util.ArrayList<>();
        if (((Map<?, ?>) json).get("domains") instanceof java.util.List<?> declared) {
            declared.forEach(domain -> domains.add(domain.toString()));
        }
        return new Run(stored.id(), stored.versionId(), Json.str(json, "key"),
                Json.str(json, "process"), Json.str(json, "step"),
                RunKind.of(Json.str(json, "kind")), Holder.of(Json.str(json, "holder")),
                optional(json, "parent"), optional(json, "correlation"),
                Map.copyOf(tally), item, java.util.List.copyOf(domains));
    }

    private static String optional(Object json, String field) {
        Object value = ((Map<?, ?>) json).get(field);
        return value == null ? null : value.toString();
    }

    private static String str(Map<?, ?> raw, String field) {
        Object value = raw.get(field);
        return value == null ? null : value.toString();
    }

    /** The correlation this run was given, if it was given one. */
    public Optional<String> correlated() {
        return Optional.ofNullable(correlation);
    }
}
