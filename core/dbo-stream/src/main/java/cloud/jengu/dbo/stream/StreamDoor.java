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
 * verbs as messages: each carries the asker's credential, the verb and its
 * body exactly as the HTTP door would have received them, and is answered
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

    /** The substrate's name for a tenant's door, by generation. */
    public static String workflowId(String tenant, int generation) {
        return "dbo-lane-door-" + tenant + "-" + generation;
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
    private volatile Thread keeper;

    public StreamDoor(DataSource substrate, String tenant, LaneHandler.Grants grants,
            LaneHandler.Lanes lanes) {
        this.tenant = tenant;
        this.service = new LaneVerbService(grants, lanes);
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
                Map<String, Object> ask = asMap(RecordWire.read(message.get()));
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
        LaneVerbService.Answer answer;
        try {
            answer = service.serve(ask.get("authorization") == null ? null
                    : String.valueOf(ask.get("authorization")), verb.get(), ask.get("body"));
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
