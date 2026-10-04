package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The embeddable participant: declare, pull, claim, checkpoint, report,
 * release.
 *
 * <p>Every place that does work needs the same three things — pull, claim,
 * report — and none of them should be written twice. A hospital integrating
 * with dbo embeds this, not a FHIR client plus a webhook plus a queue.
 *
 * <p><b>It automates nothing.</b> Its job is to carry work to wherever the work
 * is actually done — another system, an edge with no orchestrator, a screen with
 * a person at it — and to carry the result back. Automation is a {@link
 * Handler} somebody supplies; the runner ships with none and works, which is
 * "manual is the baseline; automation is an attachment" made literal.
 * Automating a step later changes which participant claims it and nothing else.
 *
 * <p><b>Two layers, owning different failures.</b> Whatever runs the work
 * locally owns local durability — resuming its own half-finished work after a
 * restart. This owns the global truth: what is owed, by whom, and what
 * happened. With only the first, work is durable and invisible to everybody
 * else; with only the second, a crashed runner loses its half of it. The
 * contract line falls between them, which is why no orchestrator is named here.
 */
public final class Runner {

    /**
     * Where the work is actually done.
     *
     * <p>A call out to another system, a DBOS workflow, a synchronous function,
     * or a person opening a screen — the runner does not know and must not.
     */
    @FunctionalInterface
    public interface Handler {

        /**
         * @throws Exception whatever went wrong; the class decides whose problem
         *                   it is (REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS)
         */
        Outcome handle(Run run) throws Exception;
    }

    /**
     * What came back.
     *
     * @param tally    what was done, counted — the run's account of itself
     * @param problems the things somebody must see; everything else is the
     *                 tally's business, because children are exceptions rather
     *                 than an enumeration
     * @param finished whether this run is over. A handler that carried work to a
     *                 person's screen says no: the work is out, and it comes back
     *                 when they are done with it
     */
    public record Outcome(Map<String, Long> tally, List<Problem> problems, boolean finished) {

        /** Nothing to report but that it is done. */
        public static Outcome done(Map<String, Long> tally) {
            return new Outcome(tally, List.of(), true);
        }

        /** Handed on, and not finished here. */
        public static Outcome handedOn() {
            return new Outcome(Map.of(), List.of(), false);
        }
    }

    /** One thing that needs somebody, named the way an item names it. */
    public record Problem(String reference, Failure failure, String message) {}

    private final Runs runs;
    private final Participation participation;
    private final Declarations declarations;
    private final Declarations.Declared self;
    private final Duration hold;

    /**
     * @param self  what this participant announces itself as
     * @param hold  how long it claims work for. Long enough to do it, short
     *              enough that this participant's death is noticed.
     */
    public Runner(Runs runs, ChangeFeed feed, Declarations declarations,
            Declarations.Declared self, Duration hold) {
        this.runs = runs;
        this.declarations = declarations;
        this.self = self;
        this.hold = hold;
        this.participation = new Participation(runs, feed, self.consumer(),
                Set.of(self.step()), self.executor());
    }

    /** Announces itself, so resolution can see it. Idempotent. */
    public Runner declare() {
        declarations.declare(self);
        return this;
    }

    /**
     * One round: take what this participant can, do it wherever it is done, and
     * say what happened.
     *
     * @return the runs it finished or handed on
     */
    public List<Run> runOnce(int limit, Handler handler) {
        List<Run> handled = new ArrayList<>();
        for (Run available : participation.poll(limit)) {
            Optional<Run> claimed = participation.claim(available, hold);
            if (claimed.isEmpty()) {
                // Somebody else got it. Take the next one rather than
                // coordinate about this one.
                continue;
            }
            try {
                handled.add(report(claimed.get(), handler));
            } catch (Runs.NotHeld lost) {
                // Somebody acted on the run before this participant finished
                // it — housekeeping handed a lapsed claim back, another
                // participant took it — and nothing was said in its name.
                // Neither done nor failed here: the work is theirs now.
                continue;
            }
        }
        return List.copyOf(handled);
    }

