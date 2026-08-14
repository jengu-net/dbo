package cloud.jengu.dbo.rest;

import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.TerminologyFacade;
import cloud.jengu.dbo.fhir.common.UnknownSearchParameterException;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The FHIR REST endpoint over one tenant store (dbo#12). JDK HttpServer +
 * virtual threads — no framework (R2). Engine semantics surface as HTTP:
 * If-Match → 412, identity conflict → 409, validation → 422, strict search →
 * 400; every error body is an OperationOutcome.
 */
public final class FhirHttpServer implements AutoCloseable {

    private static final String FHIR_JSON = "application/fhir+json";

    private final FhirStoreFacade store;
    private final TerminologyFacade terminology;
    private final HttpServer server;
    private final String basePath;
    private final boolean ownsServer;

    public FhirHttpServer(FhirStoreFacade store, TerminologyFacade terminology,
            String host, int port, String basePath) {
        this.store = store;
        this.terminology = terminology;
        this.basePath = normalize(basePath);
        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.ownsServer = true;
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(this.basePath.isEmpty() ? "/" : this.basePath, this::handle);
        server.start();
    }

    /**
     * Attached mode (dbo#17): mounts onto an EXISTING shared server under the
     * base path — the multi-tenant interim (`/t/<code>/fhir`) until the
     * routing layer. {@link #close()} detaches the context, never stops the
     * shared server.
     */
    public FhirHttpServer(HttpServer sharedServer, FhirStoreFacade store,
            TerminologyFacade terminology, String basePath) {
        this.store = store;
        this.terminology = terminology;
        this.basePath = normalize(basePath);
        this.server = sharedServer;
        this.ownsServer = false;
        server.createContext(this.basePath.isEmpty() ? "/" : this.basePath, this::handle);
    }

    private static String normalize(String basePath) {
        return basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + port() + basePath;
    }

    @Override
    public void close() {
        if (ownsServer) {
            server.stop(0);
        } else {
            server.removeContext(basePath.isEmpty() ? "/" : basePath);
        }
    }

    // ------------------------------------------------------------- routing

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (UnknownSearchParameterException e) {
            respond(exchange, 400, store.operationOutcome("invalid", e.getMessage()));
        } catch (ValidationFailedException e) {
            respond(exchange, 422, store.operationOutcome("invalid", String.join("; ", e.issues())));
        } catch (VersionConflictException e) {
            respond(exchange, 412, store.operationOutcome("conflict", e.getMessage()));
        } catch (IdentityConflictException e) {
            respond(exchange, 409, store.operationOutcome("duplicate", e.getMessage()));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, store.operationOutcome("invalid", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            respond(exchange, 500, store.operationOutcome("exception", String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String relative = path.substring(basePath.length());
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        String[] segments = relative.isEmpty() ? new String[0] : relative.split("/");
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if (segments.length == 1 && "metadata".equals(segments[0]) && "GET".equals(method)) {
            respond(exchange, 200, store.capabilityStatement(baseUrl()));
            return;
        }
        // terminology operations
        if (terminology != null && segments.length == 2 && segments[1].startsWith("$")) {
            switch (segments[0] + "/" + segments[1]) {
                case "ValueSet/$expand" -> {
                    String url = required(query, "url");
                    var expansion = terminology.expand(url, query.get("filter"),
                            intOf(query, "offset", 0), intOf(query, "count", 100));
                    respondOptional(exchange, expansion, "ValueSet not registered: " + url);
                    return;
                }
                case "CodeSystem/$lookup" -> {
                    var found = terminology.lookup(required(query, "system"), required(query, "code"));
                    respondOptional(exchange, found, "code not found");
                    return;
                }
                case "CodeSystem/$validate-code" -> {
                    respond(exchange, 200,
                            terminology.validateCode(required(query, "system"), required(query, "code")));
                    return;
                }
                default -> { /* falls through to 404 below */ }
            }
        }

        if (segments.length >= 1 && !store.knowsType(segments[0])) {
            respond(exchange, 404, store.operationOutcome("not-supported",
                    "unknown resource type or endpoint: " + relative));
            return;
        }
        String type = segments[0];

        switch (segments.length) {
            case 1 -> {
                switch (method) {
                    case "GET" -> {
                        String cursor = query.remove("_cursor");
                        respond(exchange, 200, store.search(type, query, cursor));
                    }
                    case "POST" -> {
                        String body = readBody(exchange);
                        String ifNoneExist = exchange.getRequestHeaders().getFirst("If-None-Exist");
                        PutResult result;
                        if (ifNoneExist != null) {
                            result = store.conditionalCreate(body, parseQuery(ifNoneExist));
                        } else {
                            result = store.create(body);
                        }
                        exchange.getResponseHeaders().set("Location",
                                baseUrl() + "/" + type + "/" + result.id());
                        exchange.getResponseHeaders().set("ETag", etag(result.versionId()));
                        respond(exchange, result.created() ? 201 : 200, store.read(type, result.id()));
                    }
                    default -> methodNotAllowed(exchange, method);
                }
            }
            case 2 -> {
                String id = segments[1];
                switch (method) {
                    case "GET" -> {
                        String resource = store.read(type, id);
                        if (resource == null) {
                            respond(exchange, 404, store.operationOutcome("not-found", type + "/" + id));
                        } else {
                            respond(exchange, 200, resource);
                        }
                    }
                    case "PUT" -> {
                        PutResult result = store.update(id, ifMatchVersion(exchange), readBody(exchange));
                        exchange.getResponseHeaders().set("ETag", etag(result.versionId()));
                        respond(exchange, result.created() ? 201 : 200, store.read(type, id));
                    }
                    case "DELETE" -> {
                        store.delete(type, id, ifMatchVersion(exchange));
                        respond(exchange, 204, null);
                    }
                    default -> methodNotAllowed(exchange, method);
                }
            }
            case 3 -> {
                if ("_history".equals(segments[2]) && "GET".equals(method)) {
                    respond(exchange, 200, store.historyBundle(type, segments[1]));
                } else {
                    respond(exchange, 404, store.operationOutcome("not-supported", relative));
                }
            }
            default -> respond(exchange, 404, store.operationOutcome("not-supported", relative));
        }
    }

    // ------------------------------------------------------------ plumbing

    /** Strict: a repeated parameter name is rejected, not silently last-wins. */
    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (out.put(name, value) != null) {
                throw new IllegalArgumentException(
                        "repeated query parameter not supported: " + name);
            }
        }
        return out;
    }

    private Long ifMatchVersion(HttpExchange exchange) {
        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        if (ifMatch == null) {
            return null;
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        return Long.parseLong(v);
    }

    private static String etag(long versionId) {
        return "W/\"" + versionId + "\"";
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing required parameter: " + name);
        }
        return value;
    }

    private static int intOf(Map<String, String> query, String name, int defaultValue) {
        String v = query.get(name);
        return v == null ? defaultValue : Integer.parseInt(v);
    }

    private void respondOptional(HttpExchange exchange, java.util.Optional<String> body, String notFound)
            throws IOException {
        if (body.isPresent()) {
            respond(exchange, 200, body.get());
        } else {
            respond(exchange, 404, store.operationOutcome("not-found", notFound));
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String method) throws IOException {
        respond(exchange, 405, store.operationOutcome("not-supported", "method " + method));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", FHIR_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
