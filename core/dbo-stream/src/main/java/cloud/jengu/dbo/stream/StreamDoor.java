package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.http.LaneHandler;
import cloud.jengu.dbo.runner.http.LaneVerbService;
import cloud.jengu.dbo.runner.http.LaneVerbs;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Workflow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * A tenant's door onto its lane, opened on the store's own stream.
 *
 * <p>One durable workflow per tenant sits on the substrate and receives
 * verbs as messages: each carries the verb and its body exactly as the HTTP
 * door would have received them, signed by the asker's enrolment key rather
 * than carrying a token — this plane holds no credential — and is answered
 * through the same {@link LaneVerbService} — who may ask, as whom, and what
 * each verb does are decided once, behind every door. The answer goes back
 * as an event keyed by the message, so the asker waits on the substrate and
 * never on a socket into this container. That is the shape the shared fleet
 * needs: one service reaches every tenant through the one thing it already
 * connects to, and no tenant has to accept a callback.
 *
 * <p>The workflow is long-lived and rotates by generation, so the substrate
 * records neither an unbounded operation log nor a door that vanished on
 * restart: a container coming back opens the next generation, and an asker
 * finds it by probing. Verbs are served one at a time per tenant, which is
 * the cost of a door that is one workflow; it is the same serialisation the
 * store's own feed already imposes on a participant's cursor.
 */
public final class StreamDoor implements AutoCloseable {

    /** The message topic every verb arrives on. */
    static final String TOPIC = "verb";
    /** How many verbs one generation serves before handing over to the next. */
    static final int GENERATION = 5000;
    private static final Duration IDLE = Duration.ofSeconds(2);
    /** The window a burst of claimable runs becomes one wake-up over. */
    private static final long COALESCE_MILLIS = 200;
    /**
     * A message that is not a verb: the store saying this tenant has work.
     *
     * <p>It arrives on the door's own inbox because that is the one channel
     * the door listens on, and because the substrate's trigger on that table
     * is what makes the door hear it at once rather than on a timer. The store
     * sends it to itself, through the database, deliberately: the alternative
     * was an in-memory flag that the serving loop would notice on its next
     * idle wake, which is a timer wearing a different hat.
     */
    static final String NUDGE = "{\"nudge\":true}";

    /** The substrate's name for a tenant's door, by generation. */
    public static String workflowId(String tenant, int generation) {
        return "dbo-lane-door-" + tenant + "-" + generation;
    }

    /**
     * The event key a waiting lane watches for the {@code n}th wake-up of a
     * generation.
     *
     * <p>A sequence rather than one key re-set, because an event is read by
     * its key and a key already set answers at once: a lane watching a fixed
     * key would be told about the same nudge for ever. Watching the key that
     * does not exist yet is what makes the wait notify-driven — the substrate
     * has the trigger, and the lane is woken by it rather than by asking.
     *
     * <p>Per generation, so the count restarts with the door and a lane that
     * finds a new generation starts at one rather than having to be told where
     * the last one got to.
     */
    static String workKey(long nudge) {
        return "work-" + nudge;
    }

    /** What the door does, as the substrate sees it. */
    public interface Flows {
        @Workflow(name = "serve")
        String serve(String tenant, int generation);
    }

    private final String tenant;
    private final LaneVerbService service;
    private final DBOS dbos;
    private final Flows flows;
    private final AtomicBoolean closing = new AtomicBoolean();
    /**
     * How many wake-ups this generation has emitted, which is the next key's
     * number.
     */
    private final java.util.concurrent.atomic.AtomicLong nudges =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * When the last one went out, so a burst becomes one.
     *
     * <p>A wake-up says <em>look again</em>, and saying it six times in a
     * second is the same instruction as saying it once — while costing six
     * rows in the substrate's events table for a door that may live for
     * thousands of verbs. Coalescing is therefore not a nicety: it is what
     * keeps a nudge per claimable run from becoming a write per claimable run
     * on the shared plane.
     *
     * <p>What a coalesced nudge costs is latency on the runs it dropped, and
     * the poll underneath is exactly what that is for.
     */
    private volatile long lastNudge;
    /** The generation now open, so a nudge is addressed to the door that exists. */
    private volatile int serving;
    private volatile Thread keeper;