    /**
     * A workplace: a person opened this run, and holds it now as the role
     * they hold here.
     *
     * <p>The same claim a service makes, for the same reason — two people
     * opening one piece of work is the case a lease exists for, and a person is
     * no more entitled to hold it twice than a process is. What the run then
     * says holds it is the person, by their role: it was this participant's
     * executor, so a person at a screen was recorded as a device running
     * automation, and a list of what waits for people could not see that
     * somebody had it.
     *
     * @param role the {@code PractitionerRole} the person holds, as
     *             {@code PractitionerRole/<id>}
     */
    public Optional<Run> open(String key, String role) {
        return runs.byKey(key).flatMap(run -> runs.claimAsPerson(run, role, hold, null));
    }

    /**
     * Progress, which extends the claim.
     *
     * @param run the run as its claim, or the last verb said about it, handed
     *            it back
     * @throws Runs.NotHeld when the run no longer stands under that claim
     */
    public Run checkpoint(Run run, Map<String, Long> counts) {
        return runs.checkpoint(run, holder(run), counts, hold);
    }

    /**
     * What happened, on the record — the same records dbo would have written
     * had it done the work itself.
     *
     * <p>Said as the run's holder, under the claim {@code claimed} carries,
     * as every verb a holder says is. The account goes first, so a claim
     * somebody else ended since is refused before this participant names a
     * card on work that is no longer its own.
     *
     * @param claimed the run as its claim, or the last verb said about it,
     *                handed it back
     * @throws Runs.NotHeld when the run no longer stands under that claim;
     *                      nothing more is written in its name
     */
    public Run report(Run claimed, Outcome outcome) {
        Executor holder = holder(claimed);
        Run current = outcome.tally().isEmpty() && outcome.problems().isEmpty() ? claimed
                : runs.checkpoint(claimed, holder, outcome.tally(), hold);
        for (Problem problem : outcome.problems()) {
            runs.item(current, problem.reference(), problem.failure(), problem.message());
        }
        if (!outcome.finished()) {
            // Out with somebody, and still this participant's to hold: the
            // claim keeps running, and the deadline is what notices if they
            // never come back.
            return runs.byKey(current.key()).orElse(current);
        }
        boolean anybodyWaiting = runs.items(current).stream()
                .anyMatch(item -> item.open() && item.needsAPerson());
        // The run this participant holds, never the store's copy read back:
        // the claim is read off it, and the store's copy names whoever holds
        // the run now.
        return anybodyWaiting
                ? runs.forPeople(current, holder, "an outcome of this work needs somebody")
                : runs.closed(current, holder);
    }

    /**
     * Hands work back deliberately, rather than by dying and being noticed.
     *
     * @throws Runs.NotHeld when the run no longer stands under the claim
     *                      {@code claimed} carries; nothing is written
     */
    public Run giveBack(Run claimed, String why) {
        return runs.released(claimed, holder(claimed), why, null);
    }

    /**
     * Who the run's claim names: this participant's executor, or nobody for a
     * person who opened it here — a person is not a device, and the claim
     * they hold names their role.
     */
    private Executor holder(Run claimed) {
        return claimed.heldByAPerson() ? null : self.executor();
    }

    /** How far behind this participant is. */
    public long lag() {
        return participation.lag();
    }

    private Run report(Run claimed, Handler handler) {
        Outcome outcome;
        try {
            outcome = handler.handle(claimed);
        } catch (Exception failed) {
            // The class and the step's declared retry decide where it goes:
            // a record that is wrong ends it, a fault the step said will pass
            // goes back to automation later, and anything else to a person.
            // Released first, so a claim somebody else ended since is refused
            // before a card is named in this participant's name.
            Failure failure = Failure.of(failed);
            Run released = runs.released(claimed, holder(claimed),
                    "the work failed: " + failed.getMessage(), failure);
            runs.item(released, self.name(), failure, String.valueOf(failed.getMessage()));
            return runs.byKey(released.key()).orElse(released);
        }
        // Outside the handler's failure: a claim lost while reporting is not
        // the work failing, and must not be released as though it were.
        return report(claimed, outcome);
    }
}
