package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One run, as it stands.
 *
 * <p>A read-only view over the stored record: advancing a run is
 * {@link Runs}' business, because every advance is a version, a history link
 * and a feed event — checkpoints, never a heartbeat.
 */
public record Run(String id, long versionId, String key, String process, String step,
        RunKind kind, Holder holder, String parent, String correlation, String trace,
        Map<String, Long> tally, Item item, java.util.List<String> domains,
        Assignment assignment, Produced produced, String stepVersion,
        Map<String, String> inputs, Milestone milestone) {

    /**
     * A run named only by its key, for a verb whose lane reads the store's
     * own copy of it — what a supervisor sends when it says which run to act
     * on.
     *
     * <p>Every other field is left to the store deliberately. A body that
     * carried them would be the asker describing a record it does not hold,
     * and a lane that believed the description would let a credential bounded
     * to one step reach another's work by naming it wrongly.
     */
    public static Run named(String key) {
        return new Run(null, 0, key, null, null, null, null, null, null, null,
                Map.of(), null, java.util.List.of(), null, Produced.NOTHING, null,
                Map.of(), null);
    }

    /**
     * Where a long run is, in the step's own words — replaced on each
     * report, never accumulated, and kept across release and retake so the
     * next taker resumes from a fact.
     *
     * @param name     the declared point reached, asserted by the executor
     * @param position 1-based position over the step's declared order,
     *                 derived by the store — or 0 when the step declared no
     *                 milestones, because a completeness nobody declared
     *                 cannot be derived, only invented
     * @param total    the declared order's size, or 0 with position
     */
    public record Milestone(String name, int position, int total) {}

    /**
     * What this run changed.
     *
     * <p>A run that names the versions it produced is a complete account of a
     * change, and reading runs in order reads the changes in order — which is
     * what lets another appliance ask for exactly what it is missing instead of
     * comparing two stores.
     *
     * <p><b>Bounded, because a manifest is an enumeration and children are
     * exceptions.</b> A run over forty thousand records cannot name forty
     * thousand versions: up to a cap it names them one by one, and past it it
     * keeps a per-type high-water mark and the count. The far side then reads
     * the named ones directly and the rest by cursor — less precise, and the
     * alternative was a record nobody can page through.
     *
     * @param versions  {@code Type/id/version}, in the order they were produced
     * @param watermark the highest version this run produced per type, once it
     *                  stopped naming them individually
     * @param counted   how many versions it produced in total, named or not
     */
    public record Produced(java.util.List<String> versions, Map<String, Long> watermark,
            long counted) {

        public static final Produced NOTHING =
                new Produced(java.util.List.of(), Map.of(), 0);

        /** Whether this run named everything it produced. */
        public boolean complete() {
            return counted == versions.size();
        }
    }

    /**
     * Who was chosen to run this and where — or, when nobody was, why the work
     * is in front of a person.
     *
     * <p>Recorded rather than derivable: a provider can be withdrawn and a
     * scope can be re-declared, so a resolution nobody wrote down is a decision
     * nobody can reproduce a year later.
     *
     * @param at       where the work happened, which is what makes the
     *                 fall-through countable per zone rather than in total
     * @param executor what took it, or null when nothing did
     * @param note     what resolution has to say: why nothing took it, or what
     *                 it refused on the way to the one that did — a refused
     *                 override is a fact about somebody's rule and does not
     *                 stop being one because the step's own executor ran
     * @param until    how long the claim holds, or null when nothing is
     *                 claimed. A deadline rather than a lease service: a
     *                 participant that dies mid-claim must not hold work for
     *                 ever, and the only thing that can be relied on to notice
     *                 is the clock
     */
    public record Assignment(Scope at, Executor executor, String note, java.time.Instant until) {

        /** An assignment nothing is holding to a deadline. */
        public Assignment(Scope at, Executor executor, String note) {
            this(at, executor, note, null);
        }
    }

    /** Whether somebody is holding this run right now, rather than for ever. */
    public boolean claimed(java.time.Instant now) {
        return assignment != null && assignment.until() != null
                && assignment.until().isAfter(now);
    }

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

    /**
     * The version of the step declaration this run ran under, or null for
     * a run whose step nobody has declared yet.
     *
     * <p>Beside the executor's version rather than instead of it: reproducing a
     * decision needs the definition and the runner, and a run that names only
     * one of them explains half of what happened.
     */
    public String stepVersion() {
        return stepVersion;
    }

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
        // Slot order is declaration order and the projection renders it, so
        // the copy keeps it — Map.copyOf would forget.
        Map<String, String> inputs = new LinkedHashMap<>();
        if (((Map<?, ?>) json).get("inputs") instanceof Map<?, ?> slots) {
            slots.forEach((slot, reference) ->
                    inputs.put(slot.toString(), reference.toString()));
        }
        Milestone milestone = null;
        if (((Map<?, ?>) json).get("milestone") instanceof Map<?, ?> raw) {
            milestone = new Milestone(str(raw, "name"),
                    raw.get("position") instanceof Number position ? position.intValue() : 0,
                    raw.get("total") instanceof Number total ? total.intValue() : 0);
        }
        return new Run(stored.id(), stored.versionId(), Json.str(json, "key"),
                Json.str(json, "process"), Json.str(json, "step"),
                RunKind.of(Json.str(json, "kind")), Holder.of(Json.str(json, "holder")),
                optional(json, "parent"), optional(json, "correlation"),
                optional(json, "trace"),
                Map.copyOf(tally), item, java.util.List.copyOf(domains), assignment(json),
                produced(json), optional(json, "stepVersion"),
                java.util.Collections.unmodifiableMap(inputs), milestone);
    }

    @SuppressWarnings("unchecked")
    private static Produced produced(Object json) {
        if (!(((Map<?, ?>) json).get("produced") instanceof Map<?, ?> raw)) {
            return Produced.NOTHING;
        }
        java.util.List<String> versions = new java.util.ArrayList<>();
        if (raw.get("versions") instanceof java.util.List<?> named) {
            named.forEach(version -> versions.add(version.toString()));
        }
        Map<String, Long> watermark = new LinkedHashMap<>();
        if (raw.get("watermark") instanceof Map<?, ?> marks) {
            marks.forEach((type, version) -> {
                if (version instanceof Number number) {
                    watermark.put(type.toString(), number.longValue());
                }
            });
        }
        long counted = raw.get("counted") instanceof Number number ? number.longValue()
                : versions.size();
        return new Produced(java.util.List.copyOf(versions), Map.copyOf(watermark), counted);
    }

    private static Assignment assignment(Object json) {
        Object at = ((Map<?, ?>) json).get("scope");
        Object note = ((Map<?, ?>) json).get("note");
        Executor executor = null;
        if (((Map<?, ?>) json).get("executor") instanceof Map<?, ?> raw) {
            executor = new Executor(str(raw, "name"), str(raw, "version"), str(raw, "provider"),
                    Scope.of(str(raw, "scope")));
        }
        if (at == null && executor == null && note == null
                && ((Map<?, ?>) json).get("until") == null) {
            return null;
        }
        Object until = ((Map<?, ?>) json).get("until");
        return new Assignment(Scope.of(at == null ? null : at.toString()), executor,
                note == null ? null : note.toString(),
                until == null ? null : java.time.Instant.parse(until.toString()));
    }

    /** Whether this run is in front of a person because nothing automated took it. */
    public boolean fellThrough() {
        return assignment != null && assignment.executor() == null
                && assignment.note() != null;
    }

    private static String optional(Object json, String field) {
        Object value = ((Map<?, ?>) json).get(field);
        return value == null ? null : value.toString();
    }

    private static String str(Map<?, ?> raw, String field) {
        Object value = raw.get(field);
        return value == null ? null : value.toString();
    }

    /**
     * The trace context this run travels under, if it was given one.
     *
     * <p>Opaque, and carried rather than minted. A store that minted a root
     * whenever it saw none would detach every run from the chain its caller
     * already had — two disconnected traces where there was one, and the
     * second looking authoritative. Absence is the honest answer for work
     * nobody traced.
     *
     * <p>Never a metric dimension. It is an identifier, so it belongs on a
     * span's own id fields; putting it in a label would give every run its own
     * time series, which is the same reason a run's key and a foreign
     * correlation are absent from the telemetry labels.
     */
    public java.util.Optional<String> traceContext() {
        return java.util.Optional.ofNullable(trace);
    }

    /** The correlation this run was given, if it was given one. */
    public Optional<String> correlated() {
        return Optional.ofNullable(correlation);
    }
}
