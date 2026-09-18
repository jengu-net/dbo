package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.Trackable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A lane with no store behind it, for proving a {@link StepService}.
 *
 * <p>Writing a step service is two methods. <b>Proving one</b> meant standing
 * up a store, or hand-building a {@link Run}, its inputs and a
 * {@link Work.Progress} — so every implementor carried its own scaffolding and
 * each was slightly different. That matters more than the convenience,
 * because of what the contract asks and what none of it can be checked
 * without a lane: that a failing step is <b>released with its reason</b> and
 * never closed, that a checkpoint may name the milestone reached, and that a
 * step arriving twice is safe, since a lapsed claim is re-offered.
 *
 * <p><b>This is a lane, not a second runner.</b> The semantics being proven
 * live in {@link StepRunner} — releasing on a throw, threading the reported
 * run through each checkpoint so a milestone is not erased by a later report,
 * closing on the tally. A harness that called {@code perform} itself and
 * judged the result would encode one reading of the contract, pass, and say
 * nothing about whether the runner agrees. So a test drives the <b>real</b>
 * runner over this, and what is asserted is what the runner did.
 *
 * <p>Usage:
 * <pre>{@code
 * ProvingLane lane = ProvingLane.offering(service.step())
 *         .with("device", "Device", json)
 *         .lane();
 * new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
 *         .register(service).attach(lane).cycle();
 *
 * assertEquals(ProvingLane.Ended.CLOSED, lane.ended());
 * assertEquals(List.of("parsed", "written"), lane.milestones());
 * }</pre>
 *
 * <p>Not thread-safe, and deliberately: a proof that needed locking to be read
 * would be proving the harness.
 */
public final class ProvingLane implements Lane {

    /** How the runner finished with the offered run. */
    public enum Ended {

        /** Not yet — nothing claimed it, or nothing has cycled. */
        NOT_YET,

        /** Done, with whatever tally the service returned. */
        CLOSED,

        /** Failed or threw: released with a reason, which is not done. */
        RELEASED
    }

    private final String stepId;
    private final Map<String, StoredObject> inputs;
    /**
     * Whoever asked to be told to look again. Concurrent because the runner
     * subscribes on its own thread while a test fires from its own — the rest
     * of this fixture is deliberately not thread-safe, and this one field has
     * to be, because there is no moment when only one thread holds it.
     */
    private final List<Runnable> woken = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final boolean wakeable;
    private final List<String> milestones = new ArrayList<>();
    private final List<Map<String, Long>> checkpoints = new ArrayList<>();
    private final List<Ended> endings = new ArrayList<>();

    /**
     * How many times the runner has looked. Concurrent for the reason
     * {@link #woken} is: a started runner polls from its own thread while the
     * test watches from another, and a test that has to guess whether the
     * runner is asleep yet is a test that passes on timing.
     */
    private final java.util.concurrent.atomic.AtomicInteger polls =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile Run offered;
    private Ended ended = Ended.NOT_YET;
    private String reason;
    private int performed;

    private ProvingLane(String stepId, Map<String, StoredObject> inputs, boolean wakeable,
            boolean quiet) {
        this.stepId = stepId;
        this.inputs = Map.copyOf(inputs);
        this.wakeable = wakeable;
        this.offered = quiet ? null : runFor(stepId);
    }

    /**
     * Work for the step a service declares — named with the service's own
     * {@code step()}, so the process and the step are split the way the
     * runner splits them rather than the way a caller guesses. A run whose
     * halves are split differently polls perfectly and matches nothing.
     */
    public static Offer offering(String stepId) {
        return new Offer(stepId);
    }

    /** Collects the inputs the work arrives with. */
    public static final class Offer {

        private final String stepId;
        private final Map<String, StoredObject> inputs = new LinkedHashMap<>();
        private boolean wakeable;
        private boolean quiet;

        private Offer(String stepId) {
            this.stepId = stepId;
        }

        /**
         * The lane starts with nothing to take, until
         * {@link ProvingLane#workAppears()}.
         *
         * <p>For proving what a runner does once it is already running: work
         * offered at construction is taken on the first cycle, before the
         * runner has waited for anything, so a runner that never looked again
         * would pass a test that offered it up front.
         */
        public Offer notYet() {
            this.quiet = true;
            return this;
        }

