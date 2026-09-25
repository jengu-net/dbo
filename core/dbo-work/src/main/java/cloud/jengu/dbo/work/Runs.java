package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.UuidV7;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runs of one tenant's store.
 *
 * <p>Everything that advances a run goes through here, because every advance is
 * a version, a history link and a feed event: a caller holding a payload and
 * writing it itself would be free to write a heartbeat, and a heartbeat is a
 * feed event per tick for a fact nobody asked about.
 */
public final class Runs {

    private final ObjectStore store;
    private final Automations automations;
    private final Claimable claimable;

    /**
     * The step catalogue, for reporting through declared actions.
     * Empty means nothing is declared here and no verb is narrowed.
     */
    private final cloud.jengu.dbo.core.process.Steps steps;

    /**
     * Told when a run becomes claimable, so whoever is waiting for work need
     * not wait out its next look.
     *
     * <p><b>A nudge, never a handover.</b> What arrives is that something
     * changed, and the taker still polls and claims through the ordinary path
     * — because the claim race is what decides who gets the work, and a second
     * mechanism that decided it would be a second answer to the question the
     * run record already answers.
     *
     * <p>Told after the write has landed, so anybody woken by it finds the run
     * rather than racing the transaction that created it. Told on a best
     * effort: a listener that throws is not allowed to fail the write that
     * happened, because the work is real whether or not anyone was told, and
     * the taker's own poll is underneath this.
     *
     * <p><b>A listener reports its own failures.</b> This module carries the
     * core and nothing else, so there is no logger here to say that nobody was
     * told — and buying one for that line would be paid by every bundle that
     * embeds this. An implementation that can fail silently should not: it has
     * a logger where it lives.
     */
    @FunctionalInterface
    public interface Claimable {

        /** Nobody is waiting, which is the ordinary case and costs nothing. */
        Claimable NOBODY = run -> { };

        void appeared(Run run);
    }

    public Runs(ObjectStore store) {
        this(store, cloud.jengu.dbo.core.process.Steps.of());
    }

    /**
     * @param steps the declarations reports are checked against
     *              (REQ-DBO-PROC-REPORT-THROUGH-DECLARED-ACTIONS). Checked
     *              HERE, at the primitive, because the lane, the console and
     *              whatever comes next all pass through it — a check at any
     *              one caller leaves the others to grow a second copy of the
     *              rule, and two copies is how a rule forks.
     */
    public Runs(ObjectStore store, cloud.jengu.dbo.core.process.Steps steps) {
        this(store, steps, Claimable.NOBODY);
    }

    /**
     * The same, telling somebody when a run becomes claimable.
     *
     * @param claimable told after the write lands; see {@link Claimable}
     */
    public Runs(ObjectStore store, cloud.jengu.dbo.core.process.Steps steps,
            Claimable claimable) {
        this.claimable = claimable == null ? Claimable.NOBODY : claimable;
        this.store = store;
        this.steps = steps;
        // Built here rather than passed in, for the reason the note above
        // gives about steps: every claim goes through this primitive, and a
        // switch read at one caller leaves the others to grow a second copy
        // of the rule.
        this.automations = new Automations(store);
    }

    /**
     * A report verb the step does not declare, refused naming both sides
     *: the act, and what the step actually contains. A step that has
     * not declared actions is not narrowed — empty means "has not said",
     * never "admits nothing" — and an undeclared step is not narrowed
     * either, because a run may name a step nothing has declared yet.
     */
    public static final class NotAnAction extends RuntimeException {
        NotAnAction(String stepId, String action, java.util.Set<String> declared) {
            super("step '" + stepId + "' does not contain the action '" + action
                    + "'; it declares: " + declared);
        }
    }

    /**
     * An executor the step does not admit, refused naming both sides.
     *
     * <p>Half of "what a participant may claim is the intersection of its
     * scopes and what the step admits"; the other half — what the credential
     * holds — is the lane's, because only the host knows the credential.
     *
     * <p><b>An {@link IllegalStateException} because that is how a lane tells
     * a refusal from a fault</b> (REQ-DBO-PROC-REFUSED-IS-NOT-UNANSWERED).
     * This was a plain {@code RuntimeException}, which the verb surface does
     * not recognise, so it left as a 500 and reached the participant as <i>the
     * store did not answer</i> — a transient fault, about which the only
     * correct behaviour is to ask again. It is the opposite: settled, and
     * asking again is wrong. What made it worse than a misleading message is
     * that an offer comes off a feed whose cursor is acknowledged either way,
     * so a run refused this way was never offered to anybody again.
     */
    public static final class NotAdmitted extends IllegalStateException {
        NotAdmitted(String stepId, Executor by, String overridable) {
            super("step '" + stepId + "' does not admit an executor at scope "
                    + by.scope().wire() + " ('" + by.name() + "'); it is "
                    + (overridable == null
                            ? "not open to local execution at all (not overridable "
                                    + "is the default)"
                            : "open to " + overridable + " and wider"));
        }
    }

    /**
     * Whether this step admits an executor at that scope.
     *
     * <p>The baseline always may — it is not an override, it is the rule — and
     * anything more local may only where the step said so. A step cannot grant
     * its executor more than the executor already holds, so this narrows and
     * never widens.
     */
    private void requireAdmits(Run run, Executor by) {
        String stepId = run.process() + "." + run.step();
        steps.byId(stepId).ifPresent(declaration -> {
            String overridable = declaration.overridable().orElse(null);
            ScopeClass openTo = overridable == null ? null
                    : ScopeClass.valueOf(overridable.toUpperCase(java.util.Locale.ROOT));
            if (!StepGrant.of(run.process(), run.step()).overridableBy(openTo)
                    .admits(by.scope())) {
                throw new NotAdmitted(stepId, by, overridable);
            }
        });
    }

