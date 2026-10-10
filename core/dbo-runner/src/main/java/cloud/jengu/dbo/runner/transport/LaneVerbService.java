package cloud.jengu.dbo.runner.transport;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lane's verbs, dispatched for whatever transport carried them.
 *
 * <p><b>The transport SPI's first layer.</b> A transport hands this a caller
 * and a verb: what the caller presented — a bearer token, or a signature over
 * the ask's own bytes — and the body as it arrived. The store authenticates
 * the caller through the tenant's authority, authorises it — the identity it
 * asks to work as must be its own unless it is the tenant, and its reach is
 * the credential's narrowed by what was asked — and dispatches the verb to
 * the tenant's own lane. A transport decides none of it on the way, so a
 * consumer carrying verbs over a socket of its own reaches exactly what HTTP
 * and the store's stream reach.
 *
 * <p>One per tenant, from the store that serves it; HTTP is the reference
 * transport, and the stream is the second.
 */
public final class LaneVerbService {

    /** What a verb comes to: an answer, a refusal the lane made, or a door that would not open. */
    public sealed interface Answer permits Answer.Ok, Answer.Refused, Answer.Denied {

        /** The verb's result, as a wire tree. */
        record Ok(Object result) implements Answer {}

        /**
         * What the lane refused, in its own words — the far side's recovery
         * depends on which.
         *
         * @param lost whether it was refused because the run is no longer the
         *             asker's, which the far side answers by dropping the work
         */
        record Refused(String reason, boolean lost) implements Answer {

            public Refused(String reason) {
                this(reason, false);
            }
        }

        /** The door did not open: no credential, a bad one, one without reach, or a malformed ask. */
        record Denied(int status, String wwwAuthenticate, String reason) implements Answer {}
    }

    private final Grants grants;
    private final SignedGrants signed;
    private final Lanes lanes;
    private final Places places;

    /**
     * @param grants how a token is read, or null where no token is taken
     * @param signed how a signed ask is read, or null where none is taken
     */
    public LaneVerbService(Grants grants, SignedGrants signed, Lanes lanes) {
        this(grants, signed, lanes, null);
    }

    /**
     * @param places what a place reads, or null where the tenant serves none
     */
    public LaneVerbService(Grants grants, SignedGrants signed, Lanes lanes, Places places) {
        this.grants = grants;
        this.signed = signed;
        this.lanes = lanes;
        this.places = places;
    }

    /** One verb, from whoever carries the authorization named, on the body given. */
    public Answer serve(String authorization, LaneVerbs verb, Object body) {
        return serve(grants == null
                ? new Access.Denied(401, null, "this tenant takes no token here")
                : grants.of(authorization), verb, body);
    }

    /**
     * One verb, from the participant whose enrolled key signed the bytes
     * given — which must be the bytes that travelled, so an ask altered on
     * the way fails as a forgery.
     */
    public Answer serveSigned(String participant, byte[] signedOver, String signature,
            LaneVerbs verb, Object body) {
        return serve(signed == null
                ? new Access.Denied(401, null, "this tenant takes no signed ask here")
                : signed.of(participant, signedOver, signature), verb, body);
    }

    /** One verb, from whoever the store has already decided is asking. */
    public Answer serve(Access access, LaneVerbs verb, Object body) {
        if (access instanceof Access.Denied denied) {
            return new Answer.Denied(denied.status(), denied.wwwAuthenticate(), denied.reason());
        }
        Access.Grant grant = (Access.Grant) access;
        String participant = string(body, LaneVerbs.PARTICIPANT);
        Executor identity = RecordWire.decode(field(body, LaneVerbs.IDENTITY), Executor.class);
        if (participant == null || identity == null || identity.name() == null) {
            return new Answer.Denied(400, null, "a lane verb says which participant is asking and "
                    + "which executor is working");
        }
        if (!grant.isTheTenant() && !identity.name().equals(grant.clientId())) {
            return new Answer.Denied(403, null, "'" + grant.clientId() + "' may work as itself, "
                    + "and this asked to work as '" + identity.name() + "'");
        }
        // The actor on everything a verb records — a hop, an opening — is
        // the credential the authority validated, never the participant
        // named in the body and never the machinery's own name. Set for the
        // verb and cleared after it, because a door's thread is reused.
        if (verb == LaneVerbs.FEED_READ || verb == LaneVerbs.FEED_ACK) {
            return placed(grant, participant, verb, body);
        }
        cloud.jengu.dbo.core.api.Caller.set(grant.clientId());
        try {
            return new Answer.Ok(answer(verb, lanes.laneFor(participant, identity,
                    narrowed(grant.entitlement(), body)), body));
        } catch (cloud.jengu.dbo.work.Runs.NotHeld lost) {
            // A refusal like any other, marked: the asker's work is somebody
            // else's now, and a holder that cannot tell this from its own
            // failure releases what it no longer holds.
            return new Answer.Refused(String.valueOf(lost.getMessage()), true);
        } catch (IllegalStateException refused) {
            // What a lane refuses, said as a refusal — the reason travels,
            // because the far side's recovery depends on which refusal it is.
            return new Answer.Refused(String.valueOf(refused.getMessage()));
        } catch (IllegalArgumentException malformed) {
            return new Answer.Denied(400, null, String.valueOf(malformed.getMessage()));
        } finally {
            cloud.jengu.dbo.core.api.Caller.clear();
        }
    }

