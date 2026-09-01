package cloud.jengu.dbo.sync.http;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.sync.Lanes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A tenant's replication, driven by a host that reaches the store over HTTP
 *.
 *
 * <p>The answer to "where does a cloud get a {@code Lanes}". It carries the
 * seven verbs to {@link LanesHandler} on the tenant's private surface, and
 * every one lands on the real in-process {@code Lanes} the tenant built — so
 * epochs, mirroring, ordering and idempotence are decided in one place and
 * this side stays a transport.
 *
 * <p><b>One client serves both shapes.</b> An appliance running dbo in its own
 * JVM could construct {@code Lanes} directly, and does not need to: it already
 * reaches its own store over this surface for everything else, and a connector
 * written once for both ends is worth more than one round trip saved on one of
 * them.
 *
 * <p><b>The token is fetched per call, never held</b> — a lane outlives an
 * access token, and a connector that captured one would start failing after an
 * hour in a way that looks like the store going away.
 *
 * <p><b>Caught up is not readable from a cursor.</b> A lane's own bookkeeping
 * lands in the feed the lane reads, so its cursor never stops moving, and an
 * empty batch does not mean caught up either — a stretch of non-travelling
 * housekeeping produces empty batches while the lane is still draining. A
 * connector measures caught-up as <em>nothing arrived for a few rounds</em>.
 */
public final class HttpLanes {

    private final URI base;
    private final Supplier<String> bearer;
    private final String tenant;
    private final HttpClient http;

    /**
     * @param base   the tenant's replication surface, e.g.
     *               {@code https://dbo/t/hogwarts/replication}
     * @param bearer the credential to present, asked for per call. Replication
     *               is a whole-tenant act, so this is the tenant's own
     *               credential rather than a participant's
     * @param tenant which tenant this replicates — this side's log word
     */
    public static HttpLanes to(URI base, Supplier<String> bearer, String tenant) {
        return new HttpLanes(base, bearer, tenant, HttpClient.newHttpClient());
    }

    public HttpLanes(URI base, Supplier<String> bearer, String tenant, HttpClient http) {
        this.base = base;
        this.bearer = bearer;
        this.tenant = tenant;
        this.http = http;
    }

    /** Which tenant this replicates. Known without asking, like a lane's own. */
    public String tenant() {
        return tenant;
    }

    public Lanes.Lane open(String peer) {
        return RecordWire.decode(post(LanesVerbs.OPEN, with(peer)), Lanes.Lane.class);
    }

    public Optional<Lanes.Lane> lane(String peer) {
        return Optional.ofNullable(
                RecordWire.decode(post(LanesVerbs.LANE, with(peer)), Lanes.Lane.class));
    }

    public Lanes.Lane mark(String peer, String theirMarker) {
        Map<String, Object> body = with(peer);
        body.put(LanesVerbs.MARKER, theirMarker);
        return RecordWire.decode(post(LanesVerbs.MARK, body), Lanes.Lane.class);
    }

    public Lanes.Batch outbound(String peer, int limit, Set<String> processes) {
        Map<String, Object> body = with(peer);
        body.put(LanesVerbs.LIMIT, limit);
        body.put(LanesVerbs.PROCESSES, RecordWire.encode(List.copyOf(processes)));
        return RecordWire.decode(post(LanesVerbs.OUTBOUND, body), Lanes.Batch.class);
    }

    public Lanes.Lane sent(String peer, Lanes.Batch batch) {
        Map<String, Object> body = with(peer);
        body.put(LanesVerbs.BATCH, RecordWire.encode(batch));
        return RecordWire.decode(post(LanesVerbs.SENT, body), Lanes.Lane.class);
    }

    public Lanes.Applied apply(String peer, Lanes.Batch batch) {
        Map<String, Object> body = with(peer);
        body.put(LanesVerbs.BATCH, RecordWire.encode(batch));
        return RecordWire.decode(post(LanesVerbs.APPLY, body), Lanes.Applied.class);
    }

    public Lanes.Revoked revoke() {
        return RecordWire.decode(post(LanesVerbs.REVOKE, new LinkedHashMap<>()),
                Lanes.Revoked.class);
    }

    private static Map<String, Object> with(String peer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(LanesVerbs.PEER, peer);
        return body;
    }

    /** The verb's answer, or the refusal it was met with. */
    private Object post(LanesVerbs verb, Map<String, Object> body) {
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
                    tenant + ": the replication surface did not answer '" + verb.path() + "'", unreachable);
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
        return envelope instanceof Map<?, ?> map ? map.get(LanesVerbs.RESULT) : null;
    }

    private static String reason(Object envelope) {
        Object reason = envelope instanceof Map<?, ?> map ? map.get(LanesVerbs.REASON) : null;
        return reason == null ? "no reason given" : String.valueOf(reason);
    }
}
