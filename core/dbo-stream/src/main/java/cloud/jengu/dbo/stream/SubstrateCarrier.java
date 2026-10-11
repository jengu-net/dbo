package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.runner.transport.StreamCarrier;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * The stream protocol, carried by the store's own durable substrate.
 *
 * <p>Each tenant's door is one durable workflow there, receiving asks as
 * messages; an answer is an event keyed by the ask, so the asker waits on the
 * substrate and never on a socket into the container. That is the shape a
 * shared fleet needs: one service reaches every tenant through the one thing
 * it already connects to, and no tenant has to accept a callback.
 *
 * <p>The workflow is long-lived and rotates by generation, so the substrate
 * records neither an unbounded operation log nor a door that vanished on
 * restart: a container coming back opens the next generation, and an asker
 * finds it by probing. Asks are served one at a time per tenant, which is the
 * cost of a door that is one workflow — the same serialisation the store's own
 * feed already imposes on a participant's cursor.
 */
public final class SubstrateCarrier implements StreamCarrier {

    /** The message topic every ask arrives on. */
    static final String TOPIC = "verb";
    /** How many asks one generation serves before handing over to the next. */
    static final int GENERATION = 5000;
    private static final Duration IDLE = Duration.ofSeconds(2);
    /** The window a burst of wake-ups becomes one over. */
    private static final long COALESCE_MILLIS = 200;
    /**
     * A message that is not an ask: the store saying this tenant has work.
     *
     * <p>It arrives on the door's own inbox because that is the one channel
     * the door listens on, and because the substrate's trigger on that table
     * is what makes the door hear it at once rather than on a timer. The store
     * sends it to itself, through the database, deliberately: the alternative
     * was an in-memory flag that the serving loop would notice on its next
     * idle wake, which is a timer wearing a different hat.
     */
    static final String NUDGE = "{\"nudge\":true}";

    /**
     * How many substrate connections one door needs, for a pool that carries
     * several to be sized by.
     *
     * <p>One held for the door's life: the durable layer's listener, which is
     * what makes an ask or a wake-up reach the door at once rather than on its
     * polling interval. One for the serving loop, which takes one at a time —
     * the check for a message, the ask's step, the event that answers it. And
     * one for what runs beside the loop: the keeper waiting on the generation,
     * a wake-up being sent, the durable layer's own queue and schedule polls.
     * The same three a participant's end is sized by, for the same reasons
     * from the other end.
     */
    public static final int CONNECTIONS = 3;

    /** How long one wait for a wake-up lasts before it is asked again. */
    private static final Duration WAKEUP = Duration.ofSeconds(30);
    /** How often an ask waiting for its answer looks whether the door it asked is still open. */
    private static final Duration ASKED_AGAIN = Duration.ofSeconds(2);
    /** How long to pause when there is no door to listen to at all. */
    private static final Duration WAKEUP_IDLE = Duration.ofSeconds(2);

    private final DataSource substrate;
    private final Spill spill;

    public SubstrateCarrier(DataSource substrate) {
        this.substrate = substrate;
        this.spill = new Spill(substrate);
    }

    /** The substrate's name for a tenant's door, by generation. */
    public static String workflowId(String tenant, int generation) {
        return "dbo-lane-door-" + tenant + "-" + generation;
    }

    /**
     * The event key a waiting participant watches for the {@code n}th wake-up
     * of a generation.
     *
     * <p>A sequence rather than one key re-set, because an event is read by
     * its key and a key already set answers at once: a participant watching a
     * fixed key would be told about the same nudge for ever. Watching the key
     * that does not exist yet is what makes the wait notify-driven — the
     * substrate has the trigger, and the participant is woken by it rather
     * than by asking. Per generation, so the count restarts with the door.
     */
    static String workKey(long nudge) {
        return "work-" + nudge;
    }