    private void requireAction(Run run, String action) {
        String stepId = run.process() + "." + run.step();
        steps.byId(stepId).ifPresent(declaration -> {
            if (!declaration.actions().isEmpty()
                    && !declaration.actions().contains(action)) {
                throw new NotAnAction(stepId, action, declaration.actions());
            }
        });
    }

    /**
     * A pipeline run: one per attempt at a body of work, closing when every
     * item is terminal.
     */
    public Run pipeline(String process, String step) {
        return pipeline(process, step, UuidV7.newId());
    }

    /** The same over declared storage domains. */
    public Run pipeline(String process, String step, String key, List<String> domains) {
        return byKey(key).orElseGet(() -> write(new State(key, process, step, RunKind.PIPELINE,
                Holder.AUTOMATION, null, null, null, Map.of(), null, List.copyOf(domains),
                null, Run.Produced.NOTHING, null)));
    }

    /**
     * A run of a <b>declared</b> step.
     *
     * <p>The run records the step's version alongside the executor's, because
     * reproducing last year's decision needs the definition as well as the
     * runner — and retrofitting that later means every run written before the
     * retrofit cannot be reproduced at all.
     *
     * <p>The domains come from the declaration rather than from the caller: a
     * step says what it reads and writes, and a run that claimed others would
     * be a second answer to the same question.
     */
    public Run of(cloud.jengu.dbo.core.process.StepDeclaration step, RunKind kind, String scope) {
        return of(step, kind, scope, Map.of());
    }

    /**
     * The same, with inputs filling the step's declared slots
     * (REQ-DBO-PROC-RUN-INPUTS-FILL-THE-SLOTS).
     *
     * <p>Fixed at creation — what the work is over is part of what the work
     * <em>is</em> — and refused at the door, both ways: a slot the step does
     * not declare, and a declared slot left unfilled. Every declared slot is
     * mandatory, because an input the step can do without is not a slot.
     * Half-fed work discovered at claim time, three steps from the cause, is
     * what checking here prevents.
     *
     * <p>The references are opaque to the engine, exactly like the item's:
     * resolution is the lane's act, by the party that legitimately holds the
     * objects, and shape validation is the face's, where a payload is at
     * hand.
     */
    public Run of(cloud.jengu.dbo.core.process.StepDeclaration step, RunKind kind, String scope,
            Map<String, String> inputs) {
        for (String slot : inputs.keySet()) {
            if (!step.slots().containsKey(slot)) {
                throw new IllegalArgumentException(step.id() + " declares no slot '" + slot
                        + "'; it takes: " + step.slots().keySet());
            }
        }
        for (String slot : step.slots().keySet()) {
            if (!inputs.containsKey(slot)) {
                throw new IllegalArgumentException(step.id() + ": slot '" + slot
                        + "' is unfilled — every declared slot is mandatory, because an "
                        + "input the step can do without is not a slot");
            }
        }
        // Kept in DECLARATION order regardless of how the caller's map
        // iterates — the projection renders slots in the step's order.
        Map<String, String> filled = new LinkedHashMap<>();
        step.slots().keySet().forEach(slot -> filled.put(slot, inputs.get(slot)));
        String key = step.id() + "/" + scope;
        return byKey(key).orElseGet(() -> write(new State(key, step.id().processId(),
                step.id().step(), kind, Holder.AUTOMATION, null, null, null, Map.of(), null,
                List.copyOf(step.writes()), null, Run.Produced.NOTHING, step.version(),
                java.util.Collections.unmodifiableMap(filled))));
    }

    /**
     * A pipeline run over something that already has a name — a delivery, an
     * import of a named file, a job somebody can point at.
     *
     * <p>Found rather than started, like a sweep, and for the same reason: the
     * work that calls this can be re-executed after a crash, and a second run
     * for the same attempt would double every count taken from it.
     */
    public Run pipeline(String process, String step, String key) {
        return pipeline(process, step, key, List.of());
    }

    /**
     * The sweep for this scope — found, not started, because the operator's
     * question is about the thing rather than about the pass
     * (REQ-DBO-PROC-RUN-KINDS). One durable run per (process, step, scope),
     * checkpointed.
     */
    public Run sweep(String process, String step, String scope) {
        return sweep(process, step, scope, List.of());
    }

    /**
     * The same over declared storage domains — what a face needs in order to
     * know whether this run is one it renders at all.
     */
    public Run sweep(String process, String step, String scope, List<String> domains) {
        String key = process + "/" + step + "/" + scope;
        return byKey(key).orElseGet(() -> write(new State(key, process, step, RunKind.SWEEP,
                Holder.AUTOMATION, null, null, null, Map.of(), null, List.copyOf(domains),
                null, Run.Produced.NOTHING, null)));
    }

    /** A pass over a sweep: what this round found, and what it therefore closes. */
    public Pass pass(Run sweep) {
        return new Pass(sweep);
    }

