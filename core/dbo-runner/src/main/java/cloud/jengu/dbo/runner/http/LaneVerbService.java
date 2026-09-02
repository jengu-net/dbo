package cloud.jengu.dbo.runner.http;

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
 * The lane's verbs as a service, behind whatever door carries them.
 *
 * <p>A verb is a body and an answer, and who may ask is decided the same way
 * whichever way the body arrived: the credential is validated through the
 * tenant's authority, the identity it asks to work as must be its own unless
 * it is the tenant, and the reach is the credential's narrowed by what was
 * asked. HTTP is one door; the store's own stream is another; both hand a
 * body here and carry the answer back, and neither decides anything on the
 * way.
 */
public final class LaneVerbService {

    /** What a verb comes to: an answer, a refusal the lane made, or a door that would not open. */
    public sealed interface Answer permits Answer.Ok, Answer.Refused, Answer.Denied {

        /** The verb's result, as a wire tree. */
        record Ok(Object result) implements Answer {}

        /** What the lane refused, in its own words — the far side's recovery depends on which. */
        record Refused(String reason) implements Answer {}

        /** The door did not open: no credential, a bad one, one without reach, or a malformed ask. */
        record Denied(int status, String wwwAuthenticate, String reason) implements Answer {}
    }

    private final LaneHandler.Grants grants;
    private final LaneHandler.Lanes lanes;

    public LaneVerbService(LaneHandler.Grants grants, LaneHandler.Lanes lanes) {
        this.grants = grants;
        this.lanes = lanes;
    }

    /** One verb, from whoever carries the authorization named, on the body given. */
    public Answer serve(String authorization, LaneVerbs verb, Object body) {
        LaneHandler.Access access = grants.of(authorization);
        if (access instanceof LaneHandler.Denied denied) {
            return new Answer.Denied(denied.status(), denied.wwwAuthenticate(), denied.reason());
        }
        LaneHandler.Grant grant = (LaneHandler.Grant) access;
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
        try {
            return new Answer.Ok(answer(verb, lanes.laneFor(participant, identity,
                    narrowed(grant.entitlement(), body)), body));
        } catch (IllegalStateException refused) {
            // What a lane refuses, said as a refusal — the reason travels,
            // because the far side's recovery depends on which refusal it is.
            return new Answer.Refused(String.valueOf(refused.getMessage()));
        } catch (IllegalArgumentException malformed) {
            return new Answer.Denied(400, null, String.valueOf(malformed.getMessage()));
        }
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
                lane.released(run(body), string(body, LaneVerbs.REASON));
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
            case RELEASE_LAPSED -> (long) lane.releaseLapsed();
            case DECLARE -> {
                lane.declare(declared(body));
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
        List<String> steps = RecordWire.decodeList(asked, String.class).stream()
                .filter(credential::covers).toList();
        return Lane.Entitlement.ofSteps(steps.toArray(String[]::new));
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