    /**
     * An ask as the substrate carries it: the id the answer is keyed by, then
     * the ask, untouched. The carrier reads the id it was handed and never the
     * ask.
     */
    private static String framed(String id, String ask) {
        return id + "\n" + ask;
    }

    @Override
    public Door door(String tenant) {
        return new SubstrateDoor(tenant);
    }

    @Override
    public Asker asker(String tenant, String participant) {
        return new SubstrateAsker(tenant, participant);
    }

    /** What the door does, as the substrate sees it. */
    public interface Flows {
        @Workflow(name = "serve")
        String serve(String tenant, int generation);
    }

    /** One tenant's door: a workflow per generation, kept open by a keeper. */
    private final class SubstrateDoor implements Door {

        private final String tenant;
        private final DBOS dbos;
        private final Flows flows;
        private final AtomicBoolean closing = new AtomicBoolean();
        /** How many wake-ups this generation has emitted, which is the next key's number. */
        private final java.util.concurrent.atomic.AtomicLong nudges =
                new java.util.concurrent.atomic.AtomicLong();
        /**
         * When the last wake-up went out, so a burst becomes one: saying
         * <em>look again</em> six times in a second costs six rows in the
         * substrate's events table and says what one would.
         */
        private volatile long lastNudge;
        /** The generation now open, so a nudge is addressed to the door that exists. */
        private volatile int serving;
        private volatile Thread keeper;
        private volatile Answering answering;

        SubstrateDoor(String tenant) {
            this.tenant = tenant;
            // Its own executor id: on launch an instance recovers the pending
            // workflows of its executor, and a door must never pick up an
            // asker's, nor an asker a door's.
            this.dbos = new DBOS(DBOSConfig.defaults("dbo-lane-door-" + tenant)
                    .withDataSource(substrate)
                    .withDatabaseSchema("dbos")
                    .withExecutorId("door-" + tenant + "-" + UUID.randomUUID())
                    .withMigrate(true));
            this.flows = dbos.registerProxy(Flows.class, new Serving());
            dbos.launch();
        }

        @Override
        public void serve(Answering answering) {
            this.answering = answering;
            // The keeper opens each generation as the last one closes, and the
            // first now: a door nobody is holding open is not a door.
            keeper = Thread.ofVirtual().name("dbo-lane-door-" + tenant).start(this::keep);
        }

        private void keep() {
            int generation = nextGeneration();
            closeWhatWasLeft(generation);
            while (!closing.get()) {
                final int opening = generation;
                nudges.set(0);
                serving = opening;
                try {
                    dbos.startWorkflow(() -> flows.serve(tenant, opening),
                            new StartWorkflowOptions(workflowId(tenant, opening))).getResult();
                } catch (Exception failed) {
                    if (closing.get()) {
                        return;
                    }
                    // A generation that failed is a generation; the next one
                    // opens. What is not retried is the ask that was in
                    // flight, which its asker times out on and asks again.
                }
                generation++;
            }
        }

        /**
         * Closes every earlier generation still standing open.
         *
         * <p>A door is one per tenant on a substrate — its workflows are named
         * by the tenant alone — so a generation this door did not open and
         * that is still open belongs to a run that is gone: one stopped
         * without closing its door, whose workflow nothing will ever recover,
         * because each run serves under an executor of its own. Left open, it
         * is the door an asker finds, sends to, and waits out its patience on.
         */
        private void closeWhatWasLeft(int opening) {
            for (int earlier = 1; earlier < opening; earlier++) {
                String id = workflowId(tenant, earlier);
                Optional<WorkflowStatus> status = dbos.getWorkflowStatus(id);
                if (status.isPresent() && isOpen(status.get())) {
                    dbos.cancelWorkflow(id);
                }
            }
        }

        /** The first generation not already run: a restart continues the count. */
        private int nextGeneration() {
            int generation = 1;
            while (dbos.getWorkflowStatus(workflowId(tenant, generation)).isPresent()) {
                generation++;
            }
            return generation;
        }