    /**
     * One <b>outcome somebody must see</b>, as a child run.
     *
     * <p><b>Children are exceptions, not an enumeration.</b> A run over forty
     * thousand concepts records a tally of forty thousand and three children,
     * because three of them need a person. One child per item processed would
     * make a large run forty thousand records, forty thousand feed events and a
     * history nobody can page through — every advance is a version, a history
     * link and a feed event, and that is the cost of being a record rather than
     * a row. What everything did is the tally's job; what somebody must act on
     * is a child's.
     *
     * <p>The failure class decides who holds it: a record that is wrong is a
     * person's, a store that was unavailable is a retry and nobody's card
     * (REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS).
     */
    public Run item(Run parent, String reference, Failure failure, String message) {
        return write(new State(UuidV7.newId(), parent.process(), parent.step(), parent.kind(),
                // A child inherits its parent's chain: correlation because the
                // caller tied this work together, trace because a step that
                // failed and its retry are one causal thread. A child minting
                // or dropping either would break the join at exactly the
                // moment somebody is reading the trace to find out why.
                failure.holder(), parent.key(), parent.correlation(), parent.trace(), Map.of(),
                new Run.Item(reference, failure, message), parent.domains(),
                parent.assignment(), Run.Produced.NOTHING, parent.stepVersion()));
    }

    /**
     * What resolution chose, recorded on the run.
     *
     * <p>All four facts, because each answers a different question later:
     * what ran, which behaviour that was, whose code it was, and under whose
     * declaration it was chosen.
     *
     * @param at where the work is happening — the chain's own tip, which is not
     *           necessarily the scope the executor was declared at
     */
    public Run selected(Run run, Scope at, Executor executor) {
        return selected(run, at, executor, null);
    }

    /**
     * The same, with what resolution refused on the way — an override a
     * narrower scope attempted against a step that does not allow one. The
     * step's own executor still ran; that somebody tried to displace it is a
     * fact about their rule, and a run is where it stays readable.
     */
    public Run selected(Run run, Scope at, Executor executor, String note) {
        return update(run, snapshot -> snapshot.withAssignment(new Run.Assignment(at, executor, note)));
    }

    /**
     * Nothing automated took it, so a person holds it — and the reason is part
     * of the record.
     *
     * <p>"Nobody automated this step yet", "this zone switched it off" and "a
     * narrower scope tried to override a step that is not overridable" are
     * three different facts, and the person holding the work is the one who
     * needs to know which they are looking at. Counted per step and per zone,
     * this is the automation backlog.
     */
    public Run fellThrough(Run run, Scope at, String reason) {
        Run recorded = update(run, snapshot -> snapshot.withAssignment(
                new Run.Assignment(at, null, reason)));
        return held(recorded, Holder.PERSON);
    }

    /**
     * Takes this run, or does not.
     *
     * <p><b>At-most-one actor needs no lease service.</b> The claim is a
     * conditional write against the version the claimant saw: two participants
     * racing one run produce one winner and one {@link
     * cloud.jengu.dbo.core.api.VersionConflictException}, and the loser takes
     * the next run rather than coordinating about this one.
     *
     * <p><b>This is also the dedup point.</b> Delivery is at-least-once, so a
     * participant will see the same run twice; a run already claimed and still
     * inside its deadline is refused, including to whoever claimed it. A
     * participant's own bookkeeping must not be the thing that saves it.
     *
     * <p><b>And this is where automation being switched off takes effect.</b>
     * Every claim taken here becomes {@link Holder#AUTOMATION}, so this is the
     * one place that can stop new automatic claims without stopping anything
     * else. A switch is read rather than a code path consulted: it is declared
     * configuration, and where it is read decides what it can promise.
     *
     * <p>What it cannot do from here is strand somebody. A run already held
     * carries its holder, its executor and its deadline on the run itself, and
     * nothing re-resolves a claim once taken — so a switch thrown while a
     * participant is working changes nothing about the work in its hands.
     * That is a property of where the check sits rather than a rule anybody
     * has to remember, which is the only kind worth promising.
     *
     * @param until when the claim lapses. A dead participant must not hold work
     *              for ever, and nothing but the clock is going to notice.
     * @return the claimed run, or empty when somebody else holds it — or when
     *         automation is switched off for this step where this executor
     *         stands, which is the same answer to the same question: not now
     */
    public Optional<Run> claim(Run seen, Executor by, java.time.Instant until) {
        refuseIfAuthoredElsewhere(seen, "claimed");
        requireAdmits(seen, by);
        Run current = byKey(seen.key()).orElse(null);
        if (current == null || current.claimed(java.time.Instant.now())) {
            return Optional.empty();
        }
        if (!automations.automated(current.process(), current.step(), by.scope())) {
            // Empty rather than a fault: the runner asks on every beat and
            // "not now" is a true answer to what it asked. Why not now is a
            // question about configuration, and it is answered where
            // configuration is read — the console names the scope the switch
            // was declared at.
            return Optional.empty();
        }
        State claimed = state(current).withAssignment(
                new Run.Assignment(current.assignment() == null ? null : current.assignment().at(),
                        by, null, until)).withHolder(Holder.AUTOMATION);
        try {
            store.put(new PutRequest(WorkModel.TYPE, current.id(), current.versionId(),
                    claimed.payload()));
        } catch (cloud.jengu.dbo.core.api.VersionConflictException lost) {
            // Somebody wrote between the read and the write, which for a claim
            // means somebody else took it. Not an error: it is the answer.
            return Optional.empty();
        }
        return byKey(seen.key());
    }

