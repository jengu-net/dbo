package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * A tenant's maintenance surface: take an archive, restore one
 * (jengu-platform#866).
 *
 * <p><b>The owner's key arrives with the request and is never kept.</b> That
 * is the whole shape of this endpoint. §11 decides that the platform operates
 * backups it cannot read and that a restore structurally requires the owner;
 * an endpoint holding a key on the tenant's behalf would quietly convert that
 * property into a promise, and a promise is what it was designed not to be.
 *
 * <p>System-plane only, and explicitly so rather than through the scope
 * matcher: a clinician's {@code user/*.write} must not reach a surface that
 * emits an entire tenant.
 *
 * <p>Streamed rather than buffered. A hospital's history is the volume, and
 * an archive assembled in memory before the first byte reaches the caller is
 * the mistake this codebase has already made twice.
 */
public final class MaintenanceHandler implements HttpHandler {

    /** The key the archive is sealed under, base64, per request. */
    public static final String OWNER_KEY_HEADER = "X-Owner-Key";
    /** {@code backup} or {@code portable-export}. */
    public static final String KIND_HEADER = "X-Archive-Kind";

    private final TenantAuthority authority;
    private final DataSource dataSource;
    private final String domain;
    private final List<TypeRegistration> types;
    private final String basePath;

    public MaintenanceHandler(TenantAuthority authority, DataSource dataSource,
            String domain, List<TypeRegistration> types, String basePath) {
        this.authority = authority;
        this.dataSource = dataSource;
        this.domain = domain;
        this.types = List.copyOf(types);
        this.basePath = basePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath().substring(basePath.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            if (!systemWrite(exchange)) {
                return;
            }
            switch (relative) {
                case "archive" -> archive(exchange);
                case "restore" -> restore(exchange);
                default -> fail(exchange, 404, "not_found", "no such maintenance operation");
            }
        } catch (IllegalArgumentException refused) {
            // the kind guard and the key guard both land here: an operator
            // needs to be told which, so the message travels
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException e) {
            fail(exchange, 500, "server_error", "the operation did not complete");
        } finally {
            exchange.close();
        }
    }

    private void archive(HttpExchange exchange) throws IOException {
        byte[] ownerKey = ownerKey(exchange);
        TenantExport.Kind kind = TenantExport.Kind.ofWire(
                Optional.ofNullable(exchange.getRequestHeaders().getFirst(KIND_HEADER))
                        .orElse(TenantExport.Kind.BACKUP.wire()));

        // The response opens before the archive is built, so the bytes flow
        // as they are sealed. A failure after this point cannot become a
        // status code, which is why every guard above runs first.
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set(KIND_HEADER, kind.wire());
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            TenantExport.export(dataSource, domain, ownerKey, out, types, kind);
        }
    }

    private void restore(HttpExchange exchange) throws IOException {
        byte[] ownerKey = ownerKey(exchange);
        TenantImport.restoreFidelity(dataSource, domain, exchange.getRequestBody(), ownerKey);
        respond(exchange, 200, "{\"status\":\"restored\"}");
    }

    private byte[] ownerKey(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst(OWNER_KEY_HEADER);
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException(OWNER_KEY_HEADER + " is required: the archive is "
                    + "sealed under the tenant owner's key, which this store does not hold");
        }
        byte[] key = Base64.getDecoder().decode(header.trim());
        if (key.length != 32) {
            throw new IllegalArgumentException("the owner key must be 32 bytes (AES-256)");
        }
        return key;
    }

    private boolean systemWrite(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "maintenance operations are POSTed");
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        var context = bearer == null ? Optional.<TenantAuthority.AuthContext>empty()
                : authority.validate(bearer);
        // Explicit rather than through the scope matcher: a clinician's
        // user/*.write must never reach a surface that emits a whole tenant.
        boolean systemPlane = context.isPresent()
                && context.get().scopes().contains("system/*.write");
        if (!systemPlane) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "maintenance is system-plane only");
            return false;
        }
        return true;
    }

    private void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        respond(exchange, status, "{\"error\":\"" + error + "\",\"detail\":"
                + quote(detail) + "}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
