package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.http.LaneVerbs;
import cloud.jengu.dbo.runner.http.WireLane;
import cloud.jengu.dbo.work.Executor;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.WorkflowStatus;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * A tenant's lane, held by a host that reaches the store over the store's
 * own stream.
 *
 * <p>The third carrier, beside in-process and HTTP, and the one a shared
 * fleet holds: the host connects to the substrate it already runs on, and
 * every tenant's door is a workflow there. A verb is a message to that door
 * and the answer is an event on it; nothing here opens a connection into a
 * tenant, and the tenant accepts no callback. The plane between holds no
 * token and nothing readable: an ask is signed with the participant's
 * enrolment key rather than carrying a credential, and inputs are only ever
 * asked for sealed. The verbs are
 * {@link WireLane}'s — encoded once — so a runner holding this cannot tell it
 * from the other two, which is the contract.
 *
 * <p>The door's current generation is found by probing the substrate and
 * remembered; a generation that closes underneath an ask makes the ask time
 * out, and the next ask finds the next generation.
 */
public final class StreamLane extends WireLane implements AutoCloseable {

    private final Substrate substrate;

    /**
     * A lane for a participant holding the private halves of the keys it
     * enrolled with — the only kind there is on this wire. Every ask is
     * signed with the signing key, because the plane it crosses holds no
     * token; every payload arrives sealed to the other, because that plane
     * holds nothing readable.
     */
    public static StreamLane holding(DataSource substrate, String tenant, String participant,
            Executor identity, java.security.PrivateKey privateKey,
            java.security.PrivateKey signingKey) {
        if (signingKey == null || privateKey == null) {
            throw new IllegalArgumentException("a lane on the stream is held by a participant "
                    + "enrolled with both keys: one it signs with, one it is sealed to");
        }
        return new StreamLane(new Substrate(substrate, tenant, identity.name(), signingKey),
                tenant, participant, identity, null, privateKey, signingKey);
    }

    private StreamLane(Substrate substrate, String tenant, String participant, Executor identity,
            Set<String> boundTo, java.security.PrivateKey holding,
            java.security.PrivateKey signing) {
        super(substrate, tenant, participant, identity, boundTo, holding, signing);
        this.substrate = substrate;
    }

    /**
     * The clear verb, asked for directly. A keyed lane never asks this way —
     * {@code inputs} goes sealed — and the door refuses it; this exists so a
     * test can show the refusal rather than assume it.
     */
    public java.util.Map<String, cloud.jengu.dbo.core.api.StoredObject> askedInTheClear(
            cloud.jengu.dbo.work.Run run) {
        return inputsInTheClear(run);
    }

    @Override
    public void close() {
        substrate.close();
    }

    /** One message per verb, one event per answer, on the substrate. */
    private static final class Substrate implements Transport, AutoCloseable {

        private static final Duration ANSWER = Duration.ofSeconds(30);
        private final DBOS dbos;
        private final String tenant;
        private final String participant;
        private final java.security.PrivateKey signing;
        private volatile int generation = 0;

        Substrate(DataSource substrate, String tenant, String participant,
                java.security.PrivateKey signing) {
            this.tenant = tenant;
            this.participant = participant;
            this.signing = signing;
            // A substrate connection with no workflows of its own: this side
            // sends and waits, and executes nothing the door enqueues.
            this.dbos = new DBOS(DBOSConfig.defaults("dbo-lane-" + tenant + "-" + participant)
                    .withDataSource(substrate)
                    .withDatabaseSchema("dbos")
                    .withExecutorId("lane-" + participant + "-" + UUID.randomUUID())
                    .withMigrate(true));
            dbos.launch();
        }

        @Override
        public Reply post(LaneVerbs verb, String body) {
            String id = UUID.randomUUID().toString();
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("id", id);
            ask.put("participant", participant);
            ask.put("verb", verb.path());
            Object parsed = RecordWire.read(body);
            ask.put("body", parsed);
            // Signed as it travels: id, verb and the body's own rendering, so
            // the door can check that what arrived is what was asked.
            String signed = id + "\n" + verb.path() + "\n" + RecordWire.write(parsed);
            ask.put("signature", cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                    signed.getBytes(java.nio.charset.StandardCharsets.UTF_8), signing));
            String door = door();
            for (int patience = 0; door == null && patience < 20; patience++) {
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
                        tenant + ": no door is open on the stream for '" + verb.path()
                                + "' — generations seen: " + generationsSeen());
            }
            dbos.send(door, RecordWire.write(ask), StreamDoor.TOPIC, id);
            Optional<String> answer = dbos.getEvent(door, id, ANSWER);
            if (answer.isEmpty()) {
                // The door did not answer in time: a generation closed under
                // the ask, or the container is away. Retryable, and not a
                // refusal; the next ask probes again.
                generation = 0;
                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                        tenant + ": the door on the stream did not answer '" + verb.path() + "'");
            }
            Object envelope = RecordWire.read(answer.get());
            int status = envelope instanceof Map<?, ?> map && map.get("status") instanceof Number n
                    ? n.intValue() : 500;
            return new Reply(status, answer.get());
        }

        /** What the probe saw, for a refusal that explains itself. */
        private String generationsSeen() {
            StringBuilder seen = new StringBuilder();
            for (int candidate = 1; candidate < 50; candidate++) {
                Optional<WorkflowStatus> status =
                        dbos.getWorkflowStatus(StreamDoor.workflowId(tenant, candidate));
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
                        dbos.getWorkflowStatus(StreamDoor.workflowId(tenant, candidate));
                if (status.isEmpty()) {
                    break;
                }
                if (isOpen(status.get())) {
                    found = StreamDoor.workflowId(tenant, candidate);
                    generation = candidate;
                }
                candidate++;
            }
            return found;
        }

        private static boolean isOpen(WorkflowStatus status) {
            String state = String.valueOf(status.status());
            return "PENDING".equals(state) || "ENQUEUED".equals(state);
        }

        @Override
        public void close() {
            dbos.shutdown();
        }
    }
}
