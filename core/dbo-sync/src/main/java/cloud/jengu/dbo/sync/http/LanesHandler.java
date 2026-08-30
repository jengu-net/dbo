package cloud.jengu.dbo.sync.http;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.sync.Lanes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant's replication surface, for a host that is not the container
 * (#157).
 *
 * <p><b>The same asymmetry {@code LaneHandler} answers, one layer up.</b>
 * {@code Lanes} takes the store's internals, so only a host that <em>is</em>
 * the container can build one — and declarations flow cloud → appliance, so
 * the cloud is the side that must produce outbound batches while being the
 * side whose dbo is a separate deployment. Pull-not-push does not move it:
 * whoever pulls, the cloud still has to produce the batch.
 *
 * <p>So the tenant serves the seven verbs on the private surface it is already
 * reached on, and every one of them lands on a real in-process {@code Lanes}
 * over the tenant's own store. Nothing about epochs, mirroring, ordering or
 * idempotence is decided twice, which is the whole reason the store owns this
 * contract.
 *
 * <p><b>Replication is a whole-tenant act, so the credential must be the
 * tenant's.</b> A batch carries whatever the travelling work names, across
 * every process the caller lists — there is no version of it bounded to one
 * step. A participation credential narrowed to steps is therefore refused
 * here rather than quietly served something smaller than it asked for.
 */
public final class LanesHandler implements HttpHandler {

    /** Whether a credential may drive replication, and who it is if so. */
    @FunctionalInterface
    public interface Grants {
        /** Null when it may, otherwise the denial to answer with. */
        Denied of(String authorizationHeader);
    }

    /** Why not, in the terms HTTP will answer in. */
    public record Denied(int status, String wwwAuthenticate, String reason) {
    }

    /** The host's own lanes over the tenant's store. */
    @FunctionalInterface
    public interface Replication {
        Lanes lanes();
    }

    private final String basePath;
    private final Grants grants;
    private final Replication replication;

    public LanesHandler(String basePath, Grants grants, Replication replication) {
        this.basePath = basePath.endsWith("/")
                ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.grants = grants;
        this.replication = replication;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath().substring(basePath.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            Optional<LanesVerbs> verb = LanesVerbs.ofPath(relative);
            if (verb.isEmpty()) {
                fail(exchange, 404, "no such replication verb: " + relative);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                // Every verb acts: even `outbound` moves nothing but is the
                // question a cursor is read for, and `open` mints an epoch.
                fail(exchange, 405, "the replication verbs are posted");
                return;
            }
            Denied denied = grants.of(exchange.getRequestHeaders().getFirst("Authorization"));
            if (denied != null) {
                if (denied.wwwAuthenticate() != null) {
                    exchange.getResponseHeaders().set("WWW-Authenticate",
                            denied.wwwAuthenticate());
                }
                fail(exchange, denied.status(), denied.reason());
                return;
            }
            answer(exchange, verb.get(), replication.lanes(), body(exchange));
        } catch (IllegalStateException refused) {
            // What the store refuses — a cursor from an epoch that no longer
            // exists, most of all — travels as a refusal with its reason. A
            // peer restored from a backup has to be able to tell that from a
            // link that went quiet.
            refuse(exchange, String.valueOf(refused.getMessage()));
        } catch (IllegalArgumentException malformed) {
            fail(exchange, 400, String.valueOf(malformed.getMessage()));
        } catch (RuntimeException failed) {
            fail(exchange, 500, "the verb did not complete");
        } finally {
            exchange.close();
        }
    }

    private void answer(HttpExchange exchange, LanesVerbs verb, Lanes lanes, Object body)
            throws IOException {
        switch (verb) {
            case OPEN -> respond(exchange, lanes.open(peer(body)));
            case LANE -> respond(exchange, lanes.lane(peer(body)).orElse(null));
            case MARK -> respond(exchange,
                    lanes.mark(peer(body), string(body, LanesVerbs.MARKER)));
            case OUTBOUND -> respond(exchange, lanes.outbound(peer(body),
                    (int) number(body, LanesVerbs.LIMIT),
                    Set.copyOf(RecordWire.decodeList(field(body, LanesVerbs.PROCESSES),
                            String.class))));
            case SENT -> respond(exchange, lanes.sent(peer(body), batch(body)));
            case APPLY -> respond(exchange, lanes.apply(peer(body), batch(body)));
            case REVOKE -> respond(exchange, lanes.revoke());
        }
    }

    private static String peer(Object body) {
        String peer = string(body, LanesVerbs.PEER);
        if (peer == null) {
            throw new IllegalArgumentException("a replication verb names the peer it is with");
        }
        return peer;
    }

    private static Lanes.Batch batch(Object body) {
        Lanes.Batch batch = RecordWire.decode(field(body, LanesVerbs.BATCH), Lanes.Batch.class);
        if (batch == null) {
            throw new IllegalArgumentException("this verb is about a batch, and none was carried");
        }
        return batch;
    }

    private static Object body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? new LinkedHashMap<String, Object>()
                : RecordWire.read(new String(bytes, StandardCharsets.UTF_8));
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

    private static void respond(HttpExchange exchange, Object result) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(LanesVerbs.RESULT, RecordWire.encode(result));
        send(exchange, 200, RecordWire.write(envelope));
    }

    private static void refuse(HttpExchange exchange, String reason) throws IOException {
        send(exchange, 409, envelopeOf(reason));
    }

    private static void fail(HttpExchange exchange, int status, String reason)
            throws IOException {
        send(exchange, status, envelopeOf(reason));
    }

    private static String envelopeOf(String reason) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(LanesVerbs.REFUSED, Boolean.TRUE);
        envelope.put(LanesVerbs.REASON, reason);
        return RecordWire.write(envelope);
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
