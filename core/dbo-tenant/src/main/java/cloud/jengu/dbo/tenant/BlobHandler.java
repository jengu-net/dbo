package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.BlobStore;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.rest.RequestAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Content a tenant holds whole, over the wire.
 *
 * <p>The store already keeps it and an archive already carries it; this is the
 * only way in from outside the process, and a consumer that speaks HTTP has no
 * other. Without it the capability is built, mounted and unreachable — which
 * is a different failure from not having it, and lands whoever needs it in the
 * same place.
 *
 * <p><b>Opaque, deliberately.</b> Nothing here sniffs a type, transcodes, or
 * normalises: the bytes are written as they arrive and returned as they were
 * written. Content that is identifying as a whole is often sealed before it
 * gets here, so this store frequently cannot read it even in principle — and
 * a store that guessed at ciphertext would be guessing about the one thing it
 * must not corrupt.
 *
 * <p>The media type is the writer's statement about their own content, kept
 * and handed back, never inferred. A tenant putting something unsealed through
 * here is making a decision about their own data: this store cannot tell
 * whether opaque bytes identify somebody, so it cannot classify them, and the
 * membrane has nothing to say about a blob it cannot read.
 *
 * <p>Guarded by the scope for {@code Binary} — the type in the specification
 * that IS binary content — so whoever may write a record about somebody may
 * write the recording it points at, and a credential that reads may read it.
 * Inventing a scope of its own would have made this the one surface a
 * consumer's existing grant does not describe.
 */
public final class BlobHandler implements HttpHandler {

    /** What the SMART scope names, so no new vocabulary is invented here. */
    public static final String RESOURCE = "Binary";

    private final RequestAuthenticator guard;
    private final BlobStore blobs;
    private final String base;

    public BlobHandler(RequestAuthenticator guard, BlobStore blobs, String base) {
        this.guard = guard;
        this.blobs = blobs;
        this.base = base;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            boolean mutation = !"GET".equals(method) && !"HEAD".equals(method);
            RequestAuthenticator.Denial denial = guard.check(
                    exchange.getRequestHeaders().getFirst("Authorization"), mutation, RESOURCE);
            if (denial != null) {
                if (denial.wwwAuthenticate() != null) {
                    exchange.getResponseHeaders().set("WWW-Authenticate",
                            denial.wwwAuthenticate());
                }
                fail(exchange, denial.status(), denial.diagnostics());
                return;
            }
            String key = keyIn(exchange);
            switch (method) {
                case "POST" -> {
                    if (key != null) {
                        fail(exchange, 405, "a key is this store's to choose: POST the content "
                                + "and the answer says where it went");
                        return;
                    }
                    put(exchange);
                }
                case "GET" -> get(exchange, key);
                case "DELETE" -> drop(exchange, key);
                default -> fail(exchange, 405, method + " is not one of the three things this "
                        + "door does: POST content, GET it back, DELETE it");
            }
        } catch (RuntimeException failed) {
            fail(exchange, 500, "the content did not arrive");
        } catch (Throwable died) {
            // An Error is not this thread's to die of silently, and a caller
            // told nothing cannot tell a write that happened from one that did
            // not — see what the configuration door learnt.
            fail(exchange, 500, died.getClass().getSimpleName() + ": the content did not arrive");
        } finally {
            exchange.close();
        }
    }

    private void put(HttpExchange exchange) throws IOException {
        byte[] content = exchange.getRequestBody().readAllBytes();
        if (content.length == 0) {
            fail(exchange, 400, "there is no content here to keep");
            return;
        }
        String media = exchange.getRequestHeaders().getFirst("Content-Type");
        String key = blobs.put(content, media);
        exchange.getResponseHeaders().set("Location", base + "/" + key);
        respond(exchange, 201, RecordWire.write(Map.of("key", key, "size", content.length))
                .getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void get(HttpExchange exchange, String key) throws IOException {
        if (key == null) {
            fail(exchange, 404, "name the content to read: " + base + "/{key}");
            return;
        }
        Optional<BlobStore.Blob> held = blobs.get(key);
        if (held.isEmpty()) {
            fail(exchange, 404, "nothing is held under that key");
            return;
        }
        // As it was written, with the type its writer gave it.
        respond(exchange, 200, held.get().content(), held.get().media());
    }

    private void drop(HttpExchange exchange, String key) throws IOException {
        if (key == null) {
            fail(exchange, 404, "name the content to forget: " + base + "/{key}");
            return;
        }
        // Told apart on purpose: a caller retrying a delete wants to know it
        // succeeded before, and one that mistyped a key wants to know nothing
        // was there.
        if (blobs.drop(key)) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            fail(exchange, 404, "nothing was held under that key");
        }
    }

    /** The path segment after the door, or null when the ask is the door itself. */
    private String keyIn(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(base)) {
            return null;
        }
        String rest = path.substring(base.length());
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return rest.isBlank() ? null : rest;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String media)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type",
                media == null || media.isBlank() ? "application/octet-stream" : media);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void fail(HttpExchange exchange, int status, String detail)
            throws IOException {
        respond(exchange, status, RecordWire.write(Map.of("error", detail))
                .getBytes(StandardCharsets.UTF_8), "application/json");
    }
}
