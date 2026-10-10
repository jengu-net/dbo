package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.runner.transport.LaneVerbs;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Wakeups;
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

    /**
     * Moves one verb's body across and brings the reply back; decides nothing
     * on the way.
     *
     * <p>Every verb waits for its answer. A runner says a declaration once and
     * believes it landed when the answer says so, so a carrier that could drop
     * one without saying would leave a worker declared nowhere.
     */
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

    /**
     * Nothing, because plain HTTP has no way back: this lane asks and is
     * answered, and the far side holds no channel on which to say that work
     * appeared. A runner over one of these waits out its tick, which is what
     * every runner did before a lane could say anything at all.
     */
    @Override
    public Optional<Wakeups> wakeups() {
        return Optional.empty();
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
    public void released(Run run, String reason, cloud.jengu.dbo.work.Failure failure) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.REASON, reason);
        if (failure != null) {
            body.put(LaneVerbs.FAILURE, failure.wire());
        }
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
    public Run committed(Run run, String head, List<cloud.jengu.dbo.runner.Outcome.Write> result) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        // As closed(): a participant that signs commits to the head it last
        // made or saw, whether or not the runner passed one.
        String committedTo = head != null ? head : signing == null ? null : heads.get(run.key());
        if (committedTo != null) {
            body.put(LaneVerbs.HEAD, committedTo);
        }
        body.put(LaneVerbs.WRITES, RecordWire.encode(List.copyOf(result)));
        Run ended = RecordWire.decode(post(LaneVerbs.COMMITTED, body), Run.class);
        heads.remove(run.key());
        return ended;
    }

    @Override
    public void reopen(Run run, String because) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.REASON, because);
        post(LaneVerbs.REOPEN, body);
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

    /**
     * Asked, not told: a node refuses statistics over its limit, and a worker
     * that never heard so would go on sending what nobody reads.
     */
    @Override
    public void heartbeat(Map<String, Object> statistics) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.STATISTICS, RecordWire.encode(
                statistics == null ? Map.of() : statistics));
        post(LaneVerbs.HEARTBEAT, body);
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
    public Map<String, java.util.List<StoredObject>> inputs(Run run) {
        if (holding != null) {
            // What this participant holds decides how its work arrives. The
            // far side refuses the clear verb to a keyed identity anyway;
            // asking sealed first is the runner not needing to know that.
            return open(sealed(run), run);
        }
        return inputsInTheClear(run);
    }

    /** The clear verb as such — what a keyless participant asks, and what a keyed one never does. */
    protected Map<String, java.util.List<StoredObject>> inputsInTheClear(Run run) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        // A LIST PER SLOT, because a slot may repeat. Walked rather than
        // decoded in one call: the answer is a map of LISTS, and asking the
        // wire for a map of one type reads each list as that type — which is
        // the flattening a repeat introduces, and it fails loudly rather than
        // quietly only because nothing can decode a list as a record.
        Map<String, java.util.List<StoredObject>> resolved = new LinkedHashMap<>();
        if (post(LaneVerbs.INPUTS, body) instanceof Map<?, ?> slots) {
            slots.forEach((slot, node) -> resolved.put(String.valueOf(slot),
                    RecordWire.decodeList(node, StoredObject.class)));
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    @Override
    public cloud.jengu.dbo.work.SealedPayload identified(Run run, String reference,
            String purpose) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.REFERENCE, reference);
        body.put(LaneVerbs.PURPOSE, purpose);
        return RecordWire.decode(post(LaneVerbs.IDENTIFIED, body),
                cloud.jengu.dbo.work.SealedPayload.class);
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
    private Map<String, java.util.List<StoredObject>> open(
            cloud.jengu.dbo.work.SealedWork work, Run run) {
        Map<String, java.util.List<StoredObject>> resolved = new LinkedHashMap<>();
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
            // ACCUMULATED, not replaced. Payloads arrive in the order the
            // slot was filled, and a repeating slot is several of them under
            // one name — putting each would leave the last and lose the rest,
            // silently, which is the shape of defect a repeat introduces.
            resolved.computeIfAbsent(payload.slot(), slot -> new java.util.ArrayList<>())
                    .add(document);
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    /**
     * One of the tenant's feeds, as a place of it reads them: over this lane's
     * carrier and credential, from the position the tenant keeps for this
     * participant.
     *
     * <p>A {@link cloud.jengu.dbo.core.api.feed.ChangeFeed}, so the engine that
     * keeps a dependent up to date reads it as it reads a feed beside it.
     * Only what that engine asks of a feed crosses: a read from where this
     * place stands, and an acknowledgement. The position is the tenant's to
     * keep, so the consumer the engine names is not sent — the participant
     * is the place, and the tenant names the position after it.
     *
     * @param domain {@link cloud.jengu.dbo.runner.transport.Place#RECORDS} or
     *               {@link cloud.jengu.dbo.runner.transport.Place#DEFINITIONS}
     */
    public cloud.jengu.dbo.core.api.feed.ChangeFeed placeFeed(String domain) {
        return new PlaceFeed(domain, false);
    }

    /**
     * The tenant's definitions without what it took from its face root: for
     * a place that takes its face from a root of its own.
     */
    public cloud.jengu.dbo.core.api.feed.ChangeFeed definitionsWithoutTheFace() {
        return new PlaceFeed(cloud.jengu.dbo.runner.transport.Place.DEFINITIONS, true);
    }

    /**
     * The tenant this lane reaches, as a place of it reads it: its
     * declaration and its two feeds, on this lane's carrier and credential.
     * The credential must hold a place for any of it to answer.
     */
    public cloud.jengu.dbo.runner.transport.Origin origin() {
        cloud.jengu.dbo.core.api.feed.ChangeFeed records =
                placeFeed(cloud.jengu.dbo.runner.transport.Place.RECORDS);
        cloud.jengu.dbo.core.api.feed.ChangeFeed definitions =
                placeFeed(cloud.jengu.dbo.runner.transport.Place.DEFINITIONS);
        cloud.jengu.dbo.core.api.feed.ChangeFeed withoutTheFace = definitionsWithoutTheFace();
        return new cloud.jengu.dbo.runner.transport.Origin() {
            @Override
            public cloud.jengu.dbo.core.api.feed.ChangeFeed definitionsWithoutTheFace() {
                return withoutTheFace;
            }

            @Override
            public String tenant() {
                return tenant;
            }

            @Override
            public String declaration() {
                Object answer = post(LaneVerbs.DECLARATION, verb());
                Object text = answer instanceof Map<?, ?> map
                        ? map.get(LaneVerbs.DECLARATION_TEXT) : null;
                if (text == null) {
                    throw new IllegalStateException(tenant + ": the declaration came back empty");
                }
                return String.valueOf(text);
            }

            @Override
            public cloud.jengu.dbo.core.api.feed.ChangeFeed records() {
                return records;
            }

            @Override
            public cloud.jengu.dbo.core.api.feed.ChangeFeed definitions() {
                return definitions;
            }
        };
    }

    private final class PlaceFeed implements cloud.jengu.dbo.core.api.feed.ChangeFeed {

        private final String domain;
        private final boolean withoutFace;

        private PlaceFeed(String domain, boolean withoutFace) {
            this.domain = domain;
            this.withoutFace = withoutFace;
        }

        @Override
        public cloud.jengu.dbo.core.api.feed.FeedChunk<cloud.jengu.dbo.core.api.feed.FeedItem> readFor(
                String consumer, int limit, cloud.jengu.dbo.core.api.feed.FeedSelection wanted) {
            Map<String, Object> body = verb();
            body.put(LaneVerbs.DOMAIN, domain);
            body.put(LaneVerbs.TYPES, RecordWire.encode(List.copyOf(wanted.types())));
            body.put(LaneVerbs.LIMIT, limit);
            if (withoutFace) {
                body.put(LaneVerbs.WITHOUT_FACE, true);
            }
            Object answer = post(LaneVerbs.FEED_READ, body);
            Map<?, ?> chunk = answer instanceof Map<?, ?> map ? map : Map.of();
            Object items = chunk.get(LaneVerbs.ITEMS);
            return new cloud.jengu.dbo.core.api.feed.FeedChunk<>(
                    items == null ? List.of() : RecordWire.decodeList(items,
                            cloud.jengu.dbo.core.api.feed.FeedItem.class),
                    chunk.get(LaneVerbs.CURSOR) == null
                            ? null : String.valueOf(chunk.get(LaneVerbs.CURSOR)),
                    Boolean.TRUE.equals(chunk.get(LaneVerbs.DRAINED)));
        }

        @Override
        public cloud.jengu.dbo.core.api.feed.FeedChunk<cloud.jengu.dbo.core.api.feed.FeedItem> readFor(
                String consumer, int limit) {
            return readFor(consumer, limit, cloud.jengu.dbo.core.api.feed.FeedSelection.EVERYTHING);
        }

        @Override
        public void ack(String consumer, String cursor) {
            if (cursor == null) {
                return;
            }
            Map<String, Object> body = verb();
            body.put(LaneVerbs.DOMAIN, domain);
            body.put(LaneVerbs.CURSOR, cursor);
            post(LaneVerbs.FEED_ACK, body);
        }

        // What a place may not ask, refused by name. Its position is the
        // tenant's and moves forward only: a place that could rewind it, or
        // read from a position of its choosing, could read past what it has
        // been told to apply.

        @Override
        public cloud.jengu.dbo.core.api.feed.FeedChunk<cloud.jengu.dbo.core.api.feed.FeedItem> read(
                String cursor, int limit) {
            throw new UnsupportedOperationException(tenant + ": a place reads from where it "
                    + "stands, never from a position of its choosing");
        }

        @Override
        public void resetConsumer(String consumer, String cursor) {
            throw new UnsupportedOperationException(tenant + ": a place's position is the "
                    + "tenant's to keep, and moves forward only");
        }

        @Override
        public String cursorOf(String consumer) {
            throw new UnsupportedOperationException(tenant + ": a place's position is kept "
                    + "by the tenant, and read there");
        }

        @Override
        public long lag(String consumer) {
            throw new UnsupportedOperationException(tenant + ": how far a place is behind is "
                    + "read at the tenant, where its position is kept");
        }
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
            String said = tenant + ": " + verb.path() + " refused (" + reply.status() + ") — "
                    + reason(envelope);
            if (envelope instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(LaneVerbs.LOST))) {
                // The run is no longer this participant's, said as the
                // in-process lane says it, so a runner across a wire drops
                // the work as one beside the tenant does.
                throw new cloud.jengu.dbo.work.Runs.NotHeld(said);
            }
            throw new IllegalStateException(said);
        }
        return envelope instanceof Map<?, ?> map ? map.get(LaneVerbs.RESULT) : null;
    }

    private static String reason(Object envelope) {
        Object reason = envelope instanceof Map<?, ?> map ? map.get(LaneVerbs.REASON) : null;
        return reason == null ? "no reason given" : String.valueOf(reason);
    }
}