        @Override
        public void wake() {
            long now = System.currentTimeMillis();
            if (closing.get() || now - lastNudge < COALESCE_MILLIS) {
                return;
            }
            int open = serving;
            if (open == 0) {
                return;
            }
            lastNudge = now;
            try {
                dbos.send(workflowId(tenant, open), NUDGE, TOPIC, UUID.randomUUID().toString());
            } catch (RuntimeException notSent) {
                // Best effort: the poll underneath is what stands, so a nudge
                // the substrate would not take is said quietly and not per run.
                org.slf4j.LoggerFactory.getLogger(SubstrateCarrier.class).debug(
                        "tenant {}: a wake-up did not reach the stream, the poll stands: {}",
                        tenant, notSent.getMessage());
            }
        }

        @Override
        public String hold(String id, String answer) {
            return spill.keep(tenant, id, answer);
        }

        /** One message, one answer, one event — until the generation is spent. */
        private final class Serving implements Flows {

            // Annotated here as well as on the interface: the substrate reads
            // the implementation for what is a workflow, and a target with
            // none is refused at registration.
            @Override
            @Workflow(name = "serve")
            public String serve(String tenant, int generation) {
                int served = 0;
                while (served < GENERATION && !closing.get()) {
                    Optional<String> message = dbos.recv(TOPIC, IDLE);
                    if (message.isEmpty()) {
                        continue;
                    }
                    if (NUDGE.equals(message.get())) {
                        // Published here because only a workflow may: an
                        // event belongs to the workflow that set it. NOT
                        // counted against the generation, which a door's life
                        // spent on wake-ups would rotate for work nobody
                        // asked for.
                        dbos.setEvent(workKey(nudges.incrementAndGet()), "1");
                        continue;
                    }
                    int newline = message.get().indexOf('\n');
                    if (newline < 0) {
                        continue;
                    }
                    String id = message.get().substring(0, newline);
                    String ask = message.get().substring(newline + 1);
                    // The answering runs as a step: a workflow replayed after
                    // a crash must not claim twice for one ask, and a step's
                    // result is what replay hands back instead of running it
                    // again. A large answer is held INSIDE the step, so what
                    // the substrate records is the small note and never the
                    // bytes.
                    String answer = dbos.runStep(() -> answering.answer(id, ask), "verb-" + id);
                    dbos.setEvent(id, answer);
                    served++;
                }
                return "served " + served;
            }
        }

        @Override
        public void close() {
            closing.set(true);
            Thread k = keeper;
            if (k != null) {
                k.interrupt();
            }
            dbos.shutdown();
        }
    }

    /** A participant's end: a substrate connection with no workflows of its own. */
    private final class SubstrateAsker implements Asker {

        private final String tenant;
        private final DBOS dbos;
        private volatile int generation = 0;

        SubstrateAsker(String tenant, String participant) {
            this.tenant = tenant;
            this.dbos = new DBOS(DBOSConfig.defaults("dbo-lane-" + tenant + "-" + participant)
                    .withDataSource(substrate)
                    .withDatabaseSchema("dbos")
                    .withExecutorId("lane-" + participant + "-" + UUID.randomUUID())
                    .withMigrate(true));
            dbos.launch();
        }