    /**
     * A place's read or acknowledgement, under the participant's own position.
     *
     * <p>The participant is the one the verb names, which the identity check
     * above has already tied to the credential: a place reads as itself, and
     * a tenant credential speaking for another participant reads as that one.
     */
    private Answer placed(Access.Grant grant, String participant, LaneVerbs verb, Object body) {
        if (!grant.holdsAPlace()) {
            return new Answer.Denied(403, null, "'" + grant.clientId() + "' holds no place "
                    + "here, and only a place reads what the tenant replicates");
        }
        java.util.Optional<Place> served = places == null
                ? java.util.Optional.empty() : places.place();
        if (served.isEmpty()) {
            return new Answer.Refused("this tenant serves no place yet");
        }
        String domain = string(body, LaneVerbs.DOMAIN);
        if (!Place.RECORDS.equals(domain) && !Place.DEFINITIONS.equals(domain)) {
            return new Answer.Denied(400, null, "a place reads '" + Place.RECORDS + "' or '"
                    + Place.DEFINITIONS + "', and this asked for '" + domain + "'");
        }
        Place place = served.get();
        cloud.jengu.dbo.core.api.feed.ChangeFeed feed =
                Place.RECORDS.equals(domain) ? place.records() : place.definitions();
        String consumer = Place.consumerOf(participant, domain);
        try {
            if (verb == LaneVerbs.FEED_ACK) {
                String cursor = string(body, LaneVerbs.CURSOR);
                if (cursor == null) {
                    throw new IllegalArgumentException("an acknowledgement names the position "
                            + "it reached");
                }
                feed.ack(consumer, cursor);
                return new Answer.Ok(null);
            }
            Object named = field(body, LaneVerbs.TYPES);
            Set<String> asked = named == null ? Set.of()
                    : Set.copyOf(RecordWire.decodeList(named, String.class));
            Set<String> readable = Place.RECORDS.equals(domain)
                    ? readableOf(asked, place.readableRecords()) : asked;
            if (Place.RECORDS.equals(domain) && readable.isEmpty()) {
                // Nothing asked that a place may read. Answered as an empty
                // chunk at the same position, not a refusal: the far side
                // asked within its rights, there is simply nothing for it.
                return new Answer.Ok(Map.of(LaneVerbs.ITEMS, List.of(), LaneVerbs.DRAINED, true));
            }
            cloud.jengu.dbo.core.api.feed.FeedChunk<cloud.jengu.dbo.core.api.feed.FeedItem> chunk =
                    feed.readFor(consumer, chunkOf(number(body, LaneVerbs.LIMIT)),
                            cloud.jengu.dbo.core.api.feed.FeedSelection.ofTypes(readable));
            List<cloud.jengu.dbo.core.api.feed.FeedItem> items = new java.util.ArrayList<>();
            for (cloud.jengu.dbo.core.api.feed.FeedItem item : chunk.items()) {
                // KEPT, though the feed was asked to narrow: a feed that
                // cannot narrow answers with everything, and nothing outside
                // what a place may read leaves here whatever the feed did.
                if (!readable.isEmpty() && !readable.contains(item.typeName())) {
                    continue;
                }
                items.add(transported(item, place.grain()));
            }
            Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put(LaneVerbs.ITEMS, RecordWire.encode(items));
            answer.put(LaneVerbs.CURSOR, chunk.nextCursor());
            answer.put(LaneVerbs.DRAINED, chunk.drained());
            return new Answer.Ok(answer);
        } catch (IllegalStateException refused) {
            return new Answer.Refused(String.valueOf(refused.getMessage()));
        } catch (IllegalArgumentException malformed) {
            return new Answer.Denied(400, null, String.valueOf(malformed.getMessage()));
        }
    }

