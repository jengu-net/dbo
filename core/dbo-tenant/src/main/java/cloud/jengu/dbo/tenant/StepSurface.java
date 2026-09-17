package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.StepId;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Work as the way in: a run is started, and the run is the only context in
 * which its documents can be read.
 *
 * <p>Two doors, because they are two acts. {@code /step/<id>} starts a run of a
 * declared step over named documents. {@code /run/<id>/fhir/…} answers for
 * <b>those documents and nothing else</b> — which is the whole of what this
 * class adds, since the slot discipline underneath it is already the engine's.
 *
 * <p><b>Reach is what the run names.</b> No traversal: a reference leaving the
 * named set is not followed, and a document of a declared type that this run
 * was not given is as absent as one that never existed. Following references
 * would mean settling depth and cycle rules before anything could be shown,
 * and it can be added later without changing what a step declares.
 *
 * <p><b>Not found, never forbidden.</b> A boundary that distinguishes <em>you
 * may not see this</em> from <em>this does not exist</em> tells whoever probes
 * it that the thing exists. The authority already refuses that distinction for
 * subjects; this refuses it for documents.
 *
 * <p>Reads only. The acceptance this was built against is a boundary claim,
 * and a boundary is proven by what it refuses to answer.
 */
final class StepSurface implements HttpHandler {

    private final TenantAuthority authority;
    private final Runs runs;
    private final FhirStoreFacade store;
    private final Map<String, TenantSpec.Step> declared = new LinkedHashMap<>();
    /**
     * Where a run of a step answers, named rather than derived from the
     * starting path. Deriving it by replacing "/step" with "/run" was wrong in
     * a way only an unlucky tenant reveals: a tenant whose code contains the
     * word replaced both occurrences, and the context it handed out named a
     * tenant that does not exist.
     */
    private final String runPath;
    private final boolean starting;

    StepSurface(TenantAuthority authority, Runs runs, FhirStoreFacade store,
            List<TenantSpec.Step> steps, String runPath, boolean starting) {
        this.authority = authority;
        this.runs = runs;
        this.store = store;
        this.runPath = runPath;
        this.starting = starting;
        steps.forEach(step -> declared.put(step.code(), step));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath()
                    .substring(exchange.getHttpContext().getPath().length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            if (!admitted(exchange)) {
                return;
            }
            if (starting) {
                start(exchange, relative);
            } else {
                read(exchange, relative);
            }
        } catch (IllegalArgumentException refused) {
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException failed) {
            fail(exchange, 500, "failed", "the request did not complete");
        } finally {
            exchange.close();
        }
    }

    /**
     * A credential that may act in work, and deliberately not the broad one.
     *
     * <p>The point of the surface is that holding it is not holding the
     * store: a token admitted here is refused by the tenant's own records
     * surface, and one admitted there has no business arriving through a run.
     */
    private boolean admitted(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        Optional<TenantAuthority.AuthContext> context =
                bearer == null ? Optional.empty() : authority.validate(bearer);
        if (context.isEmpty() || !context.get().scopes().contains(cloud.jengu.dbo.auth.Scopes.WORK)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "this surface admits a credential that may act in work");
            return false;
        }
        return true;
    }

    /** POST /step/&lt;module.process.step&gt; — a run over the documents named. */
    private void start(HttpExchange exchange, String stepCode) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run is started by POSTing to the step");
            return;
        }
        TenantSpec.Step step = declared.get(stepCode);
        if (step == null) {
            fail(exchange, 404, "not_found", "this tenant offers no step '" + stepCode
                    + "'; it offers: " + declared.keySet());
            return;
        }
        Object body = Json.parse(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        Map<String, String> inputs = new LinkedHashMap<>();
        if (Json.objOpt(body, "inputs") instanceof Map<?, ?> named) {
            named.forEach((slot, reference) ->
                    inputs.put(String.valueOf(slot), String.valueOf(reference)));
        }
        // The slot's declared type is a promise about what a run of it is
        // over, so a reference of another type is refused here rather than
        // becoming a run that can reach something the step never described.
        for (Map.Entry<String, String> slot : step.slots().entrySet()) {
            String reference = inputs.get(slot.getKey());
            if (reference != null && !reference.startsWith(slot.getValue() + "/")) {
                fail(exchange, 400, "invalid_request", "slot '" + slot.getKey() + "' takes "
                        + slot.getValue() + " and was given '" + reference + "'");
                return;
            }
        }
        String scope = Optional.ofNullable(Json.strOpt(body, "scope"))
                .orElseGet(cloud.jengu.dbo.core.UuidV7::newId);
        Run run = runs.of(declaration(step), RunKind.PIPELINE, scope, inputs);
        respond(exchange, 201, "{\"run\":" + quote(run.id()) + ",\"step\":" + quote(stepCode)
                + ",\"context\":" + quote(runPath + "/" + run.id() + "/fhir") + "}");
    }

    /** What the engine is handed: the slots, with the face type as the shape. */
    private StepDeclaration declaration(TenantSpec.Step step) {
        StepDeclaration declaration = StepDeclaration.of(step.code(), "1", "r5");
        for (Map.Entry<String, String> slot : step.slots().entrySet()) {
            declaration = declaration.taking(slot.getKey(), slot.getValue());
        }
        return declaration;
    }

    /** GET /run/&lt;id&gt;/fhir/… — the documents the run named, and nothing else. */
    private void read(HttpExchange exchange, String relative) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run context is read");
            return;
        }
        String[] segments = relative.split("/");
        if (segments.length < 3 || !"fhir".equals(segments[1])) {
            fail(exchange, 404, "not_found", "a run context is /run/<id>/fhir/…");
            return;
        }
        Optional<Run> found = runs.byId(segments[0]);
        if (found.isEmpty()) {
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        Run run = found.get();
        TenantSpec.Step step = declared.get(run.process() + "." + run.step());
        if ("metadata".equals(segments[2])) {
            respond(exchange, 200, metadata(step));
            return;
        }
        if (segments.length != 4) {
            fail(exchange, 404, "not_found", "a document is read as <Type>/<id>");
            return;
        }
        String reference = segments[2] + "/" + segments[3];
        if (!run.inputs().containsValue(reference)) {
            // Not found rather than forbidden, deliberately: see the class
            // note. What this run was given is the whole of what it may read.
            fail(exchange, 404, "not_found", "this run was not given " + reference);
            return;
        }
        FhirStoreFacade.ReadResult result = store.readForServing(segments[2], segments[3]);
        if (result == null) {
            fail(exchange, 404, "not_found", reference + " is named by the run and not held");
            return;
        }
        respond(exchange, 200, result.resourceJson());
    }

    /** What this context answers for: the step's types, and no others. */
    private String metadata(TenantSpec.Step step) {
        StringBuilder types = new StringBuilder();
        if (step != null) {
            step.slots().values().stream().distinct().forEach(type -> {
                if (types.length() > 0) {
                    types.append(',');
                }
                types.append("{\"type\":").append(quote(type))
                        .append(",\"interaction\":[{\"code\":\"read\"}]}");
            });
        }
        return "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\","
                + "\"kind\":\"instance\",\"rest\":[{\"mode\":\"server\",\"resource\":["
                + types + "]}]}";
    }

    private void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        respond(exchange, status, "{\"error\":" + quote(error) + ",\"detail\":"
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

    private static String quote(String value) {
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
