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
 * <p><b>One loop per lane, and no pool.</b> A runner scales by being
 * <b>deployed</b> more — a pod per step, replicas up — never by relaxing the
 * claim; a pool inside one runner is the first step of the coordination the
 * claim exists to make unnecessary. The claim race is the scheduler. Each lane
 * is cycled on a thread of its own for the same reason: lanes sharing one loop
 * waited on each other, so a node's own tenants queued behind a slow round
 * over the WAN to its cloud. Two loops share nothing but the services they
 * perform, which is why a {@link StepService} may be called on several threads
 * at once.
 *
 * <p><b>A lane is told apart by being itself</b>, never by its tenant. A site
 * holds two lanes to one tenant code — to the tenant in its cloud, and to its
 * own place of it — and may give both the same executor name; keyed by tenant,
 * attaching the second replaced the first and leaked its wake-ups, and
 * detaching either withdrew whichever held the key.
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
    /**
     * Every lane attached, with everything this runner keeps about it.
     *
     * <p>Found by identity, never by equality: a lane's own {@code equals} is
     * whatever its carrier says, and two lanes to one tenant must stay two.
     */
    private final List<Attached> lanes = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Names each lane's loop thread apart from another lane's to the same tenant. */
    private final AtomicLong attachments = new AtomicLong();
    /** Whoever adds to a heartbeat, beside the runner's own counts. */
    private final List<HeartbeatStatistics> contributors =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The namespace the runner's own counts travel under, which no contributor may use. */
    public static final String RUNNER_STATISTICS = "dbo.runner";
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
        lanes.forEach(lane -> declare(lane, service.step()));
        LOG.info("step service registered: step={}", service.step());
        return this;
    }

    /** The whiteboard's other half: the declaration is withdrawn everywhere. */
    public synchronized void unregister(String step) {
        if (services.remove(step) != null) {
            lanes.forEach(attached -> {
                attached.lane.withdraw(declared(attached.lane, step));
                attached.declaredOn.remove(step);
            });
            LOG.info("step service withdrawn: step={}", step);
        }
    }

    /**
     * A lane arriving. Every registered service is declared on it, and while
     * the runner runs, the lane is cycled on a loop of its own from now on.
     * Attaching a lane already attached changes nothing.
     */
    public synchronized StepRunner attach(Lane lane) {
        if (find(lane) != null) {
            return this;
        }
        Attached attached = new Attached(lane, attachments.incrementAndGet());
        lanes.add(attached);
        services.keySet().forEach(step -> declare(attached, step));
        // A lane that can say when it has work is listened to; one that
        // cannot is not, and its loop then waits out its tick exactly as it
        // always did. A failure to subscribe is logged and not fatal for the
        // same reason: the poll is underneath this, so the worst it costs is
        // the latency the runner had before.
        lane.wakeups().ifPresent(wakeups -> {
            try {
                attached.listening = wakeups.wake(attached.woken::release);
            } catch (RuntimeException notListening) {
                LOG.warn("lane attached without wake-ups, falling back to the poll: "
                        + "tenant={} {}", lane.tenant(), notListening.getMessage());
            }
        });
        if (running) {
            attached.start();
        }
        LOG.info("lane attached: tenant={}", lane.tenant());
        return this;
    }

    /** A lane going away — its declarations, its loop and its accounting with it. */
    public synchronized void detach(Lane lane) {
        Attached attached = find(lane);
        if (attached != null) {
            detaching(attached);
        }
    }

    /**
     * Every lane to that tenant going away.
     *
     * <p>Every one, where a node holds several: to the tenant in its cloud and
     * to its own place of it. A host letting go of one of them says which,
     * with {@link #detach(Lane)}.
     */
    public synchronized void detach(String tenant) {
        for (Attached attached : lanes) {
            if (attached.lane.tenant().equals(tenant)) {
                detaching(attached);
            }
        }
    }

    private void detaching(Attached attached) {
        lanes.remove(attached);
        attached.stop();
        services.keySet().forEach(step -> attached.lane.withdraw(declared(attached.lane, step)));
        attached.stopListening();
        LOG.info("lane detached: tenant={}", attached.lane.tenant());
    }

    private Attached find(Lane lane) {
        for (Attached attached : lanes) {
            if (attached.lane == lane) {
                return attached;
            }
        }
        return null;
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

    /**
     * Starts a loop for every lane attached, and for each lane attached from
     * now on. Registering and attaching while running is fine.
     */
    public synchronized StepRunner start() {
        if (running) {
            return this;
        }
        running = true;
        lanes.forEach(Attached::start);
        return this;
    }

    @Override
    public synchronized void close() {
        running = false;
        lanes.forEach(Attached::stop);
    }

    /**
     * One cycle over every lane: housekeeping, poll, claim, perform, report,
     * any declaration that has not landed, and a heartbeat. Public so a test — or a host scheduling for itself — drives the
     * runner without the thread; the loop is this in a sleep.
     */
    public int cycle() {
        int performed = 0;
        for (Attached attached : lanes) {
            performed += cycling(attached);
        }
        return performed;
    }

    /** One lane's cycle, whose failure is that lane's and nobody else's. */
    private int cycling(Attached attached) {
        try {
            return cycle(attached);
        } catch (RuntimeException laneFailed) {
            // One tenant's bad day must not starve the rest of the fleet.
            LOG.warn("lane cycle failed: tenant={} {}",
                    attached.lane.tenant(), laneFailed.getMessage());
            return 0;
        }
    }

    private int cycle(Attached attached) {
        Lane lane = attached.lane;
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
            perform(attached, service, claimed.get());
            performed++;
        }
        // Said again only where it changed or did not land: "still here,
        // nothing waiting" is the heartbeat's to say, and it writes nothing.
        services.keySet().forEach(step -> {
            if (!declared(lane, step).equals(attached.declaredOn.get(step))) {
                declare(attached, step);
            }
        });
        heartbeat(attached);
        return performed;
    }

    private void perform(Attached attached, StepService service, Run claimed) {
        Lane lane = attached.lane;
        Counts sign = attached.countsOf(service.step());
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

    /** One lane's loop: its cycle in a sleep, until the runner closes or the lane goes. */
    private void run(Attached attached) {
        while (running && attached.looping) {
            try {
                if (holdsWhatItAwaits()) {
                    cycling(attached);
                }
                // The tick, or less if the lane said to look again. The poll
                // is the FALLBACK and not the mechanism: a wake-up that never
                // arrives costs the latency a runner had before wake-ups
                // existed, and loses nothing — which is what keeps a silent
                // delivery failure from being invisible in the way that
                // matters.
                attached.woken.tryAcquire(pollEvery.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                // Being told six times is the same instruction as being told
                // once, and the cycle that follows looks at everything.
                attached.woken.drainPermits();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException cycleFailed) {
                // The loop survives a bad cycle: a runner that died on the
                // first refused write would be an outage where a lagging
                // cursor was available.
                LOG.warn("runner cycle failed: tenant={} {}", attached.lane.tenant(),
                        cycleFailed.getMessage());
            }
        }
    }

    private void declare(Attached attached, String step) {
        Lane lane = attached.lane;
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
            attached.declaredOn.put(step, declaring);
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
    private void heartbeat(Attached attached) {
        Lane lane = attached.lane;
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
        services.keySet().forEach(step -> runner.put(step, attached.countsOf(step).said()));
        statistics.put(RUNNER_STATISTICS, runner);
        try {
            lane.heartbeat(statistics);
        } catch (RuntimeException heartbeatFailed) {
            LOG.warn("heartbeat failed: tenant={} {}", lane.tenant(),
                    heartbeatFailed.getMessage());
        }
    }

    /**
     * One lane, attached: everything this runner keeps about it, and its loop.
     *
     * <p>Held here and nowhere else, which is the invariant above made
     * structural: there is no key under which a number, a declaration or a
     * wake-up from two lanes could meet, and a lane going away takes all of it
     * with it rather than leaving a count a re-attached lane would inherit.
     */
    private final class Attached {

        final Lane lane;
        final long number;
        /** Soft accounting, per step. */
        final Map<String, Counts> counts = new ConcurrentHashMap<>();
        /**
         * What this lane was last told about each step, so a declaration is
         * said again only when it changed or did not land.
         */
        final Map<String, Declarations.Declared> declaredOn = new ConcurrentHashMap<>();
        /**
         * This loop's sleep, endable, and by this lane alone: a lane saying
         * it has work wakes its own loop, never another lane's.
         */
        final java.util.concurrent.Semaphore woken = new java.util.concurrent.Semaphore(0);
        /** Its wake-ups, listened to until it goes. */
        volatile AutoCloseable listening;
        volatile boolean looping;
        volatile Thread loop;

        Attached(Lane lane, long number) {
            this.lane = lane;
            this.number = number;
        }

        Counts countsOf(String step) {
            return counts.computeIfAbsent(step, s -> new Counts());
        }

        void start() {
            if (loop != null) {
                return;
            }
            looping = true;
            loop = Thread.ofPlatform().name("dbo-step-runner-" + lane.tenant() + "-" + number)
                    .daemon(true).start(() -> run(this));
        }

        void stop() {
            looping = false;
            Thread current = loop;
            loop = null;
            if (current != null) {
                current.interrupt();
            }
        }

        void stopListening() {
            AutoCloseable subscription = listening;
            listening = null;
            if (subscription == null) {
                return;
            }
            try {
                subscription.close();
            } catch (Exception stopping) {
                LOG.warn("a lane's wake-ups did not stop: tenant={} {}",
                        lane.tenant(), stopping.getMessage());
            }
        }
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