    /**
     * The most one read carries. A chunk is one answer in memory on both
     * sides and one message on the wire, so the far side's ask is a wish and
     * this is the bound.
     */
    private static final int MOST_IN_ONE_CHUNK = 500;

    private static int chunkOf(long asked) {
        return asked <= 0 ? MOST_IN_ONE_CHUNK : (int) Math.min(asked, MOST_IN_ONE_CHUNK);
    }

    /** What was asked, within what a place may read; everything it may read when nothing was named. */
    private static Set<String> readableOf(Set<String> asked, Set<String> readable) {
        if (asked.isEmpty()) {
            return readable;
        }
        Set<String> within = new java.util.LinkedHashSet<>(asked);
        within.retainAll(readable);
        return Set.copyOf(within);
    }

    /**
     * The item as it travels: a type the tenant stores in parts is put back
     * together from them here, where the parts are, so the far side applies
     * whole documents and never reaches into this tenant's tables.
     */
    private static cloud.jengu.dbo.core.api.feed.FeedItem transported(
            cloud.jengu.dbo.core.api.feed.FeedItem item, cloud.jengu.dbo.core.face.GrainCodec grain) {
        if (grain == null || item.payload() == null || !grain.handles(item.typeName())) {
            return item;
        }
        return new cloud.jengu.dbo.core.api.feed.FeedItem(item.seq(), item.objectId(),
                item.typeName(), item.versionId(), item.kind(), item.committedAt(),
                grain.forTransport(item.typeName(), item.payload()), item.deleted(),
                item.payloadVersion(), item.shape());
    }

