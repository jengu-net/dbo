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
        this(authority, dataSource, domain, types, rendering, basePath, ledger, null, null);
    }

    /** Where a move is written down, for a deployment that has somewhere. */
    private MaintenanceRecording recording = MaintenanceRecording.none();

    /**
     * Says where to record what this handler moves.
     *
     * <p>Set rather than constructed, because the managing tenant's store is
     * not necessarily up when a tenant's own admin surface is mounted, and a
     * handler that demanded it would order the bring-up around its own
     * bookkeeping.
     */
    public MaintenanceHandler recordingInto(MaintenanceRecording where) {
        this.recording = where == null ? MaintenanceRecording.none() : where;
        return this;
    }

    /**
     * The same, able to run a reshape: the engine to write through and
     * the face to convert with. Both absent — a tenant whose face declares no
     * {@link cloud.jengu.dbo.core.face.ShapeConversion} — leaves the
     * operation refusing by name rather than missing.
     */
    public MaintenanceHandler(TenantAuthority authority, DataSource dataSource,
            String domain, List<TypeRegistration> types,
            cloud.jengu.dbo.core.face.PortableRendering rendering, String basePath,
            cloud.jengu.dbo.maintenance.ImportLedger ledger,
            cloud.jengu.dbo.core.api.ObjectStore engine,
            cloud.jengu.dbo.fhir.common.FhirStoreFacade facade) {
        this.engine = engine;
        this.facade = facade;
        this.authority = authority;
        this.dataSource = dataSource;
        this.domain = domain;
        this.types = List.copyOf(types);
        this.rendering = rendering;
        this.basePath = basePath;
        this.ledger = java.util.Objects.requireNonNull(ledger,
                "the tenant's own trail is where a restore is written down");
    }

    private final cloud.jengu.dbo.core.api.ObjectStore engine;
    private final cloud.jengu.dbo.fhir.common.FhirStoreFacade facade;

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
                case "reshape" -> reshape(exchange);
                case "reshape/claim" -> reshapeClaim(exchange);
                case "reshape/apply" -> reshapeApply(exchange);
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

        // Recorded before the first byte, because the record is the thing an
        // operator asks about afterwards and a backup that failed halfway is
        // exactly the case where there is no response left to read. Opened
        // where the deployment's own history lives rather than in the
        // tenant's: a restore can precede the tenant it restores, and the two
        // belong in one place or neither is answerable.
        MaintenanceRecording.Recorded recorded = recording.open("backup", kind.wire());
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set(KIND_HEADER, kind.wire());
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            TenantExport.export(dataSource, domain, ownerKey, out, types, kind, rendering);
            recorded.closed();
        } catch (IOException | RuntimeException failed) {
            // The response is already open, so this cannot become a status
            // code. The run is the only place it can be said.
            recorded.failed(failed.toString());
            throw failed;
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
                cloud.jengu.dbo.maintenance.TenantInventory.deliveryOf(dataSource),
                cloud.jengu.dbo.maintenance.TenantInventory.shapes(dataSource)));
    }

    /**
     * Converts stock stamped below a target major, in place.
     *
     * <p>Query-parameterised rather than bodied: an operator drives this from
     * a shell, and a run is a repeatable question — which type, which shape,
     * which major, how much at a time — not a document.
     */
    private void reshape(HttpExchange exchange) throws IOException {
        java.util.Map<String, String> q = query(exchange);
        String typeName = q.get("type");
        String profile = q.get("profile");
        String target = q.get("target");
        if (typeName == null || profile == null || target == null) {
            fail(exchange, 400, "invalid_request",
                    "reshape needs type, profile and target (the major to converge on)");
            return;
        }
        java.util.Optional<cloud.jengu.dbo.core.face.ShapeConversion> conversion =
                facade == null ? java.util.Optional.empty() : facade.shapeConversion();
        if (engine == null || conversion.isEmpty()) {
            // Named, not silent: "this face cannot convert" and "the operation
            // is broken" look identical from outside, and only one of them is
            // something an operator can act on.
            fail(exchange, 409, "not_convertible", "this tenant's face declares no shape "
                    + "conversion, so a reshape would have nothing to convert with");
            return;
        }
        cloud.jengu.dbo.maintenance.Reshape.Run run;
        try {
            run = cloud.jengu.dbo.maintenance.Reshape.run(engine, conversion.get(),
                    // the face's own accept path: validated against the pack
                    // and re-stamped by it, exactly like any other write
                    (type, id, expected, payload) -> facade.update(id, expected,
                            new String(payload, java.nio.charset.StandardCharsets.UTF_8)),
                    typeName, profile, Integer.parseInt(target),
                    Integer.parseInt(q.getOrDefault("pageSize", "100")),
                    Integer.parseInt(q.getOrDefault("pages", "10")),
                    q.get("cursor"));
        } catch (NumberFormatException notANumber) {
            fail(exchange, 400, "invalid_request",
                    "target, pageSize and pages are numbers: " + notANumber.getMessage());
            return;
        }
        respond(exchange, 200, cloud.jengu.dbo.maintenance.Reshape.json(run));
    }

    /**
     * A page of stock for a converter that is not this store's.
     *
     * <p>Available whether or not the face can convert in process: the
     * hand-back lane is the floor under both cases — the only lane for a
     * face whose model has no converter standard, and the escape hatch for a
     * hop that exceeds one that does.
     */
    private void reshapeClaim(HttpExchange exchange) throws IOException {
        java.util.Map<String, String> q = query(exchange);
        if (engine == null || q.get("type") == null || q.get("profile") == null
                || q.get("target") == null) {
            fail(exchange, 400, "invalid_request",
                    "claim needs type, profile and target (the major to converge on)");
            return;
        }
        try {
            respond(exchange, 200, cloud.jengu.dbo.maintenance.Reshape.json(
                    cloud.jengu.dbo.maintenance.Reshape.claim(engine, q.get("type"),
                            q.get("profile"), Integer.parseInt(q.get("target")),
                            Integer.parseInt(q.getOrDefault("pageSize", "100")),
                            q.get("cursor"))));
        } catch (NumberFormatException notANumber) {
            fail(exchange, 400, "invalid_request",
                    "target and pageSize are numbers: " + notANumber.getMessage());
        }
    }

    /** Converted forms handed back, re-accepted through the face. */
    private void reshapeApply(HttpExchange exchange) throws IOException {
        java.util.Map<String, String> q = query(exchange);
        String typeName = q.get("type");
        if (engine == null || facade == null || typeName == null) {
            fail(exchange, 400, "invalid_request", "apply needs type");
            return;
        }
        List<cloud.jengu.dbo.maintenance.Reshape.Held> converted;
        try {
            converted = heldFrom(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            fail(exchange, 400, "invalid_request",
                    "a handed-back batch is {\"held\":[{id, version, payload}]} with payload "
                            + "base64: " + unreadable.getMessage());
            return;
        }
        respond(exchange, 200, cloud.jengu.dbo.maintenance.Reshape.json(
                cloud.jengu.dbo.maintenance.Reshape.apply(
                        (type, id, expected, payload) -> facade.update(id, expected,
                                new String(payload, java.nio.charset.StandardCharsets.UTF_8)),
                        typeName, q.getOrDefault("profile", ""), converted)));
    }

    /** The handed-back batch, read without a JSON library this module does not have. */
    private static List<cloud.jengu.dbo.maintenance.Reshape.Held> heldFrom(String body) {
        List<cloud.jengu.dbo.maintenance.Reshape.Held> held = new java.util.ArrayList<>();
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                        "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"version\"\\s*:\\s*(\\d+)\\s*,"
                                + "\\s*\"payload\"\\s*:\\s*\"([^\"]*)\"\\s*\\}")
                .matcher(body);
        while (entry.find()) {
            held.add(new cloud.jengu.dbo.maintenance.Reshape.Held(entry.group(1),
                    Long.parseLong(entry.group(2)),
                    java.util.Base64.getDecoder().decode(entry.group(3))));
        }
        if (held.isEmpty()) {
            throw new IllegalArgumentException("no entries read");
        }
        return held;
    }

    /** The request's query parameters, decoded. */
    private static java.util.Map<String, String> query(HttpExchange exchange) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.put(java.net.URLDecoder.decode(pair.substring(0, eq),
                                    java.nio.charset.StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(eq + 1),
                                    java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return params;
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
        // Opened after the guards and before the archive is read, so a
        // restore that dies mid-stream leaves a run that is owed rather than
        // nothing at all. A refused one — no attestation, a bad key — never
        // started, and is a status code rather than a record.
        // Read BEFORE the run is opened. These refuse an unattested or
        // unsigned restore, and a refusal at the door is a status code rather
        // than history: a run written for a move that never began would say a
        // tenant's data was touched when nothing was.
        var attestation = attestation(exchange);
        var vendorKey = publicKey(exchange, VENDOR_KEY_HEADER);
        var tenantKey = publicKey(exchange, TENANT_KEY_HEADER);
        MaintenanceRecording.Recorded recorded = recording.open("restore", "fidelity");
        try {
            TenantImport.restoreFidelity(dataSource, domain, exchange.getRequestBody(), ownerKey,
                    attestation, vendorKey, tenantKey, ledger);
            recorded.closed();
        } catch (IOException | RuntimeException failed) {
            recorded.failed(failed.toString());
            throw failed;
        }
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