        /**
         * The lane will offer a wake-up, which nothing fires but
         * {@link ProvingLane#saysItHasWork()}.
         *
         * <p>Which makes the interesting case the one where a test never calls
         * it: a binding that can say it has work and, this time, did not. That
         * is what a dropped notification looks like from the runner's side,
         * and it must cost latency and nothing else.
         */
        public Offer wakeable() {
            this.wakeable = true;
            return this;
        }

        /** One input, as the work will carry it: a name, a type and its bytes. */
        public Offer with(String name, String typeName, String payload) {
            inputs.put(name, new StoredObject(java.util.UUID.randomUUID().toString(), typeName,
                    1, Instant.now(), payload.getBytes(StandardCharsets.UTF_8), false,
                    null, null, null));
            return this;
        }

        public ProvingLane lane() {
            return new ProvingLane(stepId, inputs, wakeable, quiet);
        }
    }

    // ---------------------------------------------------------- what happened

    /** How the runner finished with it. */
    public Ended ended() {
        return ended;
    }

    /** Why it was released, in the service's own words, or null. */
    public String reason() {
        return reason;
    }

    /** The milestones named, in the order they were reported. */
    public List<String> milestones() {
        return List.copyOf(milestones);
    }

    /** Every checkpoint's counts, including the closing tally. */
    public List<Map<String, Long>> checkpoints() {
        return List.copyOf(checkpoints);
    }

    /** How many times the service has performed this work. */
    public int performed() {
        return performed;
    }

    /**
     * How many times the runner has looked — its polls, woken or on its tick.
     *
     * <p>For the tests that have to know the runner is asleep before they do
     * something, rather than sleeping and hoping.
     */
    public int polls() {
        return polls.get();
    }

    /**
     * Work appears, with nobody told. For a lane built {@link Offer#notYet()},
     * which starts with nothing to take.
     */
    public ProvingLane workAppears() {
        this.offered = runFor(stepId);
        return this;
    }

    /** How each performance ended, oldest first — one entry per re-offer. */
    public List<Ended> endings() {
        return List.copyOf(endings);
    }

    /**
     * Offers the same work again, as a lapsed claim would.
     *
     * <p>This is the property an implementor most needs and least can write:
     * delivery is at-least-once, so a step arrives twice, and an
     * implementation that is not idempotent looks identical to one that is
     * until something goes wrong in production. Re-offered rather than
     * performed twice — the run goes back unclaimed and the runner takes it
     * the way it would take any re-offered work, so what is exercised is the
     * claim path rather than a second call somebody arranged.
     */
    public ProvingLane offerAgain() {
        this.offered = runFor(stepId);
        this.ended = Ended.NOT_YET;
        this.reason = null;
        return this;
    }

    // ------------------------------------------------------------- the lane

    @Override
    public String tenant() {
        return "proving";
    }

    @Override
    public Executor identity() {
        return new Executor("proving-lane", "1.0", "cloud.jengu.dbo", Scope.BASELINE);
    }

    /**
     * Nothing, unless the offer asked to be {@link Offer#wakeable()}. A proof
     * drives the runner a cycle at a time and asserts what it did; a wake-up
     * arriving on its own would make when it looked part of what is being
     * proven, which is the one thing these fixtures are built not to depend
     * on. So this lane says it has work only when told to.
     */
    @Override
    public Optional<Wakeups> wakeups() {
        return wakeable ? Optional.of(this::listen) : Optional.empty();
    }

    private AutoCloseable listen(Runnable toWake) {
        woken.add(toWake);
        return () -> woken.remove(toWake);
    }

    /**
     * Says there is work, as the store's end of a binding that can reach back
     * would. Carries nothing, because a wake-up carries nothing.
     *
     * <p>Not calling this is the other half of the same property, and the half
     * worth proving: the runner looks anyway, on its tick.
     */
    public ProvingLane saysItHasWork() {
        woken.forEach(Runnable::run);
        return this;
    }

    @Override
    public List<Run> poll(Set<String> steps, int limit) {
        polls.incrementAndGet();
        Run now = offered;
        return now != null && steps.contains(now.step()) ? List.of(now) : List.of();
    }

