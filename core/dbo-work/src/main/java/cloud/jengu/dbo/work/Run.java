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
 *
 * <p><b>Three facts, kept apart</b>
 * (REQ-DBO-PROC-A-RUN-KEEPS-STATUS-CLAIMANT-AND-ELIGIBILITY-APART): where it
 * stands is {@code status}; who holds it is the {@link Assignment} — an
 * executor, or a person as their {@code PractitionerRole}; and who may take it
 * next is {@code automation} — whether a machine may as well as a person —
 * with {@code notBefore}, and {@code statusReason} saying why it stands so.
 * {@code attempts} counts the failures automation has had that its step's
 * {@code retry} said would pass. Who owes the next act is derived from these
 * ({@link #awaits}), never kept beside them.
 */
public record Run(String id, long versionId, String key, String process, String step,
        RunKind kind, String parent, String correlation, String trace,
        Map<String, Long> tally, Item item, java.util.List<String> domains,
        Assignment assignment, Produced produced, String stepVersion,
        Map<String, RunSlot> inputs, Milestone milestone, String requester,
        String refused, Status status, boolean automation, java.time.Instant notBefore,
        String statusReason, int attempts, cloud.jengu.dbo.core.process.RetryPolicy retry,
        Window window) {

    /** A run whose asker collects nothing. */
    public Run(String id, long versionId, String key, String process, String step,
            RunKind kind, String parent, String correlation, String trace,
            Map<String, Long> tally, Item item, java.util.List<String> domains,
            Assignment assignment, Produced produced, String stepVersion,
            Map<String, RunSlot> inputs, Milestone milestone, String requester,
            String refused, Status status, boolean automation, java.time.Instant notBefore,
            String statusReason, int attempts, cloud.jengu.dbo.core.process.RetryPolicy retry) {
        this(id, versionId, key, process, step, kind, parent, correlation, trace, tally, item,
                domains, assignment, produced, stepVersion, inputs, milestone, requester, refused,
                status, automation, notBefore, statusReason, attempts, retry, null);
    }

    /**
     * How long the run's requester may collect what it was given and what it
     * produced, once its work is over.
     *
     * <p>A time beside the run rather than a state of it. The run is over when
     * its result is written and nobody owes it anything: collecting is
     * optional, and a result nobody collects is not work left undone. So the
     * window is read by comparing the clock with {@code until}, and closing it
     * needs no transition and no sweep.
     *
     * @param collect how long after the result is written, as the step
     *                declared it when the run was authored
     * @param until   when the window shuts, or null while the result is not
     *                written — set as the run completes, and moved to now when
     *                the requester says it is done collecting
     */
    public record Window(java.time.Duration collect, java.time.Instant until) {}

    /**
     * Whether the client named may collect from this run now: the run's
     * requester, its work over, inside the window its step declared.
     */
    public boolean collectableBy(String client, java.time.Instant now) {
        return client != null && client.equals(requester) && !open() && window != null
                && window.until() != null && now.isBefore(window.until());
    }

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
        return new Run(null, 0, key, null, null, null, null, null, null,
                Map.of(), null, java.util.List.of(), null, Produced.NOTHING, null,
                Map.of(), null, null, null, null, false, null, null, 0, null, null);
    }

    /**
     * The client that asked for this run at the tenant's step door, or null
     * for a run authored any other way — on the records surface, by a lane,
     * in process, for the fleet.
     *
     * <p>Recorded because it is who the run answers. The application that
     * asked for work learns how it ended and what it produced from the run's
     * own address, and a credential that may act in work is refused the
     * records surface by design — so without this, nothing the asker holds
     * would let it read the answer, and nothing the store holds would say the
     * answer was its to read.
     */
    public String requester() {
        return requester;
    }

    /**
     * Why the tenant would not commit what this run's step answered with, or
     * null for a run whose result nobody refused.
     *
     * <p>A run that carries this has ENDED — nobody holds it and nothing is
     * owed — and that is the difference from a failure. A step that crashed
     * or ran out of time is released and taken again, because another
     * attempt may well succeed; a result the tenant refused would be refused
     * again, word for word, so retrying it is a loop with a log line. The
     * reason is the tenant's own, and it is what the application that asked
     * for the work is told.
     */
    public String refused() {
        return refused;
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
     * @param claimant the client whose credential holds the run, as the
     *                 authority read it: the one that started it at the step
     *                 door until a lane takes it, then the one that claimed it
     *                 there — or null when no client holds it, because the
     *                 run was authored or claimed in process, or released.
     *                 Beside the executor rather than read off it, because the
     *                 executor is what performs, and a host holding a lane for
     *                 a participant names the participant and not itself
     * @param role     the {@code PractitionerRole} a person claimed the run as,
     *                 as {@code PractitionerRole/<id>}, or null when what holds
     *                 it is not a person. Beside the executor rather than
     *                 instead of it, because a person is not a device and a
     *                 run that named one as the other would say so wrongly
     * @param hold     which claim this is: minted by the store when the claim
     *                 lands, carried by every advance that leaves the claim
     *                 standing, and cleared with it. Neither the executor nor
     *                 the claimant can say this, because two replicas of one
     *                 executor share both — they are the same name and
     *                 version on the same credential — and each would pass
     *                 the other's holder check. The claim can, because each
     *                 claim lands once. Null when nothing is claimed, and for
     *                 a run its starter holds at the step door, which nobody
     *                 claimed
     */
    public record Assignment(Scope at, Executor executor, String note, java.time.Instant until,
            String claimant, String role, String hold) {

        /** An assignment that is not a claim, or carries none forward. */
        public Assignment(Scope at, Executor executor, String note, java.time.Instant until,
                String claimant, String role) {
            this(at, executor, note, until, claimant, role, null);
        }

        /** An executor's assignment, or nobody's: no person holds it. */
        public Assignment(Scope at, Executor executor, String note, java.time.Instant until,
                String claimant) {
            this(at, executor, note, until, claimant, null);
        }

        /** An assignment no client is recorded as holding. */
        public Assignment(Scope at, Executor executor, String note, java.time.Instant until) {
            this(at, executor, note, until, null);
        }

        /** An assignment nothing is holding to a deadline. */
        public Assignment(Scope at, Executor executor, String note) {
            this(at, executor, note, null);
        }
    }

    /**
     * Whether the client named holds this run right now, and so may read its
     * context and end it.
     *
     * <p>Held means open, recorded as that client's, and — for a claim taken
     * on a lane — inside its deadline: a lapsed claim nobody has released yet
     * is held by nobody, whatever it still says. A hold taken at the step door
     * carries no deadline, because the starter performing the work inline is
     * not a participant that can die mid-claim unnoticed; it ends with the run,
     * or when a lane takes the run over.
     */
    public boolean heldBy(String client, java.time.Instant now) {
        return client != null && open() && assignment != null
                && client.equals(assignment.claimant())
                && (assignment.until() == null || assignment.until().isAfter(now));
    }

    /**
     * Whether this executor holds the run under some claim, as the run itself
     * says — what a read through the executor's lane is judged by.
     *
     * <p>A read is authorised by the credential, and two replicas of one
     * executor share it: one reading the run the other now holds learns
     * nothing its credential does not already reach. What either of them
     * <em>says</em> about the run is judged by the claim, {@link
     * #heldUnder(Executor, String)}.
     *
     * <p>Held means open, claimed by that executor, and under a deadline,
     * which a claim always writes and every hand-back clears. It is judged
     * from what is written and never from the clock: past the deadline the
     * tenant's housekeeping may hand the run back and another participant may
     * take it, and either is a write that ends this claim.
     */
    public boolean heldBy(Executor executor) {
        return executor != null && open() && assignment != null
                && executor.equals(assignment.executor()) && assignment.until() != null;
    }

    /**
     * Whether the run still stands under the claim named, taken by the
     * executor named — the test every act a holder takes on it is put to.
     *
     * <p>The claim and not the executor, because the executor does not tell
     * a fleet's replicas apart: one replica whose claim housekeeping handed
     * back, and another of the same executor that then took the run, carry
     * the same name, version and credential. Each claim lands once, so the
     * hold the store minted when it landed names it, and the replica holding
     * an older one is refused whatever it is called.
     *
     * <p>Judged from what is written and never from the clock, as {@link
     * #heldBy(Executor)} is: until somebody acts on the run, its holder is the
     * only one who can finish its work.
     *
     * @param executor what took it, or null for a claim a person took, which
     *                 names a role and no device
     * @param hold     the claim, as the run handed back by the claim carries
     *                 it ({@link #hold()})
     */
    public boolean heldUnder(Executor executor, String hold) {
        return hold != null && open() && assignment != null && assignment.until() != null
                && hold.equals(assignment.hold())
                && java.util.Objects.equals(executor, assignment.executor());
    }

    /**
     * The claim this run stands under, as the store minted it when the claim
     * landed, or null when nothing has claimed it.
     *
     * <p>What a holder carries back on every verb it says: the run the claim
     * handed it, or any run a verb handed back since, names it.
     */
    public String hold() {
        return assignment == null ? null : assignment.hold();
    }

    /**
     * Whether automation may take this run now: open, open to automation as
     * well as to people, nobody holding it, and its not-before passed.
     *
     * <p>A person may take any open run, so this is the only eligibility
     * question there is.
     */
    public boolean forAutomation(java.time.Instant now) {
        return open() && automation && !claimed(now)
                && (notBefore == null || !notBefore.isAfter(now));
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
        return status != null && !status.over();
    }

    /**
     * Which failures of this run automation is given again — its step's
     * declared retry, recorded when the run was authored so a step declared
     * anywhere is held to it — or null for none.
     */
    public cloud.jengu.dbo.core.process.RetryPolicy retry() {
        return retry;
    }

    /**
     * Who owes this run's next act, as it stands now
     * (REQ-DBO-PROC-WHO-OWES-THE-NEXT-ACT-IS-DERIVED).
     */
    public Awaits awaits(java.time.Instant now) {
        if (!open()) {
            return Awaits.NOTHING;
        }
        if (status == Status.IN_PROGRESS && claimed(now)) {
            return Awaits.OWNER;
        }
        return automation ? Awaits.MACHINE : Awaits.PERSON;
    }

    /** Whether a person holds this run: claimed as a {@code PractitionerRole}. */
    public boolean heldByAPerson() {
        return status == Status.IN_PROGRESS && assignment != null && assignment.role() != null;
    }

    /**
     * Whether this is work waiting for a human rather than for a machine or a
     * clock: ready, and open to people alone.
     */
    public boolean needsAPerson() {
        return status == Status.READY && !automation;
    }

    /**
     * A run, read from what the store holds.
     *
     * <p>Public because the vocabulary a product asks about work with lives
     * in its own module, and a question answering {@code Stream<Run>} has to
     * turn the store's rows into runs somewhere. It was package-private while
     * reading one was this package's own business, and the module boundary is
     * what made that no longer true — which is the honest reason rather than a
     * general widening: nobody else has a reason to call it, and anybody who
     * does is holding a stored object out of the work domain already.
     */
    public static Run of(StoredObject stored) {
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
        Map<String, RunSlot> inputs = new LinkedHashMap<>();
        if (((Map<?, ?>) json).get("inputs") instanceof Map<?, ?> slots) {
            slots.forEach((slot, filled) -> inputs.put(slot.toString(), slotOf(filled)));
        }
        Milestone milestone = null;
        if (((Map<?, ?>) json).get("milestone") instanceof Map<?, ?> raw) {
            milestone = new Milestone(str(raw, "name"),
                    raw.get("position") instanceof Number position ? position.intValue() : 0,
                    raw.get("total") instanceof Number total ? total.intValue() : 0);
        }
        Assignment assignment = assignment(json);
        Status status = Status.of(optional(json, "status"));
        if (status == null) {
            status = Status.READY;
        }
        boolean automation = !(((Map<?, ?>) json).get("performerType")
                instanceof java.util.List<?> eligible) || eligible.contains("automation");
        Object notBefore = ((Map<?, ?>) json).get("notBefore");
        Object attempts = ((Map<?, ?>) json).get("attempts");
        return new Run(stored.id(), stored.versionId(), Json.str(json, "key"),
                Json.str(json, "process"), Json.str(json, "step"),
                RunKind.of(Json.str(json, "kind")),
                optional(json, "parent"), optional(json, "correlation"),
                optional(json, "trace"),
                Map.copyOf(tally), item, java.util.List.copyOf(domains), assignment,
                produced(json), optional(json, "stepVersion"),
                java.util.Collections.unmodifiableMap(inputs), milestone,
                optional(json, "requester"), optional(json, "refused"), status, automation,
                notBefore == null ? null : java.time.Instant.parse(notBefore.toString()),
                optional(json, "statusReason"),
                attempts instanceof Number count ? count.intValue() : 0, retry(json),
                window(json));
    }

    /** The asker's window, as the run recorded it, or null where it has none. */
    private static Window window(Object json) {
        Object collect = ((Map<?, ?>) json).get("collect");
        if (collect == null) {
            return null;
        }
        Object until = ((Map<?, ?>) json).get("collectUntil");
        return new Window(java.time.Duration.parse(collect.toString()),
                until == null ? null : java.time.Instant.parse(until.toString()));
    }

    /**
     * One slot, read back from what the run recorded.
     *
     * <p>No marker is needed and none is written: a slot is homogeneous, so a
     * string can only be a reference and an object can only be one given with
     * the run. A list is several of whichever its members are.
     *
     * <p>A bare string is also every run authored before slots could be
     * anything else, which is why that case reads as one reference rather than
     * being refused.
     */
    private static RunSlot slotOf(Object filled) {
        if (filled instanceof java.util.List<?> several) {
            if (several.isEmpty()) {
                throw new IllegalArgumentException("a slot recorded with no values");
            }
            java.util.List<String> values = new java.util.ArrayList<>();
            boolean referred = !(several.get(0) instanceof Map<?, ?>);
            for (Object one : several) {
                if (one instanceof Map<?, ?> object) {
                    if (referred) {
                        throw new IllegalArgumentException("a slot mixes references and objects, "
                                + "and no declaration can say that: a slot is Reference(T)[] or "
                                + "T[], never both");
                    }
                    values.add(Json.render(object));
                } else {
                    if (!referred) {
                        throw new IllegalArgumentException("a slot mixes objects and references, "
                                + "and no declaration can say that: a slot is Reference(T)[] or "
                                + "T[], never both");
                    }
                    values.add(one.toString());
                }
            }
            return new RunSlot(referred ? RunSlot.Kind.REFERRED : RunSlot.Kind.GIVEN,
                    values, true);
        }
        if (filled instanceof Map<?, ?> object) {
            return RunSlot.given(Json.render(object));
        }
        return RunSlot.referring(filled.toString());
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

    /** The step's retry, as the run recorded it when it was authored. */
    private static cloud.jengu.dbo.core.process.RetryPolicy retry(Object json) {
        if (!(((Map<?, ?>) json).get("retry") instanceof Map<?, ?> raw)) {
            return null;
        }
        java.util.List<String> on = new java.util.ArrayList<>();
        if (raw.get("on") instanceof java.util.List<?> faults) {
            faults.forEach(fault -> on.add(fault.toString()));
        }
        return new cloud.jengu.dbo.core.process.RetryPolicy(on, str(raw, "after"),
                raw.get("attempts") instanceof Number count ? count.intValue() : 1);
    }

    private static Assignment assignment(Object json) {
        Object at = ((Map<?, ?>) json).get("scope");
        Object note = ((Map<?, ?>) json).get("note");
        Executor executor = null;
        if (((Map<?, ?>) json).get("executor") instanceof Map<?, ?> raw) {
            executor = new Executor(str(raw, "name"), str(raw, "version"), str(raw, "provider"),
                    Scope.of(str(raw, "scope")));
        }
        Object claimant = ((Map<?, ?>) json).get("claimant");
        Object role = ((Map<?, ?>) json).get("role");
        Object hold = ((Map<?, ?>) json).get("hold");
        if (at == null && executor == null && note == null && claimant == null && role == null
                && hold == null && ((Map<?, ?>) json).get("until") == null) {
            return null;
        }
        Object until = ((Map<?, ?>) json).get("until");
        return new Assignment(Scope.of(at == null ? null : at.toString()), executor,
                note == null ? null : note.toString(),
                until == null ? null : java.time.Instant.parse(until.toString()),
                claimant == null ? null : claimant.toString(),
                role == null ? null : role.toString(),
                hold == null ? null : hold.toString());
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
