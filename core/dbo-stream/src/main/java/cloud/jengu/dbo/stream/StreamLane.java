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

    /**
     * How this lane is told its tenant has work.
     *
     * <p>One virtual thread per listener, waiting on the event key the door
     * has not set yet — which is what makes the wait notify-driven rather than
     * another poll: the substrate's trigger on its events table wakes it, and
     * `getEvent` returns the moment the door publishes.
     *
     * <p>Catching up is deliberate and harmless. A listener starting at a door
     * that has already emitted several wake-ups finds each of them set and
     * runs through them at once before blocking on the next — and since a
     * wake-up means <em>look again</em>, being told five times in a row is the
     * same instruction as being told once.
     */
    @Override
    public java.util.Optional<cloud.jengu.dbo.runner.Wakeups> wakeups() {
        return java.util.Optional.of(substrate::listen);
    }

    @Override
    public void close() {
        substrate.close();
    }

    /** One message per verb, one event per answer, on the substrate. */
    private static final class Substrate implements Transport, AutoCloseable {

        private static final Duration ANSWER = Duration.ofSeconds(30);
        /**
         * How long one wait for a wake-up lasts before it is asked again.
         *
         * <p>Not a poll interval: the wait itself is notify-driven and this is
         * only how long a single wait blocks, so that a generation rotating or
         * a substrate going away is noticed rather than waited on for ever.
         */
        private static final Duration WAKEUP = Duration.ofSeconds(30);
        /** How long to pause when there is no door to listen to at all. */
        private static final Duration WAKEUP_IDLE = Duration.ofSeconds(2);
        private final DBOS dbos;
        private final String tenant;
        private final String participant;
        private final java.security.PrivateKey signing;
        private final Spill spill;
        private volatile int generation = 0;

        Substrate(DataSource substrate, String tenant, String participant,
                java.security.PrivateKey signing) {
            this.tenant = tenant;
            this.participant = participant;
            this.signing = signing;
            this.spill = new Spill(substrate);
            // A substrate connection with no workflows of its own: this side
            // sends and waits, and executes nothing the door enqueues.
            this.dbos = new DBOS(DBOSConfig.defaults("dbo-lane-" + tenant + "-" + participant)
                    .withDataSource(substrate)
                    .withDatabaseSchema("dbos")
                    .withExecutorId("lane-" + participant + "-" + UUID.randomUUID())
                    .withMigrate(true));
            dbos.launch();
        }

        /**
         * Waits for the door to say there is work, and keeps waiting.
         *
         * <p>The wait is on the key for the <em>next</em> wake-up of the
         * generation this lane is talking to. A key nobody has set yet is what
         * {@code getEvent} blocks on, and the substrate's trigger is what ends
         * the block — so this is a listener rather than a second poll.
         *
         * <p>A timeout is not a failure and is not reported as one: it means
         * the tenant has been quiet, and the runner's own poll has been
         * happening underneath the whole time. The same is true of a
         * generation that rotates — the sequence restarts with the door, so
         * the count is reset and the wait begins again at one.
         */
        AutoCloseable listen(Runnable woken) {
            java.util.concurrent.atomic.AtomicBoolean listening =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread waiting = Thread.ofVirtual()
                    .name("dbo-lane-wakeups-" + tenant + "-" + participant)
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
                                    // A new door counts from one again.
                                    on = generation;
                                    next = 1;
                                }
                                if (dbos.getEvent(door, StreamDoor.workKey(next), WAKEUP)
                                        .isPresent()) {
                                    next++;
                                    woken.run();
                                }
                            } catch (InterruptedException stopping) {
                                Thread.currentThread().interrupt();
                                return;
                            } catch (RuntimeException notHeard) {
                                // The substrate is away or the door has gone.
                                // Neither loses work — the poll underneath is
                                // what this sits on top of — so it is not
                                // shouted about once per idle interval.
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

        /**
         * The answer, fetched if the door only said where it is.
         *
         * <p>Put back together here, at the bottom, so nothing above this
         * knows there was a spill: the verb above is handed the bytes the
         * door produced either way, and a runner cannot tell a large answer
         * from a small one except by how long it took. That is the same
         * contract the wake-up is held to, for the same reason.
         *
         * <p>Bytes that are gone are a fault rather than an empty answer. The
         * spill hands a row over exactly once, so a second collection means
         * this ask was answered twice or somebody else took it — and
         * answering the caller with nothing would turn that into a run with
         * no inputs rather than an ask to make again.
         */
        private String collected(String answer) {
            Object envelope = RecordWire.read(answer);
            if (!(envelope instanceof Map<?, ?> map)
                    || !(map.get(StreamDoor.SPILLED) instanceof String key)) {
                return answer;
            }
            return spill.take(key).orElseThrow(() ->
                    new cloud.jengu.dbo.core.api.StoreUnreachableException(tenant
                            + ": the door spilled its answer to '" + key
                            + "' and the bytes were not there to collect"));
        }

        /** One ask, signed — built here so telling and asking spell it once. */
        private Map<String, Object> asked(String id, LaneVerbs verb, String body) {
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("id", id);
            ask.put("participant", participant);
            ask.put("verb", verb.path());
            // Parsed to refuse a malformed body here rather than at the far
            // end, and then discarded: what travels is the text itself.
            RecordWire.read(body);
            ask.put("body", new RecordWire.Raw(body));
            // Signed over the body's own bytes, which are the bytes that
            // travel — and spelled by StreamAsk, so the door is not a second
            // implementation of the same sentence.
            ask.put("signature", cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                    StreamAsk.signedOver(id, verb.path(), body), signing));
            return ask;
        }

        /**
         * Handed to the substrate and left there.
         *
         * <p>The send is the durable part: the message is a row the far side
         * will find whether or not it was listening when it landed, so this is
         * not fire-and-forget in the sense of unreliable — it is fire-and-forget
         * in the sense of not waiting. The answer this does not wait for is the
         * one the caller was going to discard.
         */
        @Override
        public Reply tell(LaneVerbs verb, String body) {
            String door = door();
            if (door == null) {
                // Not an exception: the caller is telling, not asking, and a
                // door between generations is a moment rather than a fault.
                // What is lost is one re-said declaration, and the next cycle
                // says it again.
                return new Reply(200, "");
            }
            String id = UUID.randomUUID().toString();
            dbos.send(door, RecordWire.write(asked(id, verb, body)), StreamDoor.TOPIC, id);
            return new Reply(200, "");
        }

        @Override
        public Reply post(LaneVerbs verb, String body) {
            String id = UUID.randomUUID().toString();
            Map<String, Object> ask = asked(id, verb, body);
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
            String answered = collected(answer.get());
            Object envelope = RecordWire.read(answered);
            int status = envelope instanceof Map<?, ?> map && map.get("status") instanceof Number n
                    ? n.intValue() : 500;
            return new Reply(status, answered);
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
