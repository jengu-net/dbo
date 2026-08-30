package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant's participation surface, for a host that is not the container
 * (#154).
 *
 * <p><b>Why this exists.</b> {@code Lane.inProcess} needs {@code Runs} and a
 * {@code ChangeFeed} — the store's internals — which is right for a host that
 * <em>is</em> the container: an appliance running dbo in-JVM builds a real
 * lane over its own store. A cloud does not. There dbo is its own deployment,
 * precisely so the application never holds {@code CREATE DATABASE}, and the
 * consuming JVM has no {@code Runs} to build a lane from at all — while being
 * the side that <em>serves</em> work to the appliances. This is the door it
 * comes in by: the same twelve verbs, over the private surface it already
 * reaches the tenant on.
 *
 * <p><b>The lane, not the store.</b> What is offered here is deliberately no
 * wider than {@link Lane}. Nothing on this surface takes a reference, hands
 * out a store handle or reads an object the work does not name, because a
 * widened primitive is available to every caller with the scope, forever. A
 * host that reaches the store over HTTP gets exactly what a host that holds
 * it in-process gets — and run semantics stay in one place, since every verb
 * here lands on a real {@code Lane} the tenant built.
 *
 * <p><b>The credential decides the reach; the request decides who is
 * working.</b> An entitlement is never a claim the caller makes — it is
 * derived from the token, upstream of this class, and handed in with the
 * grant. What the request does say is which participant's cursor to read and
 * which executor is claiming, and a credential bounded to some steps may name
 * only itself: an executor identity is what {@code inputs} checks a claim
 * against, so a bounded credential free to spell any name could read the
 * inputs of runs it was never entitled to. A credential that is the tenant
 * may name any executor, which is what lets a cloud serve a lane on behalf
 * of the appliance it has already authenticated.
 *
 * <p><b>A refusal is answered, never dropped.</b> A lane refuses — a run this
 * identity has not claimed, a step it was not granted, an introduction into a
 * lane that records none — and that comes back as 409 with its reason, so the
 * far side can tell a refusal from a link that went quiet. Only one of the
 * two is worth retrying.
 */
public final class LaneHandler implements HttpHandler {

    /** What the token turned out to be — a grant, or the denial to answer with. */
    public sealed interface Access permits Grant, Denied {
    }

    /**
     * A credential's reach, decided where credentials are understood.
     *
     * @param clientId    who presented it — the only executor name a bounded
     *                    credential may claim as
     * @param entitlement what it covers, stated rather than defaulted (#77)
     * @param isTheTenant whether this credential is the tenant itself, and so
     *                    may serve a lane in another participant's name
     */
    public record Grant(String clientId, Lane.Entitlement entitlement, boolean isTheTenant)
            implements Access {
    }

    /** Why not, in the terms HTTP will answer in. */
    public record Denied(int status, String wwwAuthenticate, String reason) implements Access {
    }

    /** Where a token becomes a reach. The store's authority implements it. */
    @FunctionalInterface
    public interface Grants {
        Access of(String authorizationHeader);
    }

    /**
     * The host's own lanes. Given who is working and what they may reach, the
     * host builds the in-process lane it would have built anyway — so this
     * surface adds a transport and never a second implementation of the
     * participation protocol.
     */
    @FunctionalInterface
    public interface Lanes {
        Lane laneFor(String participant, Executor identity, Lane.Entitlement entitlement);
    }

    private final String basePath;
    private final Grants grants;
    private final Lanes lanes;

    public LaneHandler(String basePath, Grants grants, Lanes lanes) {
        this.basePath = basePath.endsWith("/")
                ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.grants = grants;
        this.lanes = lanes;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath().substring(basePath.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            Optional<LaneVerbs> verb = LaneVerbs.ofPath(relative);
            if (verb.isEmpty()) {
                fail(exchange, 404, "no such lane verb: " + relative);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                // Every verb is an act on the tenant's work, including the
                // ones that only read: poll moves a cursor, and inputs is
                // answered against a claim.
                fail(exchange, 405, "the lane's verbs are posted");
                return;
            }
            Access access = grants.of(exchange.getRequestHeaders().getFirst("Authorization"));
            if (access instanceof Denied denied) {
                if (denied.wwwAuthenticate() != null) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", denied.wwwAuthenticate());
                }
                fail(exchange, denied.status(), denied.reason());
                return;
            }
            Grant grant = (Grant) access;
            Object body = body(exchange);
            String participant = string(body, LaneVerbs.PARTICIPANT);
            Executor identity = RecordWire.decode(field(body, LaneVerbs.IDENTITY), Executor.class);
            if (participant == null || identity == null || identity.name() == null) {
                fail(exchange, 400, "a lane verb says which participant is asking and "
                        + "which executor is working");
                return;
            }
            if (!grant.isTheTenant() && !identity.name().equals(grant.clientId())) {
                fail(exchange, 403, "'" + grant.clientId() + "' may work as itself, and this "
                        + "asked to work as '" + identity.name() + "'");
                return;
            }
            answer(exchange, verb.get(), lanes.laneFor(participant, identity,
                    narrowed(grant.entitlement(), body)), body);
        } catch (IllegalStateException refused) {
            // What a lane refuses, said as a refusal — the reason travels,
            // because the far side's recovery depends on which refusal it is.
            refuse(exchange, String.valueOf(refused.getMessage()));
        } catch (IllegalArgumentException malformed) {
            fail(exchange, 400, String.valueOf(malformed.getMessage()));
        } catch (RuntimeException failed) {
            fail(exchange, 500, "the verb did not complete");
        } finally {
            exchange.close();
        }
    }

    private void answer(HttpExchange exchange, LaneVerbs verb, Lane lane, Object body)
            throws IOException {
        switch (verb) {
            case POLL -> respond(exchange, lane.poll(
                    Set.copyOf(RecordWire.decodeList(field(body, LaneVerbs.STEPS), String.class)),
                    (int) number(body, LaneVerbs.LIMIT)));
            // Optional on the wire is the empty answer, not a 404: nobody took
            // it is an outcome of the claim race, and the loser takes the next
            // run rather than treating it as an error.
            case CLAIM -> respond(exchange,
                    lane.claim(run(body), holdFor(body)).orElse(null));
            case CHECKPOINT -> respond(exchange,
                    lane.checkpoint(run(body), counts(body), holdFor(body)));
            case MILESTONE -> respond(exchange, lane.milestone(run(body),
                    string(body, LaneVerbs.MILESTONE_NAME), counts(body), holdFor(body)));
            case RELEASED -> {
                lane.released(run(body), string(body, LaneVerbs.REASON));
                respond(exchange, null);
            }
            case CLOSED -> {
                lane.closed(run(body));
                respond(exchange, null);
            }
            case RELEASE_LAPSED -> respond(exchange, (long) lane.releaseLapsed());
            case DECLARE -> {
                lane.declare(declared(body));
                respond(exchange, null);
            }
            case WITHDRAW -> {
                lane.withdraw(declared(body));
                respond(exchange, null);
            }
            case INTRODUCE -> {
                lane.introduce(RecordWire.decode(field(body, LaneVerbs.STEP),
                        StepDeclaration.class));
                respond(exchange, null);
            }
            // The one read, and the lane guards it: a run this identity has
            // not claimed is refused there rather than filtered here.
            case INPUTS -> respond(exchange, lane.inputs(run(body)));
        }
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

    private static Object body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<String, Object>();
        }
        return RecordWire.read(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Object field(Object body, String name) {
        return body instanceof Map<?, ?> map ? map.get(name) : null;
    }

    private static String string(Object body, String name) {
        Object value = field(body, name);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Object body, String name) {
        Object value = field(body, name);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** Every answer is the same envelope, so an empty one is still an answer. */
    private static void respond(HttpExchange exchange, Object result) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(LaneVerbs.RESULT, RecordWire.encode(result));
        send(exchange, 200, RecordWire.write(envelope));
    }

    private static void refuse(HttpExchange exchange, String reason) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
        envelope.put(LaneVerbs.REASON, reason);
        send(exchange, 409, RecordWire.write(envelope));
    }

    private static void fail(HttpExchange exchange, int status, String reason)
            throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(LaneVerbs.REFUSED, Boolean.TRUE);
        envelope.put(LaneVerbs.REASON, reason);
        send(exchange, status, RecordWire.write(envelope));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
