package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A tenant's lane, held by a host that reaches the store over HTTP.
 *
 * <p>The answer to "where does a cloud get a {@code Lane}". It implements the
 * participation protocol against {@link LaneHandler} on the tenant's private
 * surface, and a runner cannot tell it from the in-process one — which is the
 * contract {@code Lane} states and the reason the interface has no verb only
 * the in-process side could serve.
 *
 * <p><b>Two things are answered without asking.</b> {@link #tenant()} and
 * {@link #identity()} are what this lane was built with, so they cost no
 * round trip and cannot fail while the link is down. Everything else is a
 * verb.
 *
 * <p><b>A refusal arrives as a refusal.</b> The surface answers 409 with the
 * lane's own reason and this rethrows it, so a run this identity has not
 * claimed reads here exactly as it reads in-process. A link that is simply
 * down throws too, and says so differently — the two need different recovery,
 * and a lane that returned an empty list for both would hide the one that is
 * worth fixing.
 *
 * <p><b>The token is fetched per call, never held.</b> A lane outlives an
 * access token, and a host that captured one would start failing after an
 * hour in a way that looks like the store going away.
 */
public final class HttpLane implements Lane {

    private final URI base;
    private final Set<String> boundTo;
    private final Supplier<String> bearer;
    private final String tenant;
    private final String participant;
    private final Executor identity;
    private final HttpClient http;
    /** The private half of this participant's enrolment keypair, or null for one that offered none. */
    private final java.security.PrivateKey holding;

    /**
     * @param base        the tenant's lane surface, e.g.
     *                    {@code https://dbo/t/hogwarts/work}
     * @param bearer      the credential to present, asked for per call
     * @param tenant      which tenant this lane serves — the runner's key
     * @param participant this participant's feed cursor, its own and nobody
     *                    else's: two participants sharing one would ack each
     *                    other's work
     * @param identity    what claims and reports over this lane
     */
    public static HttpLane to(URI base, Supplier<String> bearer, String tenant,
            String participant, Executor identity) {
        return new HttpLane(base, bearer, tenant, participant, identity, null,
                HttpClient.newHttpClient());
    }

    /**
     * The same, bounded to what this participant was granted.
     *
     * <p>For a host serving a lane on behalf of somebody it authenticated
     * itself: the credential is the host's and covers the tenant, and the
     * reach that belongs on the lane is the participant's. The surface
     * intersects the two, so this can only narrow — a step the host does not
     * hold cannot be asked into existence here.
     */
    public static HttpLane boundedTo(URI base, Supplier<String> bearer, String tenant,
            String participant, Executor identity, Set<String> steps) {
        return new HttpLane(base, bearer, tenant, participant, identity, Set.copyOf(steps),
                HttpClient.newHttpClient());
    }

    /**
     * A lane for a participant that offered a key at enrolment and holds the
     * private half here. Its inputs arrive sealed, are opened on this side,
     * and each opening is reported home before the document is handed on —
     * the two are one act, and neither is optional.
     */
    public static HttpLane holding(URI base, Supplier<String> bearer, String tenant,
            String participant, Executor identity, java.security.PrivateKey privateKey) {
        return new HttpLane(base, bearer, tenant, participant, identity, null,
                HttpClient.newHttpClient(), privateKey);
    }

    public HttpLane(URI base, Supplier<String> bearer, String tenant, String participant,
            Executor identity, Set<String> boundTo, HttpClient http) {
        this(base, bearer, tenant, participant, identity, boundTo, http, null);
    }

    public HttpLane(URI base, Supplier<String> bearer, String tenant, String participant,
            Executor identity, Set<String> boundTo, HttpClient http,
            java.security.PrivateKey holding) {
        this.holding = holding;
        this.base = base;
        this.bearer = bearer;
        this.tenant = tenant;
        this.participant = participant;
        this.identity = identity;
        this.boundTo = boundTo;
        this.http = http;
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
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        post(LaneVerbs.CLOSED, body);
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
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        return RecordWire.decode(post(LaneVerbs.SEALED, body),
                cloud.jengu.dbo.work.SealedWork.class);
    }

    @Override
    public void opened(Run run, String reference) {
        Map<String, Object> body = verb();
        body.put(LaneVerbs.RUN, RecordWire.encode(run));
        body.put(LaneVerbs.REFERENCE, reference);
        post(LaneVerbs.OPENED, body);
    }

    /**
     * Opens each document with the key held here and says so home, one
     * document at a time and the saying before the handing on: a document
     * the service receives is one whose opening the tenant already holds.
     */
    private Map<String, StoredObject> open(cloud.jengu.dbo.work.SealedWork work, Run run) {
        Map<String, StoredObject> resolved = new LinkedHashMap<>();
        for (cloud.jengu.dbo.work.SealedPayload payload : work.payload()) {
            StoredObject document;
            try {
                document = payload.open(identity.name(), holding);
            } catch (java.security.GeneralSecurityException cannot) {
                throw new IllegalStateException(tenant + ": '" + identity.name()
                        + "' cannot open " + payload.reference() + " with the key it holds", cannot);
            }
            opened(run, payload.reference());
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
        URI target = base.resolve(base.getPath().endsWith("/")
                ? verb.path() : base.getPath() + "/" + verb.path());
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(RecordWire.write(body),
                        StandardCharsets.UTF_8));
        String token = bearer.get();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException unreachable) {
            // Not a decision about the caller: the far side never said
            // anything, so this is retryable and must not read as a refusal
            // (§7.9).
            throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                    tenant + ": the lane surface did not answer '" + verb.path() + "'", unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                    tenant + ": interrupted on '" + verb.path() + "'", interrupted);
        }
        Object envelope = response.body() == null || response.body().isEmpty()
                ? Map.of() : RecordWire.read(response.body());
        if (response.statusCode() >= 500) {
            // 5xx is the store failing to answer, never a decision about the
            // asker — including the ambiguous ones, which is the point of
            // drawing the line where HTTP already draws it.
            throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                    tenant + ": " + verb.path() + " did not complete ("
                            + response.statusCode() + ") — " + reason(envelope));
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(tenant + ": " + verb.path() + " refused ("
                    + response.statusCode() + ") — " + reason(envelope));
        }
        return envelope instanceof Map<?, ?> map ? map.get(LaneVerbs.RESULT) : null;
    }

    private static String reason(Object envelope) {
        Object reason = envelope instanceof Map<?, ?> map ? map.get(LaneVerbs.REASON) : null;
        return reason == null ? "no reason given" : String.valueOf(reason);
    }
}