    private final LaneHandler.SignedGrants grants;

    public StreamDoor(DataSource substrate, String tenant, LaneHandler.SignedGrants grants,
            LaneHandler.Lanes lanes) {
        this.tenant = tenant;
        this.grants = grants;
        // No token door: on this plane an ask is authenticated by signature,
        // so the service is handed an access already decided.
        this.service = new LaneVerbService(authorization -> new LaneHandler.Denied(401, null,
                "the stream door takes no token"), lanes);
        // Its own executor id: on launch an instance recovers the pending
        // workflows of its executor, and a door must never pick up an
        // asker's, nor an asker a door's.
        this.dbos = new DBOS(DBOSConfig.defaults("dbo-lane-door-" + tenant)
                .withDataSource(substrate)
                .withDatabaseSchema("dbos")
                .withExecutorId("door-" + tenant + "-" + java.util.UUID.randomUUID())
                .withMigrate(true));
        this.flows = dbos.registerProxy(Flows.class, new Serving());
        dbos.launch();
        // The keeper opens each generation as the last one closes, and the
        // first now: a door nobody is holding open is not a door.
        keeper = Thread.ofVirtual().name("dbo-lane-door-" + tenant).start(this::keep);
    }

    private void keep() {
        int generation = nextGeneration();
        while (!closing.get()) {
            final int opening = generation;
            // Published before the generation starts serving, and its wake-up
            // sequence restarts with it: the keys are per generation, so a
            // lane that finds a new door begins at one rather than having to
            // be told where the last one got to.
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
                // opens. What is not retried is the verb that was in
                // flight, which its asker times out on and asks again.
            }
            generation++;
        }
    }

    /** The first generation this container has not already run: a restart continues the count. */
    private int nextGeneration() {
        int generation = 1;
        while (dbos.getWorkflowStatus(workflowId(tenant, generation)).isPresent()) {
            generation++;
        }
        return generation;
    }

    /**
     * Tells whoever is waiting on this tenant's stream that there is work.
     *
     * <p>Called by the store when a run becomes claimable — the same seam the
     * in-JVM wake-up uses — and carrying nothing, because a wake-up says
     * <em>look again</em> and the taker then polls and claims through the
     * ordinary path.
     *
     * <p>Best effort in the strict sense: coalesced within a short window, and
     * swallowed if the substrate refuses it. A wake-up that does not go out
     * costs the latency a runner had before wake-ups existed, and the poll
     * underneath is what makes that true — so failing here must not disturb
     * the write that already landed.
     */
    public void workAppeared() {
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
            String id = java.util.UUID.randomUUID().toString();
            dbos.send(workflowId(tenant, open), NUDGE, TOPIC, id);
        } catch (RuntimeException notSent) {
            // Said once rather than per run: the substrate being unable to
            // take a nudge is worth knowing, and repeating it for every
            // claimable run would be the same noise the poll loop's silence
            // was, from the other side.
            org.slf4j.LoggerFactory.getLogger(StreamDoor.class).debug(
                    "tenant {}: a wake-up did not reach the stream, the poll stands: {}",
                    tenant, notSent.getMessage());
        }
    }

    /** The serving loop: one message, one answer, one event — until the generation is spent. */
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
                    // Published here because only a workflow may: an event
                    // belongs to the workflow that set it, and the store's
                    // side of the door is not one. The key is the next in this
                    // generation's sequence, so a lane waiting on a key nobody
                    // has set yet is woken by the substrate's own trigger
                    // rather than by asking again.
                    dbos.setEvent(workKey(nudges.incrementAndGet()), "1");
                    // NOT counted against the generation: a nudge is not a
                    // verb, and spending a door's life on wake-ups would
                    // rotate a generation for work nobody asked for.
                    continue;
                }
                // The body is kept as the text it arrived as, because that
                // is what its signature is over.
                Map<String, Object> ask = asMap(RecordWire.read(message.get(), "body"));
                String id = String.valueOf(ask.get("id"));
                // The verb itself runs as a step: a workflow replayed after a
                // crash must not claim twice for one ask, and a step's result
                // is what replay hands back instead of running it again.
                String answer = dbos.runStep(() -> answer(ask), "verb-" + id);
                dbos.setEvent(id, answer);
                served++;
            }
            return "served " + served;
        }
    }

    private String answer(Map<String, Object> ask) {
        Optional<LaneVerbs> verb = LaneVerbs.ofPath(String.valueOf(ask.get("verb")));
        Map<String, Object> envelope = new LinkedHashMap<>();
        if (verb.isEmpty()) {
            envelope.put("status", 404);
            envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
            envelope.put(LaneVerbs.REASON, "no such lane verb: " + ask.get("verb"));
            return RecordWire.write(envelope);
        }
        if (verb.get() == LaneVerbs.INPUTS) {
            // Over the stream everything crosses a plane that must hold no
            // resource content readable there, so the clear verb has no
            // answer here: inputs travel sealed, or not on this wire.
            envelope.put("status", 409);
            envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
            envelope.put(LaneVerbs.REASON, tenant + ": over the stream, inputs travel sealed — "
                    + "ask for them sealed, with the key enrolled for it");
            return RecordWire.write(envelope);
        }
        LaneVerbService.Answer answer;
        try {
            // What was signed: the ask's id, verb and the body's own bytes,
            // exactly as they travelled — so a body altered on the plane
            // fails as a forgery, and a signature does not depend on how
            // this store renders JSON.
            String bytes = StreamAsk.bytesOf(ask.get("body"));
            Object body = RecordWire.read(bytes);
            String participant = ask.get("participant") == null
                    ? null : String.valueOf(ask.get("participant"));
            String signature = ask.get("signature") == null
                    ? null : String.valueOf(ask.get("signature"));
            LaneHandler.Access access =
                    grants.of(participant,
                    StreamAsk.signedOver(ask.get("id"), ask.get("verb"), bytes),
                    signature);
            // A sender from before the bytes were signed signed the
            // rendering instead. Its rendering IS what sits in the message,
            // so the first attempt usually agrees — but where the body's own
            // text is not what this renderer would produce, the older
            // spelling is tried before the ask is called a forgery. Only on
            // 401: a refusal about scope is not about which bytes were
            // signed, and retrying it would say the wrong thing twice.
            String rendered = RecordWire.write(body);
            if (access instanceof LaneHandler.Denied denied && denied.status() == 401
                    && !rendered.equals(bytes)) {
                access = grants.of(participant,
                        StreamAsk.signedOver(ask.get("id"), ask.get("verb"), rendered),
                        signature);
            }
            answer = service.serve(access, verb.get(), body);
        } catch (RuntimeException failed) {
            envelope.put("status", 500);
            envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
            envelope.put(LaneVerbs.REASON, "the verb did not complete");
            return RecordWire.write(envelope);
        }
        switch (answer) {
            case LaneVerbService.Answer.Ok ok -> {
                envelope.put("status", 200);
                envelope.put(LaneVerbs.RESULT, RecordWire.encode(ok.result()));
            }
            case LaneVerbService.Answer.Refused refused -> {
                envelope.put("status", 409);
                envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
                envelope.put(LaneVerbs.REASON, refused.reason());
            }
            case LaneVerbService.Answer.Denied denied -> {
                envelope.put("status", denied.status());
                envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
                envelope.put(LaneVerbs.REASON, denied.reason());
            }
        }
        return RecordWire.write(envelope);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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