    private static Object answer(LaneVerbs verb, Lane lane, Object body) {
        return switch (verb) {
            case POLL -> lane.poll(
                    Set.copyOf(RecordWire.decodeList(field(body, LaneVerbs.STEPS), String.class)),
                    (int) number(body, LaneVerbs.LIMIT));
            // Optional on the wire is the empty answer, not a 404: nobody took
            // it is an outcome of the claim race, and the loser takes the next
            // run rather than treating it as an error.
            case CLAIM -> lane.claim(run(body), holdFor(body)).orElse(null);
            case CHECKPOINT -> lane.checkpoint(run(body), counts(body), holdFor(body));
            case MILESTONE -> lane.milestone(run(body),
                    string(body, LaneVerbs.MILESTONE_NAME), counts(body), holdFor(body));
            case RELEASED -> {
                lane.released(run(body), string(body, LaneVerbs.REASON),
                        cloud.jengu.dbo.work.Failure.ofWire(string(body, LaneVerbs.FAILURE)));
                yield null;
            }
            case CLOSED -> {
                String head = string(body, LaneVerbs.HEAD);
                if (head == null) {
                    lane.closed(run(body));
                } else {
                    lane.closed(run(body), head);
                }
                yield null;
            }
            // The run comes back either way: closed over what it wrote, or
            // ended with the tenant's reason. A refused result is an answer
            // rather than a refusal of the verb, because the far side's
            // recovery is the opposite — it must not try again.
            case COMMITTED -> lane.committed(run(body), string(body, LaneVerbs.HEAD),
                    RecordWire.decodeList(field(body, LaneVerbs.WRITES),
                            cloud.jengu.dbo.runner.Outcome.Write.class));
            case REOPEN -> {
                lane.reopen(run(body), string(body, LaneVerbs.REASON));
                yield null;
            }
            case RELEASE_LAPSED -> (long) lane.releaseLapsed();
            case DECLARE -> {
                lane.declare(declared(body));
                yield null;
            }
            case HEARTBEAT -> {
                Object statistics = field(body, LaneVerbs.STATISTICS);
                if (statistics != null && !(statistics instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("a heartbeat's statistics are one JSON "
                            + "object, keyed by whoever contributed them");
                }
                lane.heartbeat(statistics == null ? Map.of() : statisticsOf(statistics));
                yield null;
            }
            case WITHDRAW -> {
                lane.withdraw(declared(body));
                yield null;
            }
            case ROUTES -> {
                lane.routes(RecordWire.decodeList(field(body, LaneVerbs.BEHIND),
                        cloud.jengu.dbo.work.Trackable.class));
                yield null;
            }
            case INTRODUCE -> {
                lane.introduce(RecordWire.decode(field(body, LaneVerbs.STEP),
                        StepDeclaration.class));
                yield null;
            }
            // The one read, and the lane guards it: a run this identity has
            // not claimed is refused there rather than filtered here.
            case INPUTS -> lane.inputs(run(body));
            case SEALED -> {
                Object recipients = field(body, LaneVerbs.RECIPIENTS);
                yield recipients == null ? lane.sealed(run(body))
                        : lane.sealed(run(body), RecordWire.decodeList(recipients, String.class));
            }
            case IDENTIFIED -> {
                String reference = string(body, LaneVerbs.REFERENCE);
                if (reference == null) {
                    throw new IllegalArgumentException("an identification names the document");
                }
                yield lane.identified(run(body), reference, string(body, LaneVerbs.PURPOSE));
            }
            case OPENED -> {
                String reference = string(body, LaneVerbs.REFERENCE);
                if (reference == null) {
                    throw new IllegalArgumentException("an opening names the document opened");
                }
                yield lane.opened(run(body), reference,
                        new cloud.jengu.dbo.work.RunChain.Link("access",
                                string(body, LaneVerbs.PREVIOUS), string(body, LaneVerbs.LINK),
                                string(body, LaneVerbs.AUTHOR), reference,
                                string(body, LaneVerbs.SIGNATURE)));
            }
            // Served beside the lane, never through it: a place is not a
            // participant act, and serve() takes them before a lane is built.
            case FEED_READ, FEED_ACK -> throw new IllegalStateException(
                    "'" + verb.path() + "' is a place's verb and is not dispatched to a lane");
        };
    }

    /**
     * The credential's reach, optionally narrowed by the asker.
     *
     * <p><b>Narrowing only, and it is an intersection rather than a
     * replacement.</b> A host that is the tenant may serve a lane to a
     * participant it has authenticated some other way, and the reach it means
     * is that participant's — not its own. Letting it say so is safe, because
     * it could always have asked for everything it holds; letting it say more
     * than it holds is not, so what is asked for is filtered through what the
     * credential covers rather than replacing it. An asker naming only steps
     * it does not hold gets a lane that offers nothing, which is the honest
     * outcome and not the same as an unbounded one.
     */
    private static Lane.Entitlement narrowed(Lane.Entitlement credential, Object body) {
        Object asked = field(body, LaneVerbs.ENTITLED_STEPS);
        if (asked == null) {
            return credential;
        }
        return credential.narrowedTo(RecordWire.decodeList(asked, String.class));
    }

    private static Run run(Object body) {
        Run run = RecordWire.decode(field(body, LaneVerbs.RUN), Run.class);
        if (run == null) {
            throw new IllegalArgumentException("this verb is about a run, and none was named");
        }
        return run;
    }

    private static Declarations.Declared declared(Object body) {
        Declarations.Declared declared =
                RecordWire.decode(field(body, LaneVerbs.DECLARED), Declarations.Declared.class);
        if (declared == null) {
            throw new IllegalArgumentException("this verb is about a declaration, "
                    + "and none was carried");
        }
        return declared;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> statisticsOf(Object tree) {
        return (Map<String, Object>) tree;
    }

    private static Duration holdFor(Object body) {
        return Duration.ofMillis(number(body, LaneVerbs.HOLD_FOR_MILLIS));
    }

    private static Map<String, Long> counts(Object body) {
        return RecordWire.decodeMap(field(body, LaneVerbs.COUNTS), Long.class);
    }

    static Object field(Object body, String name) {
        return body instanceof Map<?, ?> map ? map.get(name) : null;
    }

    static String string(Object body, String name) {
        Object value = field(body, name);
        return value == null ? null : String.valueOf(value);
    }

    static long number(Object body, String name) {
        Object value = field(body, name);
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
