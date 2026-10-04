package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.transport.LaneVerbService;
import cloud.jengu.dbo.runner.transport.LaneVerbs;
import cloud.jengu.dbo.runner.transport.StreamCarrier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A tenant's door onto its lane, for the stream protocol, over whichever
 * carrier carries it.
 *
 * <p>An ask arrives as the participant sent it: its verb and body, signed over
 * the body's own bytes by the key the participant enrolled — the plane between
 * holds no credential — and is answered through the tenant's
 * {@link LaneVerbService}, so who may ask, as whom, and what each verb does
 * are decided once, behind every door. The answer goes back keyed by the ask;
 * one too large to travel in a message is held by the carrier until its asker
 * collects it.
 *
 * <p><b>The protocol is here and the carrying is not.</b> The store's own
 * substrate is one {@link StreamCarrier}; a host may register another. Either
 * way this door checks the signature over the bytes that arrived, so a
 * carrier that altered an ask has delivered a forgery, and refused.
 */
public final class StreamDoor implements AutoCloseable {

    /**
     * The field an answer carries instead of itself when it was too large to
     * travel in the message. Its value is the key the carrier holds it under.
     */
    static final String SPILLED = "spilled";

    /** Above this, an answer is held by the carrier and travels by reference. */
    static final int HOLD_OVER = Spill.SPILL_OVER;

    private final String tenant;
    private final LaneVerbService service;
    private final StreamCarrier.Door door;

    /**
     * A door on the store's own substrate.
     *
     * @param verbs the tenant's verbs, taking signed asks: on this plane an
     *              ask is authenticated by signature, never by a token
     */
    public StreamDoor(DataSource substrate, String tenant, LaneVerbService verbs) {
        this(new SubstrateCarrier(substrate), tenant, verbs);
    }

    /** A door on the carrier given, opened and serving when this returns. */
    public StreamDoor(StreamCarrier carrier, String tenant, LaneVerbService verbs) {
        this.tenant = tenant;
        this.service = verbs;
        this.door = carrier.door(tenant);
        door.serve(this::answering);
    }

    /**
     * Tells whoever is waiting on this tenant's stream that there is work.
     *
     * <p>Called by the store when a run becomes claimable, carrying nothing:
     * a wake-up says <em>look again</em>, and the taker then polls and claims
     * through the ordinary path. A wake-up the carrier drops costs the
     * latency a participant had before wake-ups, because the poll underneath
     * is what stands.
     */
    public void workAppeared() {
        door.wake();
    }

    /**
     * The answer, or a note saying where the carrier holds it.
     *
     * <p>The decision is made on the bytes that would have travelled and
     * nowhere else: nothing above the lane knows a hold happened, because the
     * lane puts the two back together before anything sees them.
     */
    private String answering(String id, String ask) {
        String answer = answer(id, asMap(RecordWire.read(ask, "body")));
        if (answer.length() <= HOLD_OVER) {
            return answer;
        }
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("status", 200);
        note.put(SPILLED, door.hold(id, answer));
        return RecordWire.write(note);
    }

    private String answer(String carried, Map<String, Object> ask) {
        Optional<LaneVerbs> verb = LaneVerbs.ofPath(String.valueOf(ask.get("verb")));
        if (verb.isEmpty()) {
            return refused(404, "no such lane verb: " + ask.get("verb"));
        }
        if (!carried.equals(String.valueOf(ask.get("id")))) {
            // The answer is keyed by the id the carrier gave and the
            // signature is over the id the ask carries: two that differ would
            // answer one asker with another's verb.
            return refused(400, "the ask was delivered under an id it does not carry");
        }
        if (verb.get() == LaneVerbs.INPUTS) {
            // Over the stream everything crosses a plane that must hold no
            // resource content readable there, so the clear verb has no
            // answer here: inputs travel sealed, or not on this wire.
            return refused(409, tenant + ": over the stream, inputs travel sealed — ask for "
                    + "them sealed, with the key enrolled for it");
        }
        LaneVerbService.Answer answer;
        try {
            // What was signed: the ask's id, verb and the body's own bytes,
            // exactly as they travelled — so a body altered on the way fails
            // as a forgery, and a signature does not depend on how this store
            // renders JSON.
            String bytes = StreamAsk.bytesOf(ask.get("body"));
            Object body = RecordWire.read(bytes);
            String participant = ask.get("participant") == null
                    ? null : String.valueOf(ask.get("participant"));
            String signature = ask.get("signature") == null
                    ? null : String.valueOf(ask.get("signature"));
            answer = service.serveSigned(participant,
                    StreamAsk.signedOver(ask.get("id"), ask.get("verb"), bytes),
                    signature, verb.get(), body);
            // A sender from before the bytes were signed signed the
            // rendering instead. Where the body's own text is not what this
            // renderer would produce, the older spelling is tried before the
            // ask is called a forgery — only on 401, because a refusal about
            // scope is not about which bytes were signed.
            String rendered = RecordWire.write(body);
            if (answer instanceof LaneVerbService.Answer.Denied denied
                    && denied.status() == 401 && !rendered.equals(bytes)) {
                answer = service.serveSigned(participant,
                        StreamAsk.signedOver(ask.get("id"), ask.get("verb"), rendered),
                        signature, verb.get(), body);
            }
        } catch (RuntimeException failed) {
            // Said here, as the HTTP door says it: the asker hears only that
            // the verb did not complete, so a cause not logged on this side
            // is a cause nobody can find.
            org.slf4j.LoggerFactory.getLogger(StreamDoor.class).error(
                    "lane verb failed on the stream: tenant={} verb={}", tenant,
                    ask.get("verb"), failed);
            return refused(500, "the verb did not complete");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        switch (answer) {
            case LaneVerbService.Answer.Ok ok -> {
                envelope.put("status", 200);
                envelope.put(LaneVerbs.RESULT, RecordWire.encode(ok.result()));
            }
            case LaneVerbService.Answer.Refused refused -> {
                envelope.put("status", 409);
                envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
                envelope.put(LaneVerbs.REASON, refused.reason());
                if (refused.lost()) {
                    envelope.put(LaneVerbs.LOST, Boolean.TRUE);
                }
            }
            case LaneVerbService.Answer.Denied denied -> {
                envelope.put("status", denied.status());
                envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
                envelope.put(LaneVerbs.REASON, denied.reason());
            }
        }
        return RecordWire.write(envelope);
    }

    private static String refused(int status, String reason) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", status);
        envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
        envelope.put(LaneVerbs.REASON, reason);
        return RecordWire.write(envelope);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Override
    public void close() {
        door.close();
    }
}