    @Override
    public Optional<Run> claim(Run run, Duration holdFor) {
        if (offered == null) {
            return Optional.empty();
        }
        Run claimed = held(offered, identity());
        offered = null;
        return Optional.of(claimed);
    }

    @Override
    public Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor) {
        checkpoints.add(Map.copyOf(counts));
        return run;
    }

    @Override
    public Run milestone(Run run, String milestone, Map<String, Long> counts, Duration holdFor) {
        milestones.add(milestone);
        checkpoints.add(Map.copyOf(counts));
        return run;
    }

    @Override
    public void released(Run run, String because) {
        this.ended = Ended.RELEASED;
        this.reason = because;
        this.performed++;
        endings.add(Ended.RELEASED);
    }

    @Override
    public void closed(Run run) {
        this.ended = Ended.CLOSED;
        this.performed++;
        endings.add(Ended.CLOSED);
    }

    @Override
    public void closed(Run run, String head) {
        closed(run);
    }

    @Override
    public Map<String, StoredObject> inputs(Run run) {
        return inputs;
    }

    @Override
    public int releaseLapsed() {
        return 0;
    }

    // --- Everything below belongs to a lane with a store behind it. ---------
    //
    // Refused rather than quietly answered: a proof that read an empty list
    // from one of these would be proving that this lane returns nothing, and
    // a service reaching for one here is a service that will reach for it in
    // production, where the answer matters.

    @Override
    public void reopen(Run run, String because) {
        throw new UnsupportedOperationException(notHere("reopening a run"));
    }

    @Override
    public void declare(Declarations.Declared declared) {
        throw new UnsupportedOperationException(notHere("declaring a participant"));
    }

    @Override
    public void introduce(cloud.jengu.dbo.core.process.StepDeclaration step) {
        // A service may bring its own declaration, and the runner introduces
        // it on attach. Accepted silently because refusing would make a
        // perfectly ordinary service unprovable here.
    }

    @Override
    public void withdraw(Declarations.Declared declared) {
        // As introduce: the runner does this on detach, and it is not the
        // service's behaviour under test.
    }

    @Override
    public void routes(List<Trackable> behind) {
        throw new UnsupportedOperationException(notHere("routing for an edge"));
    }

    @Override
    public cloud.jengu.dbo.work.SealedWork sealed(Run run) {
        throw new UnsupportedOperationException(notHere("sealed work"));
    }

    @Override
    public cloud.jengu.dbo.work.SealedWork sealed(Run run, List<String> recipients) {
        throw new UnsupportedOperationException(notHere("sealed work"));
    }

    @Override
    public String opened(Run run, String reference, cloud.jengu.dbo.work.RunChain.Link link) {
        throw new UnsupportedOperationException(notHere("opening a sealed input"));
    }

    private static String notHere(String what) {
        return what + " needs a store behind the lane; this one has none. A service that "
                + "reaches for it is proving something this cannot answer — drive it over a "
                + "real lane instead.";
    }

    private static Run runFor(String stepId) {
        int dot = stepId.lastIndexOf('.');
        if (dot <= 0 || dot == stepId.length() - 1) {
            throw new IllegalArgumentException("a step id is <module>.<process>.<step>, and the "
                    + "run is built by splitting it the way the runner does: " + stepId);
        }
        return new Run(java.util.UUID.randomUUID().toString(), 1,
                stepId + "/proving", stepId.substring(0, dot), stepId.substring(dot + 1),
                RunKind.PIPELINE, Holder.NOBODY, null, null, null,
                Map.of(), null, List.of(), null, Run.Produced.NOTHING, null, Map.of(), null);
    }

    /** The run as a claim leaves it: held by automation, assigned to whoever took it. */
    private static Run held(Run run, Executor by) {
        return new Run(run.id(), run.versionId(), run.key(), run.process(), run.step(),
                run.kind(), Holder.AUTOMATION, run.parent(), run.correlation(), run.trace(),
                run.tally(), run.item(), run.domains(),
                new Run.Assignment(Scope.BASELINE, by, null,
                        Instant.now().plus(Duration.ofMinutes(5))),
                run.produced(), run.stepVersion(), run.inputs(), run.milestone());
    }
}