        @Override
        public Optional<String> ask(String id, String ask, Duration patience) {
            String door = door();
            for (int waited = 0; door == null && waited < 20; waited++) {
                // A generation hands over to the next in a moment nobody can
                // see from here; a door not found is asked for again before
                // it is reported away.
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                door = door();
            }
            if (door == null) {
                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                        tenant + ": no door is open on the stream — generations seen: "
                                + generationsSeen());
            }
            dbos.send(door, framed(id, ask), TOPIC, id + "@" + door);
            long until = System.nanoTime() + patience.toNanos();
            while (true) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    // The container is away: the next ask probes again.
                    generation = 0;
                    return Optional.empty();
                }
                Optional<String> answer = dbos.getEvent(door, id,
                        Duration.ofNanos(Math.min(left, ASKED_AGAIN.toNanos())));
                if (answer.isPresent()) {
                    return answer;
                }
                // Asked of a door that has since closed — the one a run that
                // is gone left open, closed by the door that replaced it — is
                // asked again of the door now open, rather than waited out.
                // Nothing is answered twice: a door that closed without
                // answering never will, and the answer is keyed by the ask.
                Optional<WorkflowStatus> asked = dbos.getWorkflowStatus(door);
                if (asked.isPresent() && !isOpen(asked.get())) {
                    generation = 0;
                    String open = door();
                    if (open != null && !open.equals(door)) {
                        door = open;
                        dbos.send(door, framed(id, ask), TOPIC, id + "@" + door);
                    }
                }
            }
        }

        @Override
        public Optional<String> collect(String key) {
            return spill.take(key);
        }

        /**
         * Waits for the door to say there is work, and keeps waiting.
         *
         * <p>On the key for the <em>next</em> wake-up of the generation this
         * end is talking to: a key nobody has set yet is what {@code getEvent}
         * blocks on, and the substrate's trigger ends the block. A timeout is
         * not a failure — the tenant has been quiet, and the participant's own
         * poll has been happening underneath — and neither is a generation
         * that rotates, whose sequence restarts at one.
         */
        @Override
        public AutoCloseable listen(Runnable woken) {
            AtomicBoolean listening = new AtomicBoolean(true);
            Thread waiting = Thread.ofVirtual()
                    .name("dbo-lane-wakeups-" + tenant)
                    .start(() -> {
                        int on = 0;
                        long next = 1;
                        while (listening.get()) {
                            try {
                                String door = door();
                                if (door == null) {
                                    Thread.sleep(WAKEUP_IDLE.toMillis());
                                    continue;
                                }
                                if (generation != on) {
                                    on = generation;
                                    next = 1;
                                }
                                if (dbos.getEvent(door, workKey(next), WAKEUP).isPresent()) {
                                    next++;
                                    woken.run();
                                }
                            } catch (InterruptedException stopping) {
                                Thread.currentThread().interrupt();
                                return;
                            } catch (RuntimeException notHeard) {
                                // The substrate is away or the door has gone.
                                // Neither loses work, so it is not shouted
                                // about once per idle interval.
                                try {
                                    Thread.sleep(WAKEUP_IDLE.toMillis());
                                } catch (InterruptedException stopping) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }
                    });
            return () -> {
                listening.set(false);
                waiting.interrupt();
            };
        }

        /** What the probe saw, for a refusal that explains itself. */
        private String generationsSeen() {
            StringBuilder seen = new StringBuilder();
            for (int candidate = 1; candidate < 50; candidate++) {
                Optional<WorkflowStatus> status =
                        dbos.getWorkflowStatus(workflowId(tenant, candidate));
                if (status.isEmpty()) {
                    break;
                }
                seen.append(candidate).append('=').append(status.get().status()).append(' ');
            }
            return seen.length() == 0 ? "none" : seen.toString().trim();
        }

        /** The door's current generation: the newest still pending, probed from the last known. */
        private String door() {
            int candidate = Math.max(generation, 1);
            String found = null;
            while (true) {
                Optional<WorkflowStatus> status =
                        dbos.getWorkflowStatus(workflowId(tenant, candidate));
                if (status.isEmpty()) {
                    break;
                }
                if (isOpen(status.get())) {
                    found = workflowId(tenant, candidate);
                    generation = candidate;
                }
                candidate++;
            }
            return found;
        }

        @Override
        public void close() {
            dbos.shutdown();
        }
    }

    /** Whether a generation is still standing open: begun, and neither finished nor cancelled. */
    private static boolean isOpen(WorkflowStatus status) {
        String state = String.valueOf(status.status());
        return "PENDING".equals(state) || "ENQUEUED".equals(state);
    }
}
