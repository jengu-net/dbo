package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.telemetry.Label;
import cloud.jengu.dbo.telemetry.Labels;
import cloud.jengu.dbo.telemetry.Telemetry;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The reference runner: step services in, consumption out.
 *
 * <p>Registering a {@link StepService} starts everything the participation
 * doctrine demands, written once: declared as a candidate on every lane, work
 * pulled and claimed, performed, checkpointed, reported — and a heartbeat on
 * every lane each cycle, carrying the runner's counts per step under
 * {@code dbo.runner} beside whatever {@link HeartbeatStatistics} contribute,
 * so a node hears the worker even when it has nothing to ask for.
 *
 * <p><b>A declaration is said when it changes, not as a sign of life.</b> It
 * is a record in the tenant's store, and re-writing it every cycle was a
 * write per step per tenant per tick to say "still here" — which the
 * heartbeat now says without writing anything. One that did not land is said
 * again next cycle.
 *
 * <p><b>Stateless over tenants</b>: tenants arrive as {@link Lane}s and the
 * runner holds only the task in hand and the documents the task names. Its
 * counters are its own soft accounting — reconstructible from nothing,
 * carried in heartbeats, lost without loss.
 *
 * <p><b>No state here may span lanes.</b> A lane is the unit of everything
 * this runner does, and anything keyed more coarsely mixes tenants: the
 * counters were once keyed by step alone, so one runner serving three tenants
 * published a total of all three into each of their stores, and the last
 * failure message from one tenant's work was written into the others'. The
 * rule is not about counters — it is about the next thing somebody keys by
 * step because a lane felt like an implementation detail.
 *
 * <p>One loop thread, deliberately: a runner scales by being <b>deployed</b>
 * more — a pod per step, replicas up — never by relaxing the claim; a pool
 * inside one runner is the first step of the coordination the claim exists
 * to make unnecessary. The claim race is the scheduler.
 */