    /**
     * Progress, which is what extends a claim.
     *
     * <p>A checkpoint and never a heartbeat: a tick proves a process is alive,
     * and what a deadline is protecting against is a process that is alive and
     * getting nowhere. Counts are the evidence, and they are on the record
     * anyway.
     */
    public Run checkpoint(Run run, Map<String, Long> counts, java.time.Instant until) {
        Run tallied = counts.isEmpty() ? run : tally(run, counts);
        return update(tallied, snapshot -> snapshot.withAssignment(new Run.Assignment(
                snapshot.assignment() == null ? null : snapshot.assignment().at(),
                snapshot.assignment() == null ? null : snapshot.assignment().executor(),
                snapshot.assignment() == null ? null : snapshot.assignment().note(), until)));
    }

    /**
     * A checkpoint that also names the milestone reached
     * (REQ-DBO-PROC-PROGRESS-NAMES-THE-MILESTONE).
     *
     * <p>Same act, more said: it extends the claim exactly as counts do —
     * evidence, never a tick — and the run records the named point replaced,
     * never accumulated, surviving release and retake so the next taker
     * resumes from a fact.
     *
     * <p><b>Position is derived here, never asserted.</b> The executor
     * reports only the name; where the step declares its milestones the
     * store computes the position over that order, and a name outside the
     * declared order is refused naming both sides — a vocabulary forks by
     * silently accepting strangers. A step that has not declared any is not
     * narrowed: the name is recorded verbatim with no position, because a
     * completeness nobody declared cannot be derived, only invented.
     */
    public Run milestone(Run run, String name, Map<String, Long> counts,
            java.time.Instant until) {
        String stepId = run.process() + "." + run.step();
        Run.Milestone reached;
        Optional<cloud.jengu.dbo.core.process.StepDeclaration> declaration = steps.byId(stepId);
        if (declaration.isPresent() && !declaration.get().milestones().isEmpty()) {
            List<String> declared = declaration.get().milestones();
            int position = declared.indexOf(name);
            if (position < 0) {
                throw new NotAMilestone(stepId, name, declared);
            }
            reached = new Run.Milestone(name, position + 1, declared.size());
        } else {
            reached = new Run.Milestone(name, 0, 0);
        }
        Run tallied = counts.isEmpty() ? run : tally(run, counts);
        // The deadline moves; whose the work is does not. Read from the
        // snapshot rather than from the run this was called with, so an
        // advance that had to try again carries forward what the other writer
        // did instead of restoring what was true before them.
        return update(tallied, snapshot -> snapshot.withMilestone(reached)
                .withAssignment(new Run.Assignment(
                        snapshot.assignment() == null ? null : snapshot.assignment().at(),
                        snapshot.assignment() == null ? null : snapshot.assignment().executor(),
                        snapshot.assignment() == null ? null : snapshot.assignment().note(),
                        until)));
    }

    /** A point the step's own map does not contain, refused naming both sides. */
    public static final class NotAMilestone extends RuntimeException {
        NotAMilestone(String stepId, String name, List<String> declared) {
            super("step '" + stepId + "' declares no milestone '" + name
                    + "'; its order is: " + declared);
        }
    }

    /**
     * Hands back what a claim no longer holds.
     *
     * <p>Released, not done — the difference is the whole point of a deadline.
     * A run that says "done" because whoever held it stopped answering is the
     * failure this exists to prevent.
     */
    public Run released(Run run, String because) {
        return update(run, snapshot -> snapshot.withAssignment(new Run.Assignment(
                snapshot.assignment() == null ? null : snapshot.assignment().at(),
                null, because, null)));
    }

    /**
     * Claims that have lapsed, so somebody can take them again.
     *
     * <p>Walked a page at a time rather than selected whole. What is asked
     * for is every run an automation is holding and what comes back is the
     * few whose deadline has passed, so selecting them all held a tenant's
     * entire outstanding workload in memory to find a handful.
     */
    public List<Run> lapsed(java.time.Instant now) {
        return cloud.jengu.dbo.core.api.Answered.pagedBy(store::page,
                        Criteria.of(WorkModel.TYPE)
                                .eq("holder", EnvelopeValue.of(Holder.AUTOMATION.wire())))
                .map(Run::of)
                .filter(run -> run.assignment() != null && run.assignment().until() != null
                        && !run.assignment().until().isAfter(now))
                // A deadline is judged where the run lives. A mirror's
                // deadline is the other appliance's to notice, and this side's
                // clock reading it would release work whose checkpoint is
                // merely still in flight.
                .filter(run -> !WorkModel.authoredElsewhere(run.key()))
                .toList();
    }

    /**
     * How many versions a run names one by one before it keeps a high-water
     * mark instead.
     *
     * <p>Where a manifest meets "children are exceptions, not an enumeration":
     * naming every version of a forty-thousand-record run makes the record
     * itself unreadable, and naming none makes the run a summary of a change
     * rather than an account of it.
     */
    public static final int NAMED_VERSIONS = 200;

    /**
     * Records a version this run produced.
     *
     * <p>Called by whatever wrote it — the store cannot, because a store
     * writing into the work domain on every content write is the engine
     * re-entering itself.
     */
    public Run produced(Run run, String typeName, String id, long versionId) {
        // Accumulated from the snapshot, not from the run this was handed. An
        // advance that has to try again must add to what the other writer
        // produced; computed once outside, a retry would write this version
        // onto a list that no longer has theirs in it.
        return update(run, snapshot -> {
            Run.Produced before = snapshot.produced();
            List<String> versions = new ArrayList<>(before.versions());
            Map<String, Long> watermark = new LinkedHashMap<>(before.watermark());
            if (versions.size() < NAMED_VERSIONS) {
                versions.add(typeName + "/" + id + "/" + versionId);
            } else {
                watermark.merge(typeName, versionId, Math::max);
            }
            return snapshot.withProduced(new Run.Produced(List.copyOf(versions),
                    Map.copyOf(watermark), before.counted() + 1));
        });
    }

