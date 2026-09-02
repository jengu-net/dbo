package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tenant's lane, held by a host on the far side of some wire.
 *
 * <p>The verbs as bodies and answers, over whatever carries them. HTTP is one
 * carrier and the store's own stream is another; this class is what makes a
 * runner unable to tell, because the verbs are encoded once and the carrier
 * only moves bytes. A verb that only one carrier could serve does not belong
 * on the lane, and this is where that would show.
 *
 * <p><b>Two things are answered without asking.</b> {@link #tenant()} and
 * {@link #identity()} are what this lane was built with, so they cost no
 * round trip and cannot fail while the link is down. Everything else is a
 * verb.
 *
 * <p><b>A refusal arrives as a refusal.</b> The far side answers with the
 * lane's own reason and this rethrows it, so a run this identity has not
 * claimed reads here exactly as it reads in-process. A link that is simply
 * down throws too, and says so differently — the two need different
 * recovery, and a lane that returned an empty list for both would hide the
 * one that is worth fixing.
 */
public class WireLane implements Lane {

    /** What a carrier answers: a status in HTTP's vocabulary, and the envelope. */
    public record Reply(int status, String body) {}

    /** Moves one verb's body across and brings the reply back; decides nothing on the way. */
    @FunctionalInterface
    public interface Transport {
        Reply post(LaneVerbs verb, String body);
    }

    private final Transport transport;
    private final Set<String> boundTo;
    private final String tenant;
    private final String participant;
    private final Executor identity;
    /** The private half of this participant's enrolment keypair, or null for one that offered none. */
    private final java.security.PrivateKey holding;
    /** The private half of the key this participant signs its links with, beside {@code holding}. */
    private final java.security.PrivateKey signing;
    /** The head of each run's chain as this side last saw it: what the next link commits to. */
    private final Map<String, String> heads = new java.util.concurrent.ConcurrentHashMap<>();

    protected WireLane(Transport transport, String tenant, String participant, Executor identity,
            Set<String> boundTo, java.security.PrivateKey holding,
            java.security.PrivateKey signing) {
        this.transport = transport;
        this.tenant = tenant;
        this.participant = participant;
        this.identity = identity;
        this.boundTo = boundTo;
        this.holding = holding;
        this.signing = signing;
    }

    @Override
    public String tenant() {
        return tenant;
    }

    @Override
    public Executor identity() {
        return identity;
    }

    @Override
    public List<Run> poll(Set<String> steps, int limit) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.STEPS, RecordWire.encode(List.copyOf(steps)));
        body.put(LaneVerbs.LIMIT, limit);
        return RecordWire.decodeList(post(LaneVerbs.POLL, body), Run.class);
    }

    @Override
    public Optional<Run> claim(Run run, Duration holdFor) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.HOLD_FOR_MILLIS, holdFor.toMillis());
        return Optional.ofNullable(RecordWire.decode(post(LaneVerbs.CLAIM, body), Run.class));
    }

    @Override
    public Run checkpoint(Run run, Map<String, Long> counts, Duration holdFor) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.COUNTS, RecordWire.encode(counts));
        body.put(LaneVerbs.HOLD_FOR_MILLIS, holdFor.toMillis());
        return RecordWire.decode(post(LaneVerbs.CHECKPOINT, body), Run.class);
    }

    @Override
    public Run milestone(Run run, String milestone, Map<String, Long> counts,
            Duration holdFor) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.MILESTONE_NAME, milestone);
        body.put(LaneVerbs.COUNTS, RecordWire.encode(counts));
        body.put(LaneVerbs.HOLD_FOR_MILLIS, holdFor.toMillis());
        return RecordWire.decode(post(LaneVerbs.MILESTONE, body), Run.class);
    }

    @Override
    public void released(Run run, String reason) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.REASON, reason);
        post(LaneVerbs.RELEASED, body);
    }

    @Override
    public void closed(Run run) {
        // A participant that signs closes with the head it commits to — the
        // last link it made or saw — without the runner having to know
        // chains exist.
        closed(run, signing == null ? null : heads.get(run.key()));
    }

    @Override
    public void closed(Run run, String head) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        if (head != null) {
            body.put(LaneVerbs.HEAD, head);
        }
        post(LaneVerbs.CLOSED, body);
        heads.remove(run.key());
    }

    @Override
    public int releaseLapsed() {
        Object released = post(LaneVerbs.RELEASE_LAPSED, verb());
        return released instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public void declare(Declarations.Declared declared) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.DECLARED, RecordWire.encode(declared));
        post(LaneVerbs.DECLARE, body);
    }

    @Override
    public void routes(java.util.List<cloud.jengu.dbo.work.Trackable> behind) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.BEHIND, RecordWire.encode(behind));
        post(LaneVerbs.ROUTES, body);
    }

    @Override
    public void introduce(StepDeclaration step) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.STEP, RecordWire.encode(step));
        post(LaneVerbs.INTRODUCE, body);
    }

    @Override
    public void withdraw(Declarations.Declared declared) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.DECLARED, RecordWire.encode(declared));
        post(LaneVerbs.WITHDRAW, body);
    }

    @Override
    public Map<String, StoredObject> inputs(Run run) {
        if (holding != null) {
            // What this participant holds decides how its work arrives. The
            // far side refuses the clear verb to a keyed identity anyway;
            // asking sealed first is the runner not needing to know that.
            return open(sealed(run), run);
        }
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        return RecordWire.decodeMap(post(LaneVerbs.INPUTS, body), StoredObject.class);
    }

    @Override
    public cloud.jengu.dbo.work.SealedWork sealed(Run run) {
        return sealed(run, null);
    }

    @Override
    public cloud.jengu.dbo.work.SealedWork sealed(Run run, List<String> recipients) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        if (recipients != null) {
            body.put(LaneVerbs.RECIPIENTS, RecordWire.encode(recipients));
        }
        cloud.jengu.dbo.work.SealedWork work = RecordWire.decode(post(LaneVerbs.SEALED, body),
                cloud.jengu.dbo.work.SealedWork.class);
        // A router forwarding sealed work carries its routee's openings home
        // and closes on the head they leave; the manifest says where the
        // chain stands when the work leaves.
        if (work.manifest().head() != null) {
            heads.put(run.key(), work.manifest().head());
        }
        return work;
    }

    @Override
    public String opened(Run run, String reference, cloud.jengu.dbo.work.RunChain.Link link) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.REFERENCE, reference);
        body.put(LaneVerbs.PREVIOUS, link.previous());
        body.put(LaneVerbs.LINK, link.link());
        if (link.author() != null) {
            // Who opened: this participant, or the routee whose signed link
            // it is carrying home.
            body.put(LaneVerbs.AUTHOR, link.author());
        }
        if (link.signature() != null) {
            body.put(LaneVerbs.SIGNATURE, link.signature());
        }
        String head = String.valueOf(post(LaneVerbs.OPENED, body));
        heads.put(run.key(), head);
        return head;
    }

    /** This side's link for an opening: computed here, signed here, committing to the head it holds. */
    private cloud.jengu.dbo.work.RunChain.Link linkFor(Run run, String reference) {
        String previous = heads.get(run.key());
        if (previous == null) {
            throw new IllegalStateException(tenant + ": '" + identity.name()
                    + "' holds no head for run '" + run.key() + "' to chain an opening to");
        }
        String link = cloud.jengu.dbo.work.RunChain.accessLink(previous, run.key(), reference,
                identity.name());
        String signature = signing == null ? null
                : cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                        link.getBytes(StandardCharsets.UTF_8), signing);
        return new cloud.jengu.dbo.work.RunChain.Link("access", previous, link, identity.name(),
                reference, signature);
    }

    /**
     * Opens each document with the key held here and says so home, one
     * document at a time and the saying before the handing on: a document
     * the service receives is one whose opening the tenant already holds.
     */
    private Map<String, StoredObject> open(cloud.jengu.dbo.work.SealedWork work, Run run) {
        Map<String, StoredObject> resolved = new LinkedHashMap<>();
        // The manifest says where the chain stands; every opening from here
        // commits to that, then to the one before it.
        if (work.manifest().head() != null) {
            heads.put(run.key(), work.manifest().head());
        }
        for (cloud.jengu.dbo.work.SealedPayload payload : work.payload()) {
            StoredObject document;
            try {
                document = payload.open(identity.name(), holding);
            } catch (java.security.GeneralSecurityException cannot) {
                throw new IllegalStateException(tenant + ": '" + identity.name()
                        + "' cannot open " + payload.reference() + " with the key it holds", cannot);
            }
            opened(run, payload.reference(), linkFor(run, payload.reference()));
            resolved.put(payload.slot(), document);
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    /** Who is asking and who is working — on every verb, because the surface is stateless. */
    private Map<String, Object> verb() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(LaneVerbs.PARTICIPANT, participant);
        body.put(LaneVerbs.IDENTITY, RecordWire.encode(identity));
        if (boundTo != null) {
            body.put(LaneVerbs.ENTITLED_STEPS, RecordWire.encode(List.copyOf(boundTo)));
        }
        return body;
    }

    /** The verb's answer, or the refusal it was met with. */
    private Object post(LaneVerbs verb, Map<String, Object> body) {
        Reply reply = transport.post(verb, RecordWire.write(body));
        Object envelope = reply.body() == null || reply.body().isEmpty()
                ? Map.of() : RecordWire.read(reply.body());
        if (reply.status() >= 500) {
            // 5xx is the store failing to answer, never a decision about the
            // asker — including the ambiguous ones, which is the point of
            // drawing the line where HTTP already draws it, whatever the wire.
            throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                    tenant + ": " + verb.path() + " did not complete ("
                            + reply.status() + ") — " + reason(envelope));
        }
        if (reply.status() != 200) {
            throw new IllegalStateException(tenant + ": " + verb.path() + " refused ("
                    + reply.status() + ") — " + reason(envelope));
        }
        return envelope instanceof Map<?, ?> map ? map.get(LaneVerbs.RESULT) : null;
    }

    private static String reason(Object envelope) {
        Object reason = envelope instanceof Map<?, ?> map ? map.get(LaneVerbs.REASON) : null;
        return reason == null ? "no reason given" : String.valueOf(reason);
    }
}