public final class StepRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StepRunner.class);

    private final Duration holdFor;
    private final Duration pollEvery;
    /**
     * Where the numbers go, beside where they are declared.
     *
     * <p>The heartbeat's counts are for the node that hears this worker,
     * for the tenant whose work they describe; these are the same events
     * aggregated for whoever runs the fleet. Both, because they answer different questions
     * and one is not a worse copy of the other — and this one is lossy, so
     * nothing decides anything on it.
     */
    private final Telemetry telemetry;
    private final Map<String, StepService> services = new ConcurrentHashMap<>();
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    /**
     * Soft accounting, <b>per lane</b> and then per step.
     *
     * <p>The nesting is the invariant above made structural: there is no key
     * under which a number from two lanes could meet. Keyed by the same thing
     * {@link #lanes} is, so a lane going away takes its accounting with it
     * rather than leaving a count a re-attached lane would inherit.
     */
    private final Map<String, Map<String, Counts>> counts = new ConcurrentHashMap<>();
    /**
     * What each lane was last told about each step, so a declaration is said
     * again only when it changed or did not land. Keyed per lane, like
     * everything here.
     */
    private final Map<String, Map<String, Declarations.Declared>> declaredOn =
            new ConcurrentHashMap<>();
    /** Whoever adds to a heartbeat, beside the runner's own counts. */
    private final List<HeartbeatStatistics> contributors =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The namespace the runner's own counts travel under, which no contributor may use. */
    public static final String RUNNER_STATISTICS = "dbo.runner";
    /**
     * What a lane's wake-ups are listened to through, per lane, so detaching
     * one stops its listening — keyed like everything else here, because a
     * subscription outliving its lane would wake a runner on behalf of a
     * tenant it no longer serves.
     */
    private final Map<String, AutoCloseable> listening = new ConcurrentHashMap<>();
    /**
     * The loop's sleep, endable.
     *
     * <p>One permit counter for every lane together, not one per lane: the
     * cycle sweeps them all, so being told twice and being told by two tenants
     * are the same instruction — look again. Permits are drained after the
     * wait for that reason, or a burst of six wake-ups would buy six immediate
     * cycles over the same empty lanes.
     */
    private final java.util.concurrent.Semaphore woken =
            new java.util.concurrent.Semaphore(0);
    private volatile Thread loop;
    private volatile boolean running;
    /** The steps the loop waits for before it asks any lane for work; empty waits for none. */
    private volatile java.util.Set<String> awaited = java.util.Set.of();

    public StepRunner(Duration holdFor, Duration pollEvery) {
        this(holdFor, pollEvery, Telemetry.installed());
    }

    /** The same, reporting somewhere a caller chose — a test, or an embedder. */
    public StepRunner(Duration holdFor, Duration pollEvery, Telemetry telemetry) {
        this.holdFor = holdFor;
        this.pollEvery = pollEvery;
        this.telemetry = telemetry == null ? Telemetry.none() : telemetry;
    }

    /**
     * Registers a service. Two services claiming one step id in one runner is
     * a collision, refused — the same rule the catalogue applies to modules.
     */
    public synchronized StepRunner register(StepService service) {
        StepService present = services.putIfAbsent(service.step(), service);
        if (present != null && present != service) {
            throw new IllegalStateException("two services claim the step '" + service.step()
                    + "' in one runner — a collision, not an override, exactly as two "
                    + "modules declaring one id would be");
        }
        lanes.values().forEach(lane -> declare(lane, service.step()));
        LOG.info("step service registered: step={}", service.step());
        return this;
    }

    /** The whiteboard's other half: the declaration is withdrawn everywhere. */
    public synchronized void unregister(String step) {
        if (services.remove(step) != null) {
            lanes.values().forEach(lane -> lane.withdraw(declared(lane, step)));
            declaredOn.values().forEach(said -> said.remove(step));
            LOG.info("step service withdrawn: step={}", step);
        }
    }

    /** A tenant arriving. Every registered service is declared on it. */
    public synchronized StepRunner attach(Lane lane) {
        lanes.put(lane.tenant(), lane);
        services.keySet().forEach(step -> declare(lane, step));
        // A lane that can say when it has work is listened to; one that
        // cannot is not, and the loop then waits out its tick for it exactly
        // as it always did. A failure to subscribe is logged and not fatal
        // for the same reason: the poll is underneath this, so the worst it
        // costs is the latency the runner had before.
        lane.wakeups().ifPresent(wakeups -> {
            try {
                listening.put(lane.tenant(), wakeups.wake(woken::release));
            } catch (RuntimeException notListening) {
                LOG.warn("lane attached without wake-ups, falling back to the poll: "
                        + "tenant={} {}", lane.tenant(), notListening.getMessage());
            }
        });
        LOG.info("lane attached: tenant={}", lane.tenant());
        return this;
    }

    /** A tenant going away — its declarations with it. */
    public synchronized void detach(String tenant) {
        Lane lane = lanes.remove(tenant);
        if (lane != null) {
            services.keySet().forEach(step -> lane.withdraw(declared(lane, step)));
            counts.remove(tenant);
            declaredOn.remove(tenant);
            stopListening(tenant);
            LOG.info("lane detached: tenant={}", tenant);
        }
    }

    private void stopListening(String tenant) {
        AutoCloseable subscription = listening.remove(tenant);
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception stopping) {
            LOG.warn("a lane's wake-ups did not stop: tenant={} {}",
                    tenant, stopping.getMessage());
        }
    }

    /**
     * Adds what a contributor says to every heartbeat from here on.
     *
     * @throws IllegalArgumentException naming the contributor, for a blank
     *         namespace or one starting {@code dbo.}, which is the store's
     */
    public StepRunner contributing(HeartbeatStatistics contributor) {
        String namespace = contributor.namespace();
        if (namespace == null || namespace.isBlank() || namespace.startsWith("dbo.")) {
            throw new IllegalArgumentException(contributor.getClass().getName()
                    + " contributes heartbeat statistics under '" + namespace + "'; a "
                    + "contributor names a namespace of its own, and 'dbo.' is the store's");
        }
        contributors.add(contributor);
        return this;
    }

    /** Stops asking a contributor. */
    public void withdrawing(HeartbeatStatistics contributor) {
        contributors.remove(contributor);
    }

    /**
     * The steps this runner is about to hold, which the loop waits for before
     * it asks any lane for work.
     *
     * <p>A poll is a read of the participant's feed, acked as it is read, and
     * it is narrowed to the steps held at that moment — so a lane polled while
     * the runner holds three of the seven steps it is about to is a cursor moved
     * past the work of the other four, which is then never offered. That is the
     * ordinary shape of a host whose lanes arrive before its services: a
     * container that opens its lanes as it starts, and an application that
     * registers its steps once the container is up. A host that knows its
     * steps says so here, and its first poll asks for all of them.
     *
     * <p>Only the loop waits. {@link #cycle()} is the caller's to drive, and a
     * caller driving it has decided for itself when to ask.
     */
    public StepRunner awaiting(java.util.Collection<String> steps) {
        this.awaited = java.util.Set.copyOf(steps);
        return this;
    }

    /** Whether every step this runner was told to wait for is held. */
    private boolean holdsWhatItAwaits() {
        return services.keySet().containsAll(awaited);
    }

    /** Starts the loop. Registering and attaching while running is fine. */
    public synchronized StepRunner start() {
        if (running) {
            return this;
        }
        running = true;
        loop = Thread.ofPlatform().name("dbo-step-runner").daemon(true).start(this::run);
        return this;
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread current = loop;
        if (current != null) {
            current.interrupt();
        }
    }

    /**
     * One cycle over every lane: housekeeping, poll, claim, perform, report,
     * any declaration that has not landed, and a heartbeat. Public so a test — or a host scheduling for itself — drives the
     * runner without the thread; the loop is this in a sleep.
     */
    public int cycle() {
        int performed = 0;
        for (Lane lane : lanes.values()) {
            try {
                performed += cycle(lane);
            } catch (RuntimeException laneFailed) {
                // One tenant's bad day must not starve the rest of the fleet.
                LOG.warn("lane cycle failed: tenant={} {}",
                        lane.tenant(), laneFailed.getMessage());
            }
        }
        return performed;
    }

    private int cycle(Lane lane) {
        // The tenant's housekeeping first: anybody may hand back lapsed
        // claims, and the participant that needed noticing cannot.
        lane.releaseLapsed();
        int performed = 0;
        // A run records process and step separately; a service declares the
        // full id `<module>.<process>.<step>`. The poll filter speaks the
        // run's word, the service lookup speaks the catalogue's — found by
        // the first test, which polled a perfectly good run and matched
        // nothing.
        java.util.Set<String> bareSteps = new java.util.HashSet<>();
        services.keySet().forEach(step -> bareSteps.add(bareStep(step)));
        for (Run seen : lane.poll(bareSteps, 50)) {
            StepService service = services.get(seen.process() + "." + seen.step());
            if (service == null) {
                continue; // withdrawn between poll and here
            }
            Optional<Run> claimed;
            try {
                claimed = lane.claim(seen, holdFor);
            } catch (RuntimeException notARace) {
                // An empty claim and a claim that failed are opposite facts
                // and used to be one outcome here: the throw took the whole
                // cycle with it, and what the caller saw was a runner that
                // performed nothing — which reads as a quiet tenant. So the
                // reason is said, and the next run is still tried.
                LOG.warn("claim failed: tenant={} step={} {}",
                        lane.tenant(), seen.step(), notARace.getMessage());
                continue;
            }
            if (claimed.isEmpty()) {
                continue; // raced; somebody else holds it — the claim is the scheduler
            }
            perform(lane, service, claimed.get());
            performed++;
        }
        // Said again only where it changed or did not land: "still here,
        // nothing waiting" is the heartbeat's to say, and it writes nothing.
        services.keySet().forEach(step -> {
            if (!declared(lane, step).equals(declaredOn.getOrDefault(lane.tenant(), Map.of())
                    .get(step))) {
                declare(lane, step);
            }
        });
        heartbeat(lane);
        return performed;
    }

    private void perform(Lane lane, StepService service, Run claimed) {
        Counts sign = countsOf(lane, service.step());
        long began = System.nanoTime();
        // Every report answers with the run as it now stands, and the next one
        // must be built on THAT rather than on the run as claimed. A report
        // writes the state it was handed, so reporting twice from a stale copy
        // silently erases the first — a step that named a milestone and then
        // checkpointed lost the milestone, which is the one thing the report
        // said. It matters most on the way out: a released run is read
        // by the next taker, and the promise is that they resume from a
        // fact. Found by driving the runner over a lane it could only reach
        // across a boundary.
        // THE SHARED MAPPING. How an outcome becomes a report is the same
        // whichever side found the work, so it lives in one place and this
        // adds only what a runner does with the answer: its own signals, and
        // the line it writes.
        Outcome outcome = Performing.performed(lane, claimed, service, holdFor);
        long took = System.nanoTime() - began;
        if (outcome instanceof Outcome.Done) {
            sign.done(took);
            report(lane, claimed, "closed", took);
        } else if (outcome instanceof Outcome.Failed failed) {
            sign.failed(failed.reason());
            // "released", not the reason: the reason is the step's own words
            // and belongs on the run, in the store of the tenant whose work it
            // was. An outcome is a word from a fixed set, which is what makes
            // it safe to aggregate.
            report(lane, claimed, "released", took);
        } else if (outcome instanceof Outcome.Refused refused) {
            // The step did its work and the tenant would not hold the
            // result: counted as a failure of this step's, and reported as
            // its own word, because "released" would promise another attempt
            // the run will never get.
            sign.failed(refused.reason());
            report(lane, claimed, "refused", took);
        } else if (outcome instanceof Outcome.Lost lost) {
            // Somebody acted on the run before this runner finished it, and
            // nothing was said in its name. Neither the step's success nor
            // its failure, so the counts are untouched; counted as its own
            // word, so a fleet losing claims under load can be seen.
            LOG.info("a claim was lost before its work was reported: tenant={} step={} {}",
                    lane.tenant(), claimed.step(), lost.reason());
            report(lane, claimed, "lost", took);
        }
    }

    private void run() {
        while (running) {
            try {
                if (holdsWhatItAwaits()) {
                    cycle();
                }
                // The tick, or less if a lane said to look again. The poll is
                // the FALLBACK and not the mechanism: a wake-up that never
                // arrives costs the latency a runner had before wake-ups
                // existed, and loses nothing — which is what keeps a silent
                // delivery failure from being invisible in the way that
                // matters.
                woken.tryAcquire(pollEvery.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                // Being told six times is the same instruction as being told
                // once, and the cycle that follows sweeps every lane.
                woken.drainPermits();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException cycleFailed) {
                // The loop survives a bad cycle: a runner that died on the
                // first refused write would be an outage where a lagging
                // cursor was available.
                LOG.warn("runner cycle failed: {}", cycleFailed.getMessage());
            }
        }
    }

    private void declare(Lane lane, String step) {
        try {
            // A service that brings its own step introduces it BESIDE its
            // candidacy, so the catalogue learns the step the moment
            // presence can be derived. Idempotent per introducer, like the
            // declaration itself.
            StepService service = services.get(step);
            if (service != null) {
                service.declaration().ifPresent(brought ->
                        lane.introduce(brought));
            }
            Declarations.Declared declaring = declared(lane, step);
            lane.declare(declaring);
            declaredOn.computeIfAbsent(lane.tenant(), tenant -> new ConcurrentHashMap<>())
                    .put(step, declaring);
        } catch (RuntimeException declineFailed) {
            LOG.warn("declaration failed: tenant={} step={} {}",
                    lane.tenant(), step, declineFailed.getMessage());
        }
    }

    /**
     * One run's outcome, as numbers.
     *
     * <p>Labelled from what the run's envelope already discloses, minus the
     * fields that are identifiers or come from another system — see
     * {@link Label}. Nothing here reads the payload, and nothing carries the
     * step's own words about a failure.
     */
    private void report(Lane lane, Run run, String outcome, long nanos) {
        Labels labels = Labels.of(Label.TENANT, lane.tenant())
                .and(Label.PROCESS, run.process())
                .and(Label.STEP, run.step())
                .and(Label.EXECUTOR, lane.identity().name())
                .and(Label.OUTCOME, outcome);
        telemetry.counted("dbo.run.reported", 1, labels);
        telemetry.observed("dbo.run.duration", Duration.ofNanos(nanos), labels);
    }

    /**
     * Still here, with the runner's counts for this lane's steps and what
     * each contributor says.
     *
     * <p>A contributor that throws is left out of this one heartbeat and
     * named, rather than costing the worker its contact; a heartbeat the
     * node refuses is said once per cycle, like a declaration that did not
     * land.
     */
    private void heartbeat(Lane lane) {
        Map<String, Object> statistics = new java.util.LinkedHashMap<>();
        for (HeartbeatStatistics contributor : contributors) {
            try {
                Map<String, Object> said = contributor.statistics();
                if (said != null) {
                    statistics.put(contributor.namespace(), said);
                }
            } catch (RuntimeException contributorFailed) {
                LOG.warn("heartbeat statistics left out: namespace={} {}",
                        contributor.namespace(), contributorFailed.getMessage());
            }
        }
        Map<String, Object> runner = new java.util.LinkedHashMap<>();
        services.keySet().forEach(step -> runner.put(step, countsOf(lane, step).said()));
        statistics.put(RUNNER_STATISTICS, runner);
        try {
            lane.heartbeat(statistics);
        } catch (RuntimeException heartbeatFailed) {
            LOG.warn("heartbeat failed: tenant={} {}", lane.tenant(),
                    heartbeatFailed.getMessage());
        }
    }

    /** This lane's counters for this step, and no other lane's. */
    private Counts countsOf(Lane lane, String step) {
        return counts.computeIfAbsent(lane.tenant(), t -> new ConcurrentHashMap<>())
                .computeIfAbsent(step, s -> new Counts());
    }

    private static String bareStep(String fullId) {
        int dot = fullId.lastIndexOf('.');
        return dot > 0 ? fullId.substring(dot + 1) : fullId;
    }

    private Declarations.Declared declared(Lane lane, String step) {
        int dot = step.lastIndexOf('.');
        String process = dot > 0 ? step.substring(0, dot) : step;
        String stepName = dot > 0 ? step.substring(dot + 1) : step;
        return new Declarations.Declared(process, stepName,
                lane.identity().name(), lane.identity().version(),
                lane.identity().provider(), lane.identity().scope(), runnerConsumer(lane));
    }

    private static String runnerConsumer(Lane lane) {
        return lane.identity().name();
    }

    /**
     * The runner's own soft accounting, carried in each heartbeat —
     * reconstructible from nothing, lost without loss.
     *
     * <p>The last error is the reason the run was released with, the step's
     * own words, under the rule every statistic is held to: nothing about a
     * person.
     */
    private static final class Counts {

        private final AtomicLong performed = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private volatile String lastError;

        void done(long nanos) {
            performed.incrementAndGet();
            totalNanos.addAndGet(nanos);
        }

        void failed(String reason) {
            failures.incrementAndGet();
            lastError = reason;
        }

        Map<String, Object> said() {
            long count = performed.get();
            Map<String, Object> said = new java.util.LinkedHashMap<>();
            said.put("performed", count);
            said.put("failed", failures.get());
            said.put("meanMillis", count == 0 ? 0L : totalNanos.get() / count / 1_000_000);
            if (lastError != null) {
                said.put("lastError", lastError);
            }
            return said;
        }
    }
}