    /**
     * A run that succeeded: nothing is owed, and nobody holds it.
     *
     * <p>Closing is an act of judgment and goes through the step's declared
     * actions — a step whose actions omit {@code close} has said its
     * closure is somebody else's act (a human's, typically), and an
     * automated participant reporting done is refused by name. Releasing is
     * NEVER narrowed the same way: released-is-not-done is failure honesty,
     * and a step must not be able to refuse to hear that its executor
     * failed.
     */
    public Run closed(Run run) {
        requireAction(run, "close");
        return held(run, Holder.NOBODY);
    }

    /**
     * A closed run, deliberately open again
     * (REQ-DBO-PROC-CLOSED-CAN-BE-REOPENED).
     *
     * <p>Discovering a close was wrong must not require inventing a second
     * run to disagree with the first: the run itself becomes claimable again
     * — the released shape, a holder and no executor — with the reason on
     * the record. It goes through the step's declared {@code reopen} action,
     * which is what a supervisor's role will later narrow: reopening
     * is the judgment the action vocabulary exists for.
     */
    public Run reopen(Run run, String because) {
        requireAction(run, "reopen");
        return update(run, snapshot -> snapshot.withHolder(Holder.AUTOMATION).withAssignment(
                new Run.Assignment(
                        snapshot.assignment() == null ? null : snapshot.assignment().at(),
                        null, because, null)));
    }

    /** Moves a run to a holder — the only field anybody reads first. */
    public Run held(Run run, Holder holder) {
        return update(run, snapshot -> snapshot.withHolder(holder));
    }

    /**
     * A checkpoint: what this run has done so far, counted.
     *
     * <p>A count's name becomes an envelope path, so it is checked here rather
     * than at the write: a hyphen in a name is a mistake the caller made, and
     * finding out at the write means the whole pass is lost for it.
     */
    public Run tally(Run run, Map<String, Long> counts) {
        counts.keySet().forEach(name ->
                cloud.jengu.dbo.core.api.Paths.requireValid("tally_" + name));
        return update(run, snapshot -> snapshot.withTally(counts));
    }

    /**
     * The correlation this work was handed, echoed and never interpreted
     * (REQ-DBO-PROC-CORRELATION-TRAVELS-OPAQUE).
     */
    public Run correlated(Run run, String correlation) {
        return update(run, snapshot -> snapshot.withCorrelation(correlation));
    }

    /**
     * The trace context this work travels under, carried and never read
     * (REQ-DBO-PROC-TRACE-RIDES-THE-LANE).
     *
     * <p>A run claimed here and performed on an appliance is one causal chain
     * living in two processes, and nothing else can join it: the far end is a
     * process this store does not run, so no workflow engine can supply the
     * link and no counter can show it.
     *
     * <p>Carried, never minted. Where a caller already has a chain, a context
     * invented here would root a second one that looks authoritative and is
     * not — so a run nobody traced has none, and that is the honest answer
     * rather than a gap to fill.
     */
    public Run traced(Run run, String traceContext) {
        return update(run, snapshot -> snapshot.withTrace(traceContext));
    }

    /** A run by the store's id, which is what a feed event names. */
    public Optional<Run> byId(String id) {
        return store.get(WorkModel.TYPE, id).map(Run::of);
    }

    /** A run by its own key. */
    public Optional<Run> byKey(String key) {
        return store.getByIdentifier(WorkModel.TYPE, List.of(WorkModel.key(key)))
                .stream().findFirst().map(Run::of);
    }

    /** What is waiting for somebody of this kind — the list an operator opens. */
    public List<Run> holding(Holder holder) {
        return store.select(Criteria.of(WorkModel.TYPE)
                        .eq("holder", EnvelopeValue.of(holder.wire()))).stream()
                .map(Run::of).toList();
    }

    /**
     * The runs an operator is asking about: newest first, narrowed by whatever
     * they said and by nothing they did not.
     *
     * <p>Every filter is an envelope value, so this is a query rather than a
     * scan that reads payloads to decide — which also means it says nothing
     * about what any run was over (REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT).
     *
     * @param process null for any
     * @param step    null for any
     * @param holder  null for any
     * @param limit   how many, because a console that prints ten thousand rows
     *                has answered nothing
     */
    public List<Run> matching(String process, String step, Holder holder, int limit) {
        Criteria criteria = Criteria.of(WorkModel.TYPE).sortByLastUpdated(false).limit(limit);
        if (process != null) {
            criteria.eq("process", EnvelopeValue.of(process));
        }
        if (step != null) {
            criteria.eq("step", EnvelopeValue.of(step));
        }
        if (holder != null) {
            criteria.eq("holder", EnvelopeValue.of(holder.wire()));
        }
        return store.select(criteria).stream().map(Run::of).toList();
    }

    /**
     * The automation backlog: work waiting for a person at this step, here
     *. A number, per step and per zone, rather than an opinion about how
     * much is automated.
     */
    public long backlog(String process, String step, Scope at) {
        return store.count(Criteria.of(WorkModel.TYPE)
                .eq("process", EnvelopeValue.of(process))
                .eq("step", EnvelopeValue.of(step))
                .eq("scope", EnvelopeValue.of(at.wire()))
                .eq("holder", EnvelopeValue.of(Holder.PERSON.wire())));
    }

