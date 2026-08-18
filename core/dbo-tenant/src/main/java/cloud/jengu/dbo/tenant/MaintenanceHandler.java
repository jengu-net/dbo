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
 * A tenant's maintenance surface: take an archive, restore one.
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
    /** The attestation JSON, base64 — both signatures over the archive's root. */
    public static final String ATTESTATION_HEADER = "X-Archive-Attestation";
    /** The key that sealed the archive, base64 X.509. */
    public static final String VENDOR_KEY_HEADER = "X-Vendor-Key";
    /** The key that countersigned it, base64 X.509. */
    public static final String TENANT_KEY_HEADER = "X-Tenant-Key";

    private final TenantAuthority authority;
    private final DataSource dataSource;
    private final String domain;
    private final List<TypeRegistration> types;
    private final cloud.jengu.dbo.core.face.PortableRendering rendering;
    private final String basePath;
    private final cloud.jengu.dbo.maintenance.ImportLedger ledger;

    public MaintenanceHandler(TenantAuthority authority, DataSource dataSource,
            String domain, List<TypeRegistration> types,
            cloud.jengu.dbo.core.face.PortableRendering rendering, String basePath,
            cloud.jengu.dbo.maintenance.ImportLedger ledger) {
        this.authority = authority;
        this.dataSource = dataSource;
        this.domain = domain;
        this.types = List.copyOf(types);
        this.rendering = rendering;
        this.basePath = basePath;
        this.ledger = java.util.Objects.requireNonNull(ledger,
                "the tenant's own trail is where a restore is written down");
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
                case "inventory" -> inventory(exchange);
                case "projection" -> projection(exchange);
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
            TenantExport.export(dataSource, domain, ownerKey, out, types, kind, rendering);
        }
    }

    /**
     * What the tenant holds, before anything is moved.
     *
     * <p>No owner key: this reads no content, only counts. Requiring one
     * would be security theatre — it would suggest the answer discloses
     * something it does not, and would make the report an operator runs
     * before deciding harder to run than the move itself.
     */
    private void inventory(HttpExchange exchange) throws IOException {
        respond(exchange, 200, cloud.jengu.dbo.maintenance.TenantInventory.json(
                cloud.jengu.dbo.maintenance.TenantInventory.of(dataSource),
                cloud.jengu.dbo.maintenance.TenantInventory.deliveryOf(dataSource)));
    }

    /**
     * Records which configuration commit this tenant is projected from.
     *
     * <p>Written when a projection is applied rather than read when a backup
     * is taken. Those differ whenever the sync is behind, and stamping an
     * archive with the repository's current head would give it a commit its
     * data never saw — worse than no stamp, because it looks like an answer.
     */
    private void projection(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int at = body.indexOf("\"commit\"");
        if (at < 0) {
            throw new IllegalArgumentException("a commit is required");
        }
        int open = body.indexOf('"', body.indexOf(':', at)) + 1;
        String commit = body.substring(open, body.indexOf('"', open));
        if (!commit.matches("[0-9a-f]{7,64}")) {
            throw new IllegalArgumentException("not a commit sha: " + commit);
        }
        cloud.jengu.dbo.maintenance.ProjectionMarker.record(dataSource,
                cloud.jengu.dbo.maintenance.ProjectionMarker.CONFIG_COMMIT, commit);
        respond(exchange, 200, "{\"status\":\"recorded\"}");
    }

    private void restore(HttpExchange exchange) throws IOException {
        byte[] ownerKey = ownerKey(exchange);
        TenantImport.restoreFidelity(dataSource, domain, exchange.getRequestBody(), ownerKey,
                attestation(exchange),
                publicKey(exchange, VENDOR_KEY_HEADER),
                publicKey(exchange, TENANT_KEY_HEADER),
                ledger);
        respond(exchange, 200, "{\"status\":\"restored\"}");
    }

    /**
     * The two signatures over the archive's root, as the exporter and the
     * tenant produced them. Required: this store never holds the tenant's key,
     * so an unsigned restore is not something it could complete honestly.
     */
    private cloud.jengu.dbo.maintenance.ArchiveAttestation attestation(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst(ATTESTATION_HEADER);
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException(ATTESTATION_HEADER + " is required: a restore "
                    + "applies an archive both parties signed, and this store cannot sign for "
                    + "either of them");
        }
        return cloud.jengu.dbo.maintenance.ArchiveAttestation.fromJson(
                new String(Base64.getDecoder().decode(header.trim()), StandardCharsets.UTF_8));
    }

    private byte[] publicKey(HttpExchange exchange, String header) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(header + " is required: a signature nobody can "
                    + "check is not evidence");
        }
        return Base64.getDecoder().decode(value.trim());
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
