package cloud.jengu.dbo.runner;

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
 * The reference runner (#79): step services in, consumption out.
 *
 * <p>Registering a {@link StepService} starts everything the participation
 * doctrine demands, written once: declared as a candidate on every lane
 * (#78), work pulled and claimed, performed, checkpointed, reported — and
 * the service re-declared with its vitals as it goes (#148), so an operator
 * sees throughput and health beside the presence the cursor already proves.
 *
 * <p><b>Stateless over tenants</b>: tenants arrive as {@link Lane}s and the
 * runner holds only the task in hand and the documents the task names. Its
 * per-step counters are its own soft accounting — reconstructible from
 * nothing, published as vitals, lost without loss.
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
    private final Map<String, StepService> services = new ConcurrentHashMap<>();
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final Map<String, Vitals> vitals = new ConcurrentHashMap<>();
    private volatile Thread loop;
    private volatile boolean running;

    public StepRunner(Duration holdFor, Duration pollEvery) {
        this.holdFor = holdFor;
        this.pollEvery = pollEvery;
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
            LOG.info("step service withdrawn: step={}", step);
        }
    }

    /** A tenant arriving. Every registered service is declared on it. */
    public synchronized StepRunner attach(Lane lane) {
        lanes.put(lane.tenant(), lane);
        services.keySet().forEach(step -> declare(lane, step));
        LOG.info("lane attached: tenant={}", lane.tenant());
        return this;
    }

    /** A tenant going away — its declarations with it. */
    public synchronized void detach(String tenant) {
        Lane lane = lanes.remove(tenant);
        if (lane != null) {
            services.keySet().forEach(step -> lane.withdraw(declared(lane, step)));
            LOG.info("lane detached: tenant={}", tenant);
        }
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
     * vitals. Public so a test — or a host scheduling for itself — drives the
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
            Optional<Run> claimed = lane.claim(seen, holdFor);
            if (claimed.isEmpty()) {
                continue; // raced; somebody else holds it — the claim is the scheduler
            }
            perform(lane, service, claimed.get());
            performed++;
        }
        // Vitals ride the declaration record, replaced never accumulated —
        // re-said even on a quiet cycle, because "still here, nothing
        // waiting" is itself a vital sign.
        services.keySet().forEach(step -> declare(lane, step));
        return performed;
    }

    private void perform(Lane lane, StepService service, Run claimed) {
        Vitals sign = vitals.computeIfAbsent(service.step(), s -> new Vitals());
        long began = System.nanoTime();
        try {
            Work work = new Work(claimed, lane.inputs(claimed), new Work.Progress() {
                @Override
                public void checkpoint(java.util.Map<String, Long> counts) {
                    lane.checkpoint(claimed, counts, holdFor);
                }

                @Override
                public void milestone(String milestone, java.util.Map<String, Long> counts) {
                    lane.milestone(claimed, milestone, counts, holdFor);
                }
            });
            Outcome outcome = service.perform(work);
            if (outcome instanceof Outcome.Done done) {
                Run reported = done.tally().isEmpty() ? claimed
                        : lane.checkpoint(claimed, done.tally(), holdFor);
                lane.closed(reported);
                sign.done(System.nanoTime() - began);
            } else if (outcome instanceof Outcome.Failed failed) {
                lane.released(claimed, failed.reason());
                sign.failed(failed.reason());
            }
        } catch (RuntimeException thrown) {
            // Released, not closed and not swallowed: released is not done,
            // and a later cycle may take it again.
            lane.released(claimed, "the service threw: " + thrown.getMessage());
            sign.failed(String.valueOf(thrown.getMessage()));
        }
    }

    private void run() {
        while (running) {
            try {
                cycle();
                Thread.sleep(pollEvery.toMillis());
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
            lane.declare(declared(lane, step).withVitals(
                    vitals.computeIfAbsent(step, s -> new Vitals()).block()));
        } catch (RuntimeException declineFailed) {
            LOG.warn("declaration failed: tenant={} step={} {}",
                    lane.tenant(), step, declineFailed.getMessage());
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
     * The runner's own soft accounting, rendered into the declaration's
     * vitals block (#148) — reconstructible from nothing, lost without loss.
     */
    private static final class Vitals {

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

        Map<String, String> block() {
            long count = performed.get();
            Map<String, String> block = new java.util.LinkedHashMap<>();
            block.put("performed", Long.toString(count));
            block.put("failed", Long.toString(failures.get()));
            block.put("meanMillis", Long.toString(
                    count == 0 ? 0 : totalNanos.get() / count / 1_000_000));
            block.put("at", java.time.Instant.now().toString());
            if (lastError != null) {
                block.put("lastError", lastError);
            }
            return block;
        }
    }
}