    /** The items of a run, open and closed. */
    public List<Run> items(Run parent) {
        return store.select(Criteria.of(WorkModel.TYPE)
                        .eq("parent", EnvelopeValue.of(parent.key()))).stream()
                .map(Run::of).toList();
    }

    /**
     * A pass over a sweep, closing by re-evaluation
     * (REQ-DBO-PROC-CLOSE-BY-RE-EVALUATION).
     *
     * <p>What this pass finds wrong stays open; what it does not find again is
     * closed by the pass itself. So a person fixes the world and the next round
     * closes their card — nobody clicks resolved on a fault that is still live.
     */
    public final class Pass {

        private final Run sweep;
        private final Map<String, Run> before = new LinkedHashMap<>();
        private final List<String> seen = new ArrayList<>();
        private final Map<String, Long> counts = new LinkedHashMap<>();

        private Pass(Run sweep) {
            this.sweep = sweep;
            for (Run item : items(sweep)) {
                if (item.item() != null && item.open()) {
                    before.put(item.item().reference(), item);
                }
            }
        }

        /**
         * This item is still wrong, or wrong for the first time.
         *
         * <p>Only what somebody must see: a pass over ten thousand things
         * counts ten thousand and names the handful that need acting on.
         */
        public Pass item(String reference, Failure failure, String message) {
            seen.add(reference);
            Run existing = before.get(reference);
            if (existing == null) {
                Runs.this.item(sweep, reference, failure, message);
            } else if (existing.holder() != failure.holder()) {
                held(existing, failure.holder());
            }
            return this;
        }

        /** A count worth keeping: read, applied, skipped, swept. */
        public Pass counted(String name, long count) {
            counts.put(name, count);
            return this;
        }

        /**
         * Ends the pass: anything not seen again is closed, and the sweep is
         * held by whoever the remaining items say holds it.
         */
        public Run done() {
            before.forEach((reference, item) -> {
                if (!seen.contains(reference)) {
                    closed(item);
                }
            });
            Run current = byKey(sweep.key()).orElse(sweep);
            if (!counts.isEmpty()) {
                current = tally(current, counts);
            }
            boolean anybodyWaiting = items(current).stream()
                    .anyMatch(item -> item.open() && item.needsAPerson());
            // Converged when nothing is left for a person. A sweep is never
            // "finished" — it is either agreeing with the world or not.
            return held(current, anybodyWaiting ? Holder.PERSON : Holder.NOBODY);
        }
    }

    /**
     * This run and its items, as the record a face renders.
     *
     * <p>Assembled here because a face never reaches for the store: rendering
     * takes the record as data, so whatever belongs to the run has to arrive
     * with it. Items are children; a subprocess or a continuation elsewhere is
     * a reference in the payload and is not gathered.
     */
    public cloud.jengu.dbo.core.face.RecordProjection.Record asRecord(Run run) {
        return new cloud.jengu.dbo.core.face.RecordProjection.Record(WorkModel.TYPE, run.id(),
                run.versionId(), payloadOf(run),
                items(run).stream().map(item ->
                        new cloud.jengu.dbo.core.face.RecordProjection.Record(WorkModel.TYPE,
                                item.id(), item.versionId(), payloadOf(item), item.domains()))
                        .toList(),
                run.domains());
    }

    private byte[] payloadOf(Run run) {
        return store.get(WorkModel.TYPE, run.id())
                .orElseThrow(() -> new IllegalStateException("run " + run.key() + " has gone"))
                .payload();
    }

    // ------------------------------------------------------------- writing

    private State state(Run run) {
        return new State(run.key(), run.process(), run.step(), run.kind(), run.holder(),
                run.parent(), run.correlation(), run.trace(), run.tally(), run.item(),
                run.domains(),
                run.assignment(), run.produced(), run.stepVersion(), run.inputs(),
                run.milestone());
    }

    /**
     * A run this appliance is advancing must be this appliance's own.
     *
     * <p>A mirror is a read-only account of somebody else's
     * work. Across two stores with a lagging lane, "the deadline passed" and
     * "the checkpoint is in flight" can both be true, and a peer acting on the
     * first would have the work done twice — so the side that authored a run
     * is the only side that advances it, and a deadline is judged only where
     * the run lives.
     *
     * <p>The consequence is accepted rather than hidden: an appliance that
     * dies holding work it authored keeps that work until it returns. Moving
     * it is an operator's deliberate act, not something a clock infers from a
     * lane that is merely behind.
     */
    private static void refuseIfAuthoredElsewhere(Run run, String what) {
        if (WorkModel.authoredElsewhere(run.key())) {
            throw new NotOurs(run.key(), what);
        }
    }

    /** A run belonging to another appliance, and the act that was refused. */
    public static final class NotOurs extends IllegalStateException {
        public NotOurs(String key, String what) {
            super("run '" + key + "' was authored by another appliance — it may be read "
                    + "here and not " + what + "; the side that authored it is the side "
                    + "that advances it");
        }
    }

