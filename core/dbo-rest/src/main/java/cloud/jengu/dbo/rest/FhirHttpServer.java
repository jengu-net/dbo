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
 * The FHIR REST endpoint over one tenant store. JDK HttpServer +
 * virtual threads — no framework. Engine semantics surface as HTTP:
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
    private final RequestAuthenticator authenticator;
    /**
     * Every operation this server answers, keyed by {@code Type/$name}.
     *
     * <p>Built from what was actually wired: the store's own, plus a
     * terminology facade's if one was given. The router dispatches from here
     * and the CapabilityStatement is generated from here, so an operation
     * cannot be reachable and unannounced — which four of them were.
     */
    private final Map<String, cloud.jengu.dbo.fhir.common.FhirOperation> operations =
            new LinkedHashMap<>();
    /**
     * The same operations, once each, for the statement to declare.
     *
     * <p>Held apart from the routing map because the two want different keys.
     * A route is per type — {@code Patient/$validate} and
     * {@code Observation/$validate} dispatch separately — so the routing map
     * holds one operation once per type it covers, and declaring from its
     * values announced $validate as many times as there were types.
     */
    private final java.util.List<cloud.jengu.dbo.fhir.common.FhirOperation> declaredOperations =
            new java.util.ArrayList<>();
    /** §15.4: the tenant's declared policies, named in the capability statement. */
    public volatile String policyNote;
    /** §15.1: when set, /AuditEvent is served as a projection of the trail. */
    public volatile AuditSurface auditSurface;

    public FhirHttpServer(FhirStoreFacade store, TerminologyFacade terminology,
            String host, int port, String basePath) {
        this(store, terminology, host, port, basePath, null);
    }

    public FhirHttpServer(FhirStoreFacade store, TerminologyFacade terminology,
            String host, int port, String basePath, RequestAuthenticator authenticator) {
        this.store = store;
        this.terminology = terminology;
        this.authenticator = authenticator;
        this.basePath = normalize(basePath);
        register();
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
     * Attached mode: mounts onto an EXISTING shared server under the
     * base path — the multi-tenant interim ({@code /t/<code>/fhir}) until the
     * routing layer. {@link #close()} detaches the context, never stops the
     * shared server.
     */
    public FhirHttpServer(HttpServer sharedServer, FhirStoreFacade store,
            TerminologyFacade terminology, String basePath) {
        this(sharedServer, store, terminology, basePath, null);
    }

    public FhirHttpServer(HttpServer sharedServer, FhirStoreFacade store,
            TerminologyFacade terminology, String basePath, RequestAuthenticator authenticator) {
        this.store = store;
        this.terminology = terminology;
        this.authenticator = authenticator;
        this.basePath = normalize(basePath);
        register();
        this.server = sharedServer;
        this.ownsServer = false;
        server.createContext(this.basePath.isEmpty() ? "/" : this.basePath, this::handle);
    }

    /** Collects what the wired facades say they answer. */
    private void register() {
        java.util.List<cloud.jengu.dbo.fhir.common.FhirOperation> declared =
                new java.util.ArrayList<>(store.operations());
        if (terminology != null) {
            declared.addAll(terminology.operations());
        }
        for (cloud.jengu.dbo.fhir.common.FhirOperation operation : declared) {
            declaredOperations.add(operation);
            for (String type : operation.types()) {
                operations.put(type + "/$" + operation.name(), operation);
            }
        }
    }

    /**
     * Injects rest.security into the generated CapabilityStatement when the
     * surface is guarded — the capability document is a generated projection,
     * and this is part of the generation.
     */
    private String securityDeclared(String capabilityJson) {
        if (authenticator == null) {
            return capabilityJson;
        }
        String marker = "\"rest\":[{";
        int at = capabilityJson.indexOf(marker);
        if (at < 0) {
            return capabilityJson;
        }
        String security = "\"security\":{\"service\":[{\"coding\":[{"
                + "\"system\":\"http://terminology.hl7.org/CodeSystem/restful-security-service\","
                + "\"code\":\"OAuth\"}]}],"
                + "\"description\":\"Bearer JWT from this tenant's own authority (/oidc)"
                + (policyNote != null ? "; " + policyNote : "") + "\"},";
        return capabilityJson.substring(0, at + marker.length()) + security
                + capabilityJson.substring(at + marker.length());
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
        } catch (cloud.jengu.dbo.fhir.common.ValidationUnavailableException e) {
            // 503, not 422. The resource was never found invalid — validation
            // could not reach a verdict, and answering "invalid" would tell a
            // caller their good resource is malformed, intermittently, with a
            // regular expression as the diagnosis. Retry-After because the
            // retry is the caller's and it will work.
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, 503, store.operationOutcome("timeout", e.getMessage()));
        } catch (VersionConflictException e) {
            respond(exchange, 412, store.operationOutcome("conflict", e.getMessage()));
        } catch (IdentityConflictException e) {
            respond(exchange, 409, store.operationOutcome("duplicate", e.getMessage()));
        } catch (cloud.jengu.dbo.core.api.HandlingRefusedException e) {
            // 403, not 500 and not 401. This is not a permission the caller
            // could be granted — an append-only record cannot be altered by
            // anyone, including us — so the answer says which rule refused it
            // rather than implying somebody could authorise their way past.
            respond(exchange, 403, store.operationOutcome("forbidden", e.getMessage()));
        } catch (cloud.jengu.dbo.core.api.ShapeTooNewException e) {
            // 409: the tenant's DATA and the tenant's PACK disagree. Not 422
            // — the request was fine; not 403 — nobody could be granted a way
            // past it; not 500 — nothing is broken. A consumer gates on this
            // to know it should upgrade or convert rather than retry.
            respond(exchange, 409, store.operationOutcome("conflict", e.getMessage()));
        } catch (cloud.jengu.dbo.core.api.PolicyViolationException e) {
            respond(exchange, 409, store.operationOutcome("business-rule", e.getMessage()));
        } catch (UnsupportedOperationException e) {
            respond(exchange, 400, store.operationOutcome("not-supported", String.valueOf(e.getMessage())));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, store.operationOutcome("invalid", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            respond(exchange, 500, store.internalFault(String.valueOf(e.getMessage())));
        } catch (Throwable t) {
            // An Error is not this thread's to die of silently. The toolchain
            // throws one for a canonical it cannot resolve, and a dead handler
            // answers with no bytes at all — which a caller cannot tell from a
            // network fault and cannot act on either.
            respond(exchange, 500, store.internalFault(
                    t.getClass().getSimpleName() + ": " + t.getMessage()));
        } finally {
            cloud.jengu.dbo.core.api.Caller.clear();
            // Cleared with the caller, and for the same reason: a thread is
            // reused, and a purpose left behind would disclose the next
            // request's person under the last one's reason.
            cloud.jengu.dbo.core.api.Disclosure.clear();
            cloud.jengu.dbo.core.api.Reach.clear();
            cloud.jengu.dbo.core.api.Audience.clear();
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
            // anonymous by REQ-DBO-AUTH-OPEN-CAPABILITY; declares the auth mode
            // The audit trail has a surface of its own, so the statement
            // advertises the parameters THAT surface honours rather than
            // every one the version defines.
            respond(exchange, 200, securityDeclared(
                    store.capabilityStatement(baseUrl(), declaredOperations,
                            auditSurface == null ? Map.of()
                                    : Map.of("AuditEvent", auditSurface.searchParameters()))));
            return;
        }
        if (authenticator != null) {
            boolean mutation = switch (method) {
                case "PUT", "DELETE", "PATCH" -> true;
                case "POST" -> segments.length == 0 || !segments[segments.length - 1].startsWith("$");
                default -> false;
            };
            String resourceType = segments.length > 0 && !segments[0].startsWith("$")
                    && !segments[0].startsWith("_") ? segments[0] : null;
            RequestAuthenticator.Denial denial = authenticator.check(
                    exchange.getRequestHeaders().getFirst("Authorization"), mutation, resourceType);
            if (denial != null) {
                if (denial.wwwAuthenticate() != null) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", denial.wwwAuthenticate());
                }
                respond(exchange, denial.status(),
                        store.operationOutcome("security", denial.diagnostics()));
                return;
            }
        }
        // §15.1 audit surface: read renders the trail, POST maps into a
        // native custom entry, update/delete never exist
        if (auditSurface != null && segments.length >= 1 && "AuditEvent".equals(segments[0])) {
            switch (method) {
                case "GET" -> {
                    if (segments.length == 1) {
                        respond(exchange, 200, auditSurface.search(query, baseUrl()));
                    } else {
                        var rendered = auditSurface.read(segments[1]);
                        if (rendered.isPresent()) {
                            respond(exchange, 200, rendered.get());
                        } else {
                            respond(exchange, 404, store.operationOutcome("not-found",
                                    "no such AuditEvent"));
                        }
                    }
                }
                case "POST" -> {
                    // 200 when this event had already been delivered: an
                    // appliance forwards at-least-once, and the status is how
                    // it learns its retry landed on the entry it already made
                    // rather than beside it.
                    AuditSurface.Recorded recorded = auditSurface.record(
                            new String(exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    respond(exchange, recorded.created() ? 201 : 200, recorded.rendered());
                }
                default -> throw new cloud.jengu.dbo.core.api.PolicyViolationException(
                        "the audit trail is unconditionally append-only");
            }
            return;
        }

        // Operations, dispatched from the registry and nowhere else. A
        // hand-written branch beside this would be the second list all over
        // again, so there is not one.
        if (segments.length == 2 && segments[1].startsWith("$")) {
            var operation = operations.get(segments[0] + "/" + segments[1]);
            if (operation != null) {
                if (!store.knowsType(segments[0])) {
                    respond(exchange, 404, store.operationOutcome("not-supported",
                            "unknown resource type: " + segments[0]));
                    return;
                }
                var answer = operation.answer(segments[0], query, readBody(exchange));
                respond(exchange, answer.status(), answer.body());
                return;
            }
            // A $-segment is an operation, never an id. Falling through would
            // have routed it as an instance — "method not allowed on the
            // resource $reindex" — when the truth is that no such operation
            // exists here.
            respond(exchange, 404, store.operationOutcome("not-supported",
                    "unknown operation: " + segments[0] + "/" + segments[1]));
            return;
        }

        // The base itself. POST is a request bundle; anything else at
        // the base is refused with its name — the index-out-of-bounds this
        // replaced told a caller to retry a request that could never work.
        if (segments.length == 0) {
            if ("POST".equals(method)) {
                respond(exchange, 200, store.bundle(readBody(exchange)));
            } else {
                respond(exchange, 404, store.operationOutcome("not-supported",
                        method + " on the base — the base accepts POSTed "
                                + "transaction and batch bundles"));
            }
            return;
        }

        if (!store.knowsType(segments[0])) {
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
                        respondStreaming(exchange, out -> store.search(type, query, cursor, out));
                    }
                    // Conditional update (R4 §3.1.0.7.1): the type with a
                    // condition, no id. Absent it creates, present it
                    // replaces — the upsert-by-canonical a catalogue needs,
                    // which conditional create cannot express because it is a
                    // no-op when the resource exists.
                    case "PUT" -> {
                        if (query.isEmpty()) {
                            respond(exchange, 400, store.operationOutcome("invalid",
                                    "PUT to a type needs a condition — " + type
                                            + "?identifier=… or ?url=… — or an id"));
                            return;
                        }
                        PutResult conditional = store.conditionalUpdate(readBody(exchange), query);
                        exchange.getResponseHeaders().set("Location",
                                baseUrl() + "/" + type + "/" + conditional.id());
                        exchange.getResponseHeaders().set("ETag", etag(conditional.versionId()));
                        respond(exchange, conditional.created() ? 201 : 200,
                                store.read(type, conditional.id()));
                    }
                    case "POST" -> {
                        String body = readBody(exchange);
                        String ifNoneExist = exchange.getRequestHeaders().getFirst("If-None-Exist");
                        PutResult result;
                        // A CodeSystem written the ordinary way is stored whole
                        // and answers nothing: the concepts never reach the
                        // native form, so $lookup and $expand find an empty
                        // system and say so politely. Terminology writes go
                        // through the facade, which splits shell from concepts
                        // (REQ-DBO-TERM-EVERY-TENANT-ANSWERS).
                        if (terminology != null && isTerminology(type)) {
                            result = ingest(type, body);
                        } else if (ifNoneExist != null) {
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
                        FhirStoreFacade.ReadResult result = store.readForServing(type, id);
                        if (result == null) {
                            respond(exchange, 404, store.operationOutcome("not-found", type + "/" + id));
                        } else {
                            // A read carries its validators. Without them a
                            // client cannot make the conditional update the
                            // read was for.
                            exchange.getResponseHeaders().set("ETag", etag(result.versionId()));
                            exchange.getResponseHeaders().set("Last-Modified",
                                    HTTP_DATE.format(result.lastUpdated()));
                            respond(exchange, 200, result.resourceJson());
                        }
                    }
                    case "PUT" -> {
                        String updateBody = readBody(exchange);
                        // an update has to reach the native form for the same
                        // reason a create does: a CodeSystem updated the
                        // ordinary way would leave yesterday's concepts in
                        // place while the resource claims today's
                        PutResult result = terminology != null && isTerminology(type)
                                ? ingest(type, updateBody)
                                : store.update(id, ifMatchVersion(exchange), updateBody);
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

    /** The two types whose truth form is the native one, not the resource. */
    private static boolean isTerminology(String type) {
        return "CodeSystem".equals(type) || "ValueSet".equals(type);
    }

    /**
     * Writes a terminology resource through the facade.
     *
     * <p>Both halves land or neither is useful: a CodeSystem shell with no
     * concepts answers nothing, and concepts with no shell have no identity,
     * no history and no feed entry. The facade owns that pairing; this only
     * routes to it.
     */
    private PutResult ingest(String type, String body) {
        if ("ValueSet".equals(type)) {
            return terminology.ingestValueSet(body);
        }
        TerminologyFacade.IngestResult ingested = terminology.ingestCodeSystem(body);
        return new PutResult(ingested.id(), ingested.versionId(), true);
    }

    private static String etag(long versionId) {
        return "W/\"" + versionId + "\"";
    }

    /** RFC 7231 IMF-fixdate, which is the only form a Last-Modified may take. */
    private static final java.time.format.DateTimeFormatter HTTP_DATE =
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .withZone(java.time.ZoneOffset.UTC);

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

    /** Something that writes a response body as it produces it. */
    private interface BodyWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Answers 200 without knowing how long the answer is.
     *
     * <p>A content length of zero tells the JDK server to chunk, so the first
     * bytes reach the reader while the rest is still being read from the
     * database — and the reader is the brake, since writing blocks when it
     * stops draining.
     *
     * <p><b>The status waits for the first byte.</b> A search that refuses —
     * an unknown parameter, a malformed cursor — refuses while compiling,
     * before anything is produced, and that has to stay answerable as 400
     * rather than as a 200 containing an apology. So the headers are sent when
     * the body first writes, and until then a failure is an ordinary failure.
     * Once a byte is out the status is spent: a failure after that can only
     * close the response, which is the real cost of not holding a page.
     */
    private void respondStreaming(HttpExchange exchange, BodyWriter body) throws IOException {
        try (OutputStream out = new HeadersOnFirstWrite(exchange)) {
            body.writeTo(out);
            out.flush();
        }
    }

    /** Sends 200 and opens the response body when something is actually written. */
    private final class HeadersOnFirstWrite extends OutputStream {

        private final HttpExchange exchange;
        private OutputStream body;

        private HeadersOnFirstWrite(HttpExchange exchange) {
            this.exchange = exchange;
        }

        private OutputStream body() throws IOException {
            if (body == null) {
                exchange.getResponseHeaders().set("Content-Type", FHIR_JSON);
                exchange.sendResponseHeaders(200, 0);
                body = exchange.getResponseBody();
            }
            return body;
        }

        @Override
        public void write(int b) throws IOException {
            body().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            body().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (body != null) {
                body.flush();
            }
        }

        @Override
        public void close() throws IOException {
            // Nothing written and nothing failed means an empty 200 is still
            // the answer; a body that threw closes without one, and the caller
            // has already sent a status.
            if (body != null) {
                body.close();
            }
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
