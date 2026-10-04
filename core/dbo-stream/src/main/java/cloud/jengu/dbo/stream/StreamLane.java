package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.http.WireLane;
import cloud.jengu.dbo.runner.transport.LaneVerbs;
import cloud.jengu.dbo.runner.transport.StreamCarrier;
import cloud.jengu.dbo.work.Executor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * A tenant's lane, held by a participant over the stream protocol.
 *
 * <p>The third binding, beside in-process and HTTP, and the one a shared
 * fleet holds: every tenant's door is reached through a carrier the
 * participant already holds — the store's own substrate, or one a host
 * provides — and nothing here opens a connection into a tenant. The plane
 * between holds no token and nothing readable: an ask is signed with the
 * participant's enrolment key rather than carrying a credential, and inputs
 * are only ever asked for sealed. The verbs are {@link WireLane}'s — encoded
 * once — so a runner holding this cannot tell it from the other two.
 */
public final class StreamLane extends WireLane implements AutoCloseable {

    private final Carried carried;

    /**
     * A lane on the store's own substrate, for a participant holding the
     * private halves of the keys it enrolled with — the only kind there is on
     * this wire.
     *
     * <p><b>The participant is the credential here</b>, which it is not over
     * HTTP: there a token says who is asking and the participant is only the
     * cursor's name, and here the enrolment the signature is checked against
     * is both. So an executor named differently from the participant is
     * admitted exactly when a differently named one would be over HTTP — when
     * the enrolment speaks for the whole tenant.
     */
    public static StreamLane holding(DataSource substrate, String tenant, String participant,
            Executor identity, java.security.PrivateKey privateKey,
            java.security.PrivateKey signingKey) {
        return holding(new SubstrateCarrier(substrate), tenant, participant, identity,
                privateKey, signingKey);
    }

    /** The same, over the carrier given. */
    public static StreamLane holding(StreamCarrier carrier, String tenant, String participant,
            Executor identity, java.security.PrivateKey privateKey,
            java.security.PrivateKey signingKey) {
        if (signingKey == null || privateKey == null) {
            throw new IllegalArgumentException("a lane on the stream is held by a participant "
                    + "enrolled with both keys: one it signs with, one it is sealed to");
        }
        // THE PARTICIPANT, not the executor. What the door checks a signature
        // against is the enrolment the ask names, and a worker records runs
        // under a name of its own.
        return new StreamLane(new Carried(carrier.asker(tenant, participant), tenant,
                participant, signingKey), tenant, participant, identity, null, privateKey,
                signingKey);
    }

    private StreamLane(Carried carried, String tenant, String participant, Executor identity,
            Set<String> boundTo, java.security.PrivateKey holding,
            java.security.PrivateKey signing) {
        super(carried, tenant, participant, identity, boundTo, holding, signing);
        this.carried = carried;
    }

    /**
     * The clear verb, asked for directly. A keyed lane never asks this way —
     * {@code inputs} goes sealed — and the door refuses it; this exists so a
     * test can show the refusal rather than assume it.
     */
    public java.util.Map<String, java.util.List<cloud.jengu.dbo.core.api.StoredObject>>
            askedInTheClear(cloud.jengu.dbo.work.Run run) {
        return inputsInTheClear(run);
    }

    /**
     * How this lane is told its tenant has work: the carrier's wake-ups,
     * which say <em>look again</em> and carry nothing. Being told five times
     * is the same instruction as being told once.
     */
    @Override
    public java.util.Optional<cloud.jengu.dbo.runner.Wakeups> wakeups() {
        return java.util.Optional.of(carried.asker::listen);
    }

    @Override
    public void close() {
        carried.asker.close();
    }

    /** One signed ask per verb, carried to the door and answered keyed by the ask. */
    private static final class Carried implements Transport {

        private static final Duration ANSWER = Duration.ofSeconds(30);
        private final StreamCarrier.Asker asker;
        private final String tenant;
        private final String participant;
        private final java.security.PrivateKey signing;

        Carried(StreamCarrier.Asker asker, String tenant, String participant,
                java.security.PrivateKey signing) {
            this.asker = asker;
            this.tenant = tenant;
            this.participant = participant;
            this.signing = signing;
        }

        @Override
        public Reply post(LaneVerbs verb, String body) {
            String id = UUID.randomUUID().toString();
            Optional<String> answer = asker.ask(id, RecordWire.write(asked(id, verb, body)),
                    ANSWER);
            if (answer.isEmpty()) {
                // The door did not answer in time: it went away under the
                // ask, or the carrier dropped it. Retryable, and not a
                // refusal.
                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                        tenant + ": the door on the stream did not answer '" + verb.path() + "'");
            }
            String answered = collected(answer.get());
            Object envelope = RecordWire.read(answered);
            int status = envelope instanceof Map<?, ?> map && map.get("status") instanceof Number n
                    ? n.intValue() : 500;
            return new Reply(status, answered);
        }

        /** One ask, signed over the body's own bytes, which are the bytes that travel. */
        private Map<String, Object> asked(String id, LaneVerbs verb, String body) {
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("id", id);
            ask.put("participant", participant);
            ask.put("verb", verb.path());
            // Parsed to refuse a malformed body here rather than at the far
            // end, and then discarded: what travels is the text itself.
            RecordWire.read(body);
            ask.put("body", new RecordWire.Raw(body));
            ask.put("signature", cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                    StreamAsk.signedOver(id, verb.path(), body), signing));
            return ask;
        }

        /**
         * The answer, fetched if the door only said where it is.
         *
         * <p>Put back together here, at the bottom, so nothing above knows the
         * carrier held it. Bytes that are gone are a fault rather than an
         * empty answer: an answer is handed over once, so a second collection
         * means this ask was answered twice or somebody else took it — and
         * answering the caller with nothing would turn that into a run with
         * no inputs rather than an ask to make again.
         */
        private String collected(String answer) {
            Object envelope = RecordWire.read(answer);
            if (!(envelope instanceof Map<?, ?> map)
                    || !(map.get(StreamDoor.SPILLED) instanceof String key)) {
                return answer;
            }
            return asker.collect(key).orElseThrow(() ->
                    new cloud.jengu.dbo.core.api.StoreUnreachableException(tenant
                            + ": the door held its answer under '" + key
                            + "' and it was not there to collect"));
        }
    }
}