    /**
     * Tells whoever is waiting, when this run is now there to be taken.
     *
     * <p>Here rather than at each verb that might produce one, because the two
     * places a run lands — the write and the advance — are the two places it
     * can become claimable, and a list of verbs to remember is a list somebody
     * eventually adds to without remembering.
     *
     * <p>Claimable means nobody is holding it: a run under an unexpired claim
     * has a taker already, and waking somebody for it would be inviting a race
     * that the claim exists to have settled.
     */
    private Run announced(Run run) {
        if (claimable == Claimable.NOBODY) {
            return run;
        }
        if (run.holder() != Holder.AUTOMATION || run.claimed(java.time.Instant.now())) {
            return run;
        }
        try {
            claimable.appeared(run);
        } catch (RuntimeException listenerFailed) {
            // The work happened whether or not anybody was told, and the
            // taker's own poll is underneath this — so a listener cannot fail
            // a write that already landed.
            //
            // Not logged HERE, and that is not an oversight: this module
            // carries the core and nothing else, and a logging dependency
            // bought for one line would be paid by every bundle that embeds
            // it. Saying so is the listener's job, where a logger already
            // exists — see the contract on Claimable.
            ignored(listenerFailed);
        }
        return run;
    }

    /** Named, so that swallowing this reads as a decision rather than a gap. */
    private static void ignored(RuntimeException listenersOwnProblem) {
        // deliberately nothing; see announced()
    }

    private Run write(State state) {
        if (WorkModel.authoredElsewhere(state.key())) {
            // A locally authored key that looked mirrored would be advanceable
            // nowhere and would collide with a real mirror on arrival. Refused
            // where it is cheap, rather than discovered on a lane.
            throw new IllegalArgumentException("'" + WorkModel.AUTHOR_SEPARATOR
                    + "' separates an appliance from a run's key and cannot appear in one: "
                    + state.key());
        }
        PutResult result = store.putIfAbsent(IdentityRef.identifier(WorkModel.KEY_SYSTEM,
                state.key()), PutRequest.create(WorkModel.TYPE, state.payload()));
        return announced(byKey(state.key()).orElseThrow(() -> new IllegalStateException(
                "a run was written as " + result.id() + " and cannot be read back")));
    }

    /**
     * How many times an advance re-reads and tries again before it gives up.
     *
     * <p>Small on purpose. A conflict here is two writers meeting, not a queue:
     * the appliance that authored a run is the only one that advances it, so
     * the other writer is a sweep of this appliance's own or a lane delivering
     * a mirrored version, and either resolves in the next read. A number large
     * enough to outlast real contention would be large enough to hold a
     * request open while something else is wrong.
     */
    private static final int ATTEMPTS = 4;

    /**
     * Advances a run, re-reading and re-applying if somebody wrote first.
     *
     * <p><b>The change is a function of what is there, not a payload computed
     * before the read.</b> That is the whole of why this can retry: a
     * pre-computed payload replayed onto a newer version silently discards
     * whatever the other writer did, so a retry would be a clobber wearing a
     * retry's clothes. Applied to the snapshot each time, the advance lands on
     * top of what happened meanwhile.
     *
     * <p>{@link #claim} does the same conditional write and catches its
     * conflict deliberately — losing a claim IS the answer. This did not catch
     * anything at all, and everything a pass does to its own record comes
     * through here, so a second writer at the wrong moment threw out of the
     * pass, past the loop that turns a refused item into a card, and out of
     * whatever door was asking. The work had happened; only the account of it
     * was lost, which is the worst way round.
     */
    private Run update(Run run, java.util.function.UnaryOperator<State> change) {
        refuseIfAuthoredElsewhere(run, "advanced");
        Run current = run;
        for (int attempt = 1; ; attempt++) {
            StoredObject stored = store.get(WorkModel.TYPE, current.id()).orElseThrow(
                    () -> new IllegalStateException("run " + run.key() + " has gone"));
            State state = change.apply(state(current));
            try {
                store.put(new PutRequest(WorkModel.TYPE, stored.id(), stored.versionId(),
                        state.payload()));
                return announced(byKey(state.key()).orElseThrow());
            } catch (cloud.jengu.dbo.core.api.VersionConflictException lost) {
                if (attempt == ATTEMPTS) {
                    throw new Contended(run.key(), lost);
                }
                // Read what they wrote, and apply this advance on top of it.
                current = byKey(run.key()).orElseThrow(
                        () -> new IllegalStateException("run " + run.key() + " has gone"));
            }
        }
    }

    /**
     * An advance that kept losing the race for its own record.
     *
     * <p>Its own type rather than the engine's conflict, because the two mean
     * different things to whoever catches it: a conflict says a write did not
     * land, and this says the WORK is fine and the account of it is not. A
     * caller holding a result somebody is waiting for should answer with the
     * result — a tally the store admits it could not keep is worth more than a
     * failure about an application that largely happened.
     */
    public static final class Contended extends IllegalStateException {
        public Contended(String key, Throwable cause) {
            super("run '" + key + "' was written by somebody else on every attempt to advance "
                    + "it; what it records is behind, and what it recorded about is not",
                    cause);
        }
    }

