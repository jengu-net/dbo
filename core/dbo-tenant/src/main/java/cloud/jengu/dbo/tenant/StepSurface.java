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
 * <p><b>The context lasts as long as the work does.</b> A run nobody holds is
 * over, and its base url answers like a run that never existed. Otherwise
 * performing a piece of work once would leave a standing way in behind it,
 * which is the opposite of granting access to a step.
 *
 * <p><b>A read here is a disclosure, and is recorded as one.</b> The entry
 * lands on the document, beside every other reading of it, and names the run
 * as its occasion — so it is answerable both from the document, by somebody
 * who need not know work exists, and from the run.
 *
 * <p>The context reads and does nothing else. The one act beside reading is
 * ending the run, at the run's own address rather than inside its context,
 * because it is a statement about the work and not about a document.
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
            TenantAuthority.AuthContext admitted = admitted(exchange);
            if (admitted == null) {
                return;
            }
            // Who is asking, before anything is read. The trail's actor comes
            // from the authority and never from the request, exactly as it
            // does on the records surface.
            cloud.jengu.dbo.core.api.Caller.set(admitted.clientId());
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
            // Cleared on the way out, because the thread is reused: a run left
            // behind would occasion the next request's read.
            cloud.jengu.dbo.core.api.Caller.clear();
            exchange.close();
        }
    }

    /**
     * A credential that may act in work, and deliberately not the broad one.
     *
     * <p>The point of the surface is that holding it is not holding the
     * store: a token admitted here is refused by the tenant's own records
     * surface, and one admitted there has no business arriving through a run.
     *
     * @return who is asking, or null when the request has already been
     *         answered with a refusal
     */
    private TenantAuthority.AuthContext admitted(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        Optional<TenantAuthority.AuthContext> context =
                bearer == null ? Optional.empty() : authority.validate(bearer);
        if (context.isEmpty() || !context.get().scopes().contains(cloud.jengu.dbo.auth.Scopes.WORK)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "this surface admits a credential that may act in work");
            return null;
        }
        return context.get();
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
        // The key as well as the id, because they answer different questions
        // and only one of them is this surface's. The id addresses the
        // context; the key is the name the rest of the work model is asked by
        // — the trail's `run` parameter among them — so a caller that started
        // a run here can ask what it did without first holding a credential
        // that may read the run's own record to find its name out.
        respond(exchange, 201, "{\"run\":" + quote(run.id()) + ",\"key\":" + quote(run.key())
                + ",\"step\":" + quote(stepCode)
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

    /**
     * The run's own address: its context at {@code /fhir/…}, and the one act
     * that is not a read.
     *
     * <p>Ending a run is here rather than on the lane because this slice is
     * the synchronous half: the caller starts a run, reads what it was given,
     * does the work and says it is done, all with a curl. Claiming queued work
     * is the lane's, and a participant that polls closes there with the run it
     * was handed.
     */
    private void read(HttpExchange exchange, String relative) throws IOException {
        String[] segments = relative.split("/");
        if (segments.length == 2 && "done".equals(segments[1])) {
            done(exchange, segments[0]);
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run context is read");
            return;
        }
        if (segments.length < 3 || !"fhir".equals(segments[1])) {
            fail(exchange, 404, "not_found", "a run context is /run/<id>/fhir/…");
            return;
        }
        Optional<Run> found = runs.byId(segments[0]);
        // A run that has ended answers exactly as one that never existed. The
        // context is the work, so it lasts as long as the work does: a run
        // closed an hour ago whose base url still served would be a standing
        // grant left behind by a piece of work nobody is doing — and it is
        // the same answer either way, because saying "this run is over"
        // confirms it was real.
        if (found.isEmpty() || found.get().holder() == cloud.jengu.dbo.work.Holder.NOBODY) {
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
        // Occasioned by the run, which is what makes the read answerable from
        // both ends: the access entry lands on the DOCUMENT, beside every
        // other reading of it, and carries the run — so "who has read this"
        // is answerable by somebody who need not know work exists, and "what
        // did this run open" by somebody who does. Without it the entry is
        // the tenant's ordinary read traffic, kept or dropped by the audit
        // level, and a disclosure made through a run would be the one reading
        // nobody could account for.
        FhirStoreFacade.ReadResult result;
        cloud.jengu.dbo.core.api.Caller.setRun(run.key());
        try {
            result = store.readForServing(segments[2], segments[3]);
        } finally {
            cloud.jengu.dbo.core.api.Caller.clearRun();
        }
        if (result == null) {
            fail(exchange, 404, "not_found", reference + " is named by the run and not held");
            return;
        }
        respond(exchange, 200, result.resourceJson());
    }

    /**
     * POST /run/&lt;id&gt;/done — the work is finished, and the context closes
     * with it.
     *
     * <p>The same answer as a read for a run that is not there, and for the
     * same reason: a caller that may end a run it cannot name would be told,
     * by the difference between the two refusals, which runs exist. Ending a
     * run twice is not an error — the second call finds a run nobody holds,
     * which is what it asked for.
     */
    private void done(HttpExchange exchange, String id) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run is ended by POSTing to it");
            return;
        }
        Optional<Run> found = runs.byId(id);
        if (found.isEmpty() || found.get().holder() == cloud.jengu.dbo.work.Holder.NOBODY) {
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        Run ended;
        try {
            ended = runs.closed(found.get());
        } catch (Runs.NotAnAction refused) {
            // A step that declares its actions and omits close has said its
            // closure is somebody else's act. Told by name rather than as a
            // fault, because it is an answer about the step and not a
            // breakage.
            fail(exchange, 409, "not_an_action", String.valueOf(refused.getMessage()));
            return;
        }
        respond(exchange, 200, "{\"run\":" + quote(ended.id()) + ",\"key\":"
                + quote(ended.key()) + ",\"holder\":" + quote(ended.holder().wire()) + "}");
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