    /** The payload shape, in one place, so no caller authors a run by hand. */
    private record State(String key, String process, String step, RunKind kind, Holder holder,
            String parent, String correlation, String trace, Map<String, Long> tally, Run.Item item,
            List<String> domains, Run.Assignment assignment, Run.Produced produced,
            String stepVersion, Map<String, String> inputs, Run.Milestone milestone) {

        /** The pre-inputs shape — every run that fills no slots. */
        State(String key, String process, String step, RunKind kind, Holder holder,
                String parent, String correlation, String trace, Map<String, Long> tally, Run.Item item,
                List<String> domains, Run.Assignment assignment, Run.Produced produced,
                String stepVersion) {
            this(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, Map.of());
        }

        /** The pre-milestone shape. */
        State(String key, String process, String step, RunKind kind, Holder holder,
                String parent, String correlation, String trace, Map<String, Long> tally, Run.Item item,
                List<String> domains, Run.Assignment assignment, Run.Produced produced,
                String stepVersion, Map<String, String> inputs) {
            this(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, inputs, null);
        }

        State withHolder(Holder holder) {
            return new State(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, inputs, milestone);
        }

        State withTally(Map<String, Long> tally) {
            return new State(key, process, step, kind, holder, parent, correlation, trace,
                    Map.copyOf(tally), item, domains, assignment, produced, stepVersion,
                    inputs, milestone);
        }

        State withAssignment(Run.Assignment assignment) {
            return new State(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, inputs, milestone);
        }

        State withProduced(Run.Produced produced) {
            return new State(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, inputs, milestone);
        }

        State withTrace(String trace) {
            return new State(key, process, step, kind, holder, parent, correlation, trace,
                    tally, item, domains, assignment, produced, stepVersion, inputs, milestone);
        }

        State withCorrelation(String correlation) {
            return new State(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, inputs, milestone);
        }

        State withMilestone(Run.Milestone milestone) {
            return new State(key, process, step, kind, holder, parent, correlation, trace, tally, item,
                    domains, assignment, produced, stepVersion, inputs, milestone);
        }

        byte[] payload() {
            StringBuilder json = new StringBuilder(256)
                    .append("{\"key\":").append(Json.quoted(key))
                    .append(",\"process\":").append(Json.quoted(process))
                    .append(",\"step\":").append(Json.quoted(step))
                    .append(",\"kind\":").append(Json.quoted(kind.wire()))
                    .append(",\"holder\":").append(Json.quoted(holder.wire()));
            if (parent != null) {
                json.append(",\"parent\":").append(Json.quoted(parent));
            }
            if (correlation != null) {
                json.append(",\"correlation\":").append(Json.quoted(correlation));
            }
            if (trace != null) {
                json.append(",\"trace\":").append(Json.quoted(trace));
            }
            if (!tally.isEmpty()) {
                json.append(",\"tally\":{");
                boolean first = true;
                for (Map.Entry<String, Long> count : tally.entrySet()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append(Json.quoted(count.getKey())).append(':').append(count.getValue());
                }
                json.append('}');
            }
            if (assignment != null) {
                if (assignment.at() != null) {
                    json.append(",\"scope\":").append(Json.quoted(assignment.at().wire()));
                }
                if (assignment.executor() != null) {
                    Executor executor = assignment.executor();
                    json.append(",\"executor\":{\"name\":").append(Json.quoted(executor.name()))
                            .append(",\"version\":").append(Json.quoted(executor.version()))
                            .append(",\"provider\":").append(Json.quoted(executor.provider()))
                            .append(",\"scope\":").append(Json.quoted(executor.scope().wire()))
                            .append('}');
                }
                if (assignment.note() != null) {
                    json.append(",\"note\":").append(Json.quoted(assignment.note()));
                }
                if (assignment.until() != null) {
                    json.append(",\"until\":")
                            .append(Json.quoted(assignment.until().toString()));
                }
            }
            if (stepVersion != null) {
                json.append(",\"stepVersion\":").append(Json.quoted(stepVersion));
            }
            if (!inputs.isEmpty()) {
                json.append(",\"inputs\":{");
                boolean first = true;
                for (Map.Entry<String, String> slot : inputs.entrySet()) {
                    json.append(first ? "" : ",").append(Json.quoted(slot.getKey()))
                            .append(':').append(Json.quoted(slot.getValue()));
                    first = false;
                }
                json.append('}');
            }
            if (milestone != null) {
                json.append(",\"milestone\":{\"name\":").append(Json.quoted(milestone.name()))
                        .append(",\"position\":").append(milestone.position())
                        .append(",\"total\":").append(milestone.total()).append('}');
            }
            if (produced != null && produced.counted() > 0) {
                json.append(",\"produced\":{\"counted\":").append(produced.counted());
                if (!produced.versions().isEmpty()) {
                    json.append(",\"versions\":[");
                    for (int i = 0; i < produced.versions().size(); i++) {
                        json.append(i == 0 ? "" : ",")
                                .append(Json.quoted(produced.versions().get(i)));
                    }
                    json.append(']');
                }
                if (!produced.watermark().isEmpty()) {
                    json.append(",\"watermark\":{");
                    boolean first = true;
                    for (Map.Entry<String, Long> mark : produced.watermark().entrySet()) {
                        json.append(first ? "" : ",").append(Json.quoted(mark.getKey()))
                                .append(':').append(mark.getValue());
                        first = false;
                    }
                    json.append('}');
                }
                json.append('}');
            }
            if (!domains.isEmpty()) {
                json.append(",\"domains\":[");
                for (int i = 0; i < domains.size(); i++) {
                    json.append(i == 0 ? "" : ",").append(Json.quoted(domains.get(i)));
                }
                json.append(']');
            }
            if (item != null) {
                json.append(",\"item\":{\"reference\":").append(Json.quoted(item.reference()))
                        .append(",\"failure\":").append(Json.quoted(item.failure().wire()))
                        .append(",\"message\":").append(Json.quoted(item.message()))
                        .append('}');
            }
            return json.append(",\"at\":").append(Json.quoted(java.time.Instant.now().toString()))
                    .append('}').toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
