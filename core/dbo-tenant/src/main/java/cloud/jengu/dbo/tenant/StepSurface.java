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
 * <p><b>And it is the performer's.</b> The context and the end of the run
 * answer the client holding the run and nobody else: whoever started it here,
 * performing it inline, until a lane claims it — and then the client that
 * claimed it. Another credential that may act in work, at the same tenant and
 * holding the run's id, is answered exactly as for a run that never existed,
 * and so is everybody while nobody holds the run. Admitting any credential
 * with {@code work} made a run's id the key to its documents, and an id is a
 * thing that gets logged and passed around.
 *
 * <p><b>A read here is a disclosure, and is recorded as one.</b> The entry
 * lands on the document, beside every other reading of it, and names the run
 * as its occasion — so it is answerable both from the document, by somebody
 * who need not know work exists, and from the run.
 *
 * <p>The context reads and does nothing else. The one act beside reading is
 * ending the run, at the run's own address rather than inside its context,
 * because it is a statement about the work and not about a document.
 *
 * <p><b>And then, for a while, the asker's.</b> A step that declares an answer
 * gives the client that asked for its run a window once the result is
 * written: until then plus the step's {@code collect}, the context answers
 * that client for what the run was given and for each version the run
 * produced, read as that version — as the audience the step names, which
 * fixes the types it may collect and what a collection of a person reveals.
 * The run is over meanwhile and nobody holds it; the window is a time beside
 * it, compared with the clock on every read, so it shuts with no transition
 * and no sweep. Past it the context answers as a run that never existed.
 * {@code done} from the asker shuts it now.
 *
 * <p>The reach rule is therefore two lines: the performer while it holds the
 * run, the requester while its window is open, and nobody else.
 *
 * <p><b>The run answers its initiator.</b> {@code GET /run/<id>} gives the
 * client that asked for the run at this door the run as a {@code Task}: how
 * it stands, what it was over, and what the step produced. To that client
 * only — anybody else is answered as if the run did not exist — and after the
 * run has ended too, because the end is the answer it is waiting for.
 */
final class StepSurface implements HttpHandler {

    /** The authority's reading of a bearer, or empty for none it issued. */
    private final java.util.function.Function<String, Optional<TenantAuthority.AuthContext>>
            validated;
    private final Runs runs;
    private final FhirStoreFacade store;
    /**
     * The engine, for turning a search into the references a run is over.
     *
     * <p>Beside the facade rather than instead of it: the facade compiles a
     * face's search parameters into criteria — the same compiler the records
     * surface uses — and the engine is what runs them. Compiling here and
     * matching somewhere else would be two answers to one question.
     */
    private final cloud.jengu.dbo.core.api.ObjectStore engine;
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
    /** This tenant's code, for asking what the deployment offers it. */
    private final String tenant;
    /** What the deployment performs, asked per request rather than held. */
    private final Fleet fleet;
    /**
     * A run as the tenant's face renders it, by id: the same document the
     * records surface answers {@code Task/<id>} with, or empty where the face
     * renders no runs.
     *
     * <p>Taken rather than built here, so a run has one rendering. A second
     * one at this door would be a second answer to "what does a run look like
     * as a Task", and the two would differ the first time either was fixed.
     */
    private final java.util.function.Function<String, Optional<String>> rendered;

    /**
     * The roles a practitioner holds here now, as {@code PractitionerRole}
     * ids: what a person who signed in through the tenant's identity provider
     * takes a run as.
     */
    private final java.util.function.Function<String, List<String>> roles;

    /**
     * What "now" is, for every question this door asks of a run's time —
     * who holds it, and whether its asker's window is open. A field so a
     * test can stand at the instant a window shuts rather than wait for it.
     */
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /** The same door, asking the clock given. */
    StepSurface at(java.time.Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * The header a collecting request states its purpose in: the second key,
     * said at the moment of reading, beside the one the step declared.
     */
    static final String PURPOSE_OF_USE = "Purpose-Of-Use";

    /** How long a person's claim holds before it lapses, unless they checkpoint. */
    static final java.time.Duration A_PERSONS_LEASE = java.time.Duration.ofMinutes(30);

    /**
     * What the DEPLOYMENT declares, for this tenant, right now.
     *
     * <p>Asked per request and not captured when the door was built, because
     * both answers move under it: a deployment's declaration is re-read on
     * every sweep, and whether a tenant may start a step depends on what that
     * tenant declined and on which register rows it has authorised — which is
     * a thing a tenant changes precisely in order to change this.
     */
    @FunctionalInterface
    interface Fleet {

        /**
         * @return what the step takes and whether this tenant may start it, or
         *         null where the deployment declares no such step
         */
        Offer offer(String tenant, String stepCode);

        /** The step's slots, and why it is not startable here if it is not. */
        record Offer(Map<String, String> slots, String refusedBecause) {
        }
    }

    StepSurface(TenantAuthority authority, Runs runs, FhirStoreFacade store,
            cloud.jengu.dbo.core.api.ObjectStore engine,
            List<TenantSpec.Step> steps, String runPath, boolean starting,
            String tenant, Fleet fleet,
            java.util.function.Function<String, Optional<String>> rendered) {
        this(authority::validate, authority::activeRoles, runs, store, engine, steps, runPath,
                starting, tenant, fleet, rendered);
    }

    /** The same, with nobody able to take a run as a person here. */
    StepSurface(java.util.function.Function<String, Optional<TenantAuthority.AuthContext>> validated,
            Runs runs, FhirStoreFacade store,
            cloud.jengu.dbo.core.api.ObjectStore engine,
            List<TenantSpec.Step> steps, String runPath, boolean starting,
            String tenant, Fleet fleet,
            java.util.function.Function<String, Optional<String>> rendered) {
        this(validated, practitioner -> List.of(), runs, store, engine, steps, runPath, starting,
                tenant, fleet, rendered);
    }

    /**
     * The same, with the authority as the one question this door asks of it.
     * The tenant's authority is a whole identity store; who a bearer is, is
     * all a run's address needs to know.
     */
    StepSurface(java.util.function.Function<String, Optional<TenantAuthority.AuthContext>> validated,
            java.util.function.Function<String, List<String>> roles,
            Runs runs, FhirStoreFacade store,
            cloud.jengu.dbo.core.api.ObjectStore engine,
            List<TenantSpec.Step> steps, String runPath, boolean starting,
            String tenant, Fleet fleet,
            java.util.function.Function<String, Optional<String>> rendered) {
        this.roles = roles;
        this.rendered = rendered;
        this.validated = validated;
        this.runs = runs;
        this.store = store;
        this.engine = engine;
        this.runPath = runPath;
        this.starting = starting;
        this.tenant = tenant;
        this.fleet = fleet;
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
            if (!starting && !relative.isEmpty() && relative.indexOf('/') < 0) {
                // The run's own address, which refuses differently: see answer().
                answer(exchange, relative);
                return;
            }
            TenantAuthority.AuthContext admitted = admitted(exchange);
            if (admitted == null) {
                return;
            }
            // Who is asking, before anything is read. The trail's actor comes
            // from the authority and never from the request, exactly as it
            // does on the records surface.
            // A person is named as the practitioner their token says they
            // are, as the records surface names them; anything else as the
            // client.
            cloud.jengu.dbo.core.api.Caller.set(admitted.fhirUser() != null
                    ? admitted.fhirUser() : admitted.clientId());
            if (starting) {
                // NO PURPOSE IS STATED HERE, and none is accepted. A slot may
                // be filled by a search, and a stated purpose is what opens an
                // exact lookup on an identifying element through the vault —
                // so accepting one would make this door a way to ask whether a
                // person with a given number is here, on a credential the
                // tenant's own records surface refuses.
                //
                // Cleared rather than merely left alone: this thread serves the
                // records surface too, and a purpose another request set and
                // did not clear would be in force over this search.
                cloud.jengu.dbo.core.api.Disclosure.clear();
                start(exchange, relative, admitted);
            } else {
                read(exchange, relative, admitted);
            }
        } catch (IllegalArgumentException refused) {
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException failed) {
            fail(exchange, 500, "failed", "the request did not complete");
        } finally {
            // Cleared on the way out, because the thread is reused: a run left
            // behind would occasion the next request's read.
            cloud.jengu.dbo.core.api.Caller.clear();
            // As above: the thread is reused, and a purpose left behind would
            // be stated over somebody else's request.
            cloud.jengu.dbo.core.api.Disclosure.clear();
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
        Optional<TenantAuthority.AuthContext> context = asking(exchange);
        if (context.isEmpty()
                || !cloud.jengu.dbo.auth.Scopes.admitsWork(context.get().scopes())) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "this surface admits a credential that may act in work");
            return null;
        }
        return context.get();
    }

    /** Who the bearer is, as the authority reads it, or empty for no credential or a bad one. */
    private Optional<TenantAuthority.AuthContext> asking(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        return bearer == null ? Optional.empty() : validated.apply(bearer);
    }

    /**
     * POST /step/&lt;module.process.step&gt; — a run over the documents named.
     *
     * @param requester the client id the authority read off the credential,
     *                  recorded on the run as the one it answers
     */
    private void start(HttpExchange exchange, String stepCode,
            TenantAuthority.AuthContext asking) throws IOException {
        String requester = asking.clientId();
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run is started by POSTing to the step");
            return;
        }
        if (!covers(asking, stepCode)) {
            // Bounded to its steps, as a lane credential bounded the same way
            // is: a credential that may take one step's work has not been
            // given another's.
            fail(exchange, 403, "access_denied", "this credential may act in work for "
                    + cloud.jengu.dbo.auth.Scopes.workSteps(asking.scopes()) + ", not "
                    + stepCode);
            return;
        }
        TenantSpec.Step step = declared.get(stepCode);
        Map<String, String> slots;
        if (step != null) {
            slots = step.slots();
        } else {
            // THE DEPLOYMENT'S, and only if it declared one. A tenant's work
            // stream is where a run of a fleet step has to be authored — the
            // joiner reads tenants and nothing else — so the tenant's own door
            // is where it is asked for, and the run belongs to the tenant that
            // asked exactly as its own runs do.
            //
            // This is NOT the same as a step a participant introduced. An
            // introduction is a capability somebody brought and grants its
            // bringer nothing, so the door keeps refusing those by name; a
            // fleet step was declared by the deployment, in the deployment's
            // own record, before any participant said anything.
            Fleet.Offer offered = fleet.offer(tenant, stepCode);
            if (offered == null) {
                fail(exchange, 404, "not_found", "this tenant offers no step '" + stepCode
                        + "'; it offers: " + declared.keySet());
                return;
            }
            if (offered.refusedBecause() != null) {
                // 409 rather than 403: nothing is wrong with the credential.
                // The deployment performs this step and this tenant has said
                // something about it, so the state of the pair is what refuses.
                fail(exchange, 409, "refused", offered.refusedBecause());
                return;
            }
            slots = offered.slots();
        }
        Object body = Json.parse(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        Map<String, Object> given = new LinkedHashMap<>();
        if (Json.objOpt(body, "inputs") instanceof Map<?, ?> named) {
            named.forEach((slot, filled) -> given.put(String.valueOf(slot), filled));
        }
        // The slot's declared shape is a promise about what a run of it is
        // over, so anything else is refused here rather than becoming a run
        // that can reach something the step never described.
        // A SLOT THE STEP DOES NOT DECLARE, refused before anything is read.
        // This used to be the engine's to say, and it still is for every other
        // caller — but nothing undeclared reaches it from here any more, since
        // what is built below is built FROM the declaration. Dropped instead,
        // an input the author meant would be silently ignored and the run would
        // look exactly like one they got right.
        for (String named : given.keySet()) {
            if (!slots.containsKey(named)) {
                fail(exchange, 400, "invalid_request", stepCode + " declares no slot '"
                        + named + "'; it takes: " + slots.keySet());
                return;
            }
        }
        Map<String, cloud.jengu.dbo.work.RunSlot> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            cloud.jengu.dbo.core.process.SlotShape shape =
                    cloud.jengu.dbo.core.process.SlotShape.of(slot.getValue());
            Object filled = given.get(slot.getKey());
            if (filled == null) {
                // Left to the engine, which refuses an unfilled slot by name
                // and is the one place that rule lives.
                continue;
            }
            try {
                inputs.put(slot.getKey(), fill(slot.getKey(), shape, filled));
            } catch (IllegalArgumentException wrong) {
                fail(exchange, 400, "invalid_request", wrong.getMessage());
                return;
            }
        }
        String scope = Optional.ofNullable(Json.strOpt(body, "scope"))
                .orElseGet(cloud.jengu.dbo.core.UuidV7::newId);
        StepDeclaration declaration = declaration(stepCode, slots);
        if (step != null && step.retry() != null) {
            // Recorded on the run as it is authored, so its failures are
            // routed by what this tenant declared wherever it is performed.
            declaration = declaration.retrying(step.retry());
        }
        Run run = runs.filling(declaration, RunKind.PIPELINE, scope, inputs, requester,
                step == null || step.automate() == null ? null
                        : forPeopleBecause(step.automate(), inputs),
                // The asker's window, recorded as the run is authored so the
                // close that opens it needs nothing but the run.
                step == null || step.answer() == null ? null : step.answer().collect());
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

    /**
     * One slot, read against what it was declared to take.
     *
     * <p>The request's shape and the declaration's have to agree, and this is
     * the only place they are compared: a reference where an object was
     * declared reaches a performer as a string it cannot resolve, and an
     * object where a reference was declared is data nobody asked to be sent.
     * Both are refused by name.
     *
     * <p>An object is checked for being the declared type and nothing more.
     * Whether it is a VALID one of them is the face's question, asked where a
     * payload is at hand against the profiles that tenant holds — and asking
     * it here would be a second validator, differing.
     */
    private cloud.jengu.dbo.work.RunSlot fill(String name,
            cloud.jengu.dbo.core.process.SlotShape shape, Object filled) {
        if (filled instanceof List<?> several) {
            if (!shape.many()) {
                throw new IllegalArgumentException("slot '" + name + "' takes "
                        + shape.declared() + " — one of them — and was given a list");
            }
            if (several.isEmpty()) {
                throw new IllegalArgumentException("slot '" + name + "' was given an empty "
                        + "list, and an unfilled slot is not a filled one: every declared "
                        + "slot is mandatory");
            }
            List<String> values = new java.util.ArrayList<>();
            for (Object one : several) {
                // A SEARCH MAY EXPAND. Each value is one thing the caller
                // wrote and any number of things the store holds, and a
                // repeating slot takes them all in the order they were asked
                // for and then matched.
                values.addAll(valuesOf(name, shape, one));
            }
            return shape.referred()
                    ? cloud.jengu.dbo.work.RunSlot.referringTo(values)
                    : cloud.jengu.dbo.work.RunSlot.givenAll(values);
        }
        if (shape.many()) {
            // One value where a list was declared is still a list — a search
            // filling a repeating slot is the ordinary way to write one.
            return cloud.jengu.dbo.work.RunSlot.referringTo(valuesOf(name, shape, filled));
        }
        List<String> one = valuesOf(name, shape, filled);
        if (one.size() != 1) {
            throw new IllegalArgumentException("slot '" + name + "' takes " + shape.declared()
                    + " — one of them — and the search given for it matched " + one.size()
                    + ". Narrow it until it names one, or declare the slot "
                    + shape.declared() + "[] if the step is over however many there are");
        }
        return shape.referred()
                ? cloud.jengu.dbo.work.RunSlot.referring(one.get(0))
                : cloud.jengu.dbo.work.RunSlot.given(one.get(0));
    }

    /**
     * One value the caller wrote, as the values the run will record.
     *
     * <p>One of them, except where it is a search: {@code Organization?…} is
     * resolved HERE, at the door, into the references it matched, and the run
     * records those. Which is the same rule the rest of the model already
     * keeps — what the work is over is fixed when the work is created — and
     * it is what makes a search usable at all: resolved at claim time instead,
     * two performers could be handed different sets, a re-claim after a
     * release could see different data, and the register could not say what
     * was opened.
     *
     * <p><b>It buys no reach.</b> The narrowing is compiled by the face's own
     * search compiler and run by the engine, so every rule a search on the
     * records surface meets is met here — an identifying element still needs a
     * stated purpose, and is still refused by name rather than answered empty.
     */
    private List<String> valuesOf(String name,
            cloud.jengu.dbo.core.process.SlotShape shape, Object one) {
        if (!shape.referred() || one instanceof Map<?, ?>) {
            return List.of(value(name, shape, one));
        }
        String written = String.valueOf(one);
        int query = written.indexOf('?');
        if (query < 0) {
            return List.of(value(name, shape, written));
        }
        String type = written.substring(0, query);
        if (!shape.type().equals(type)) {
            throw new IllegalArgumentException("slot '" + name + "' takes " + shape.declared()
                    + " and was given a search for '" + type + "'");
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : written.substring(query + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int is = pair.indexOf('=');
            if (is < 0) {
                throw new IllegalArgumentException("slot '" + name + "': '" + pair
                        + "' is not a search parameter");
            }
            params.put(decoded(pair.substring(0, is)), decoded(pair.substring(is + 1)));
        }
        if (params.isEmpty()) {
            throw new IllegalArgumentException("slot '" + name + "' was given a search with no "
                    + "parameters, which is every " + type + " this tenant holds");
        }
        List<String> found = new java.util.ArrayList<>();
        try {
            for (cloud.jengu.dbo.core.api.StoredObject matched
                    : engine.select(store.narrow(type, params))) {
                found.add(type + "/" + matched.id());
            }
        } catch (cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException identifying) {
            // ANSWERED IN THIS DOOR'S OWN WORDS. The engine's refusal says to
            // state a purpose, which is true of the records surface and false
            // here: this door states none and accepts none, so repeating that
            // advice would send the caller round a loop it cannot leave.
            throw new IllegalArgumentException("slot '" + name + "': this door does not match "
                    + "on an identifying element, and no stated purpose will change that — "
                    + "asking whether somebody is here is not something a credential for work "
                    + "may do. Name the record by '" + type + "/<id>', or find it on this "
                    + "tenant's records surface with a credential for that. (" 
                    + identifying.getMessage() + ")", identifying);
        }
        if (found.isEmpty()) {
            // Said as nothing matched, which is what happened. A slot left
            // unfilled is refused by the engine a moment later and would read
            // as the caller having forgotten it.
            throw new IllegalArgumentException("slot '" + name + "': the search '" + written
                    + "' matched nothing, so there is nothing for this run to be over");
        }
        return found;
    }

    private static String decoded(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** One value of a slot: a reference of the declared type, or an object of it. */
    private static String value(String name,
            cloud.jengu.dbo.core.process.SlotShape shape, Object one) {
        if (shape.referred()) {
            if (one instanceof Map<?, ?>) {
                throw new IllegalArgumentException("slot '" + name + "' takes "
                        + shape.declared() + " and was given an object. A reference names "
                        + "something this tenant already holds; to send the object itself the "
                        + "step declares the slot '" + shape.type() + "'");
            }
            String reference = String.valueOf(one);
            if (!reference.startsWith(shape.type() + "/")) {
                throw new IllegalArgumentException("slot '" + name + "' takes "
                        + shape.declared() + " and was given '" + reference + "'");
            }
            return reference;
        }
        if (!(one instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("slot '" + name + "' takes " + shape.declared()
                    + " — the object itself — and was given '" + one + "'. To name something "
                    + "this tenant already holds, the step declares the slot 'Reference("
                    + shape.type() + ")'");
        }
        Object said = object.get("resourceType");
        if (!shape.type().equals(String.valueOf(said))) {
            throw new IllegalArgumentException("slot '" + name + "' takes " + shape.declared()
                    + " and was given a '" + said + "'");
        }
        // The wire writer, because this module has a parser and no renderer.
        // A parsed object written back is the same object: what came in was
        // JSON and the maps and lists it became carry nothing else.
        return cloud.jengu.dbo.core.wire.RecordWire.write(object);
    }

    /**
     * Why a run is for people alone, or null when its step's condition admits
     * automation — decided here, once, as the run is authored.
     *
     * <p>What a slot names is read as the store holds it, which for a tenant
     * behind the membrane is the carrier form. That is enough: a condition
     * reading an element that identifies a person was refused when the step
     * was declared, so nothing the decision needs is sealed, and deciding is
     * the machinery's read rather than a disclosure.
     */
    private String forPeopleBecause(cloud.jengu.dbo.core.process.AutomationCriterion when,
            Map<String, cloud.jengu.dbo.work.RunSlot> inputs) {
        boolean admitted = when.admits(slot -> {
            cloud.jengu.dbo.work.RunSlot filled = inputs.get(slot);
            List<Object> values = new java.util.ArrayList<>();
            if (filled == null) {
                return values;
            }
            for (String value : filled.values()) {
                if (!filled.referred()) {
                    values.add(Json.parse(value));
                    continue;
                }
                int slash = value.indexOf('/');
                if (slash > 0) {
                    engine.get(value.substring(0, slash), value.substring(slash + 1))
                            .ifPresent(held -> values.add(Json.parse(
                                    new String(held.payload(), StandardCharsets.UTF_8))));
                }
            }
            return values;
        });
        return admitted ? null : "its step admits automation only when " + when.expression();
    }

    /** What the engine is handed: the slots, with the face type as the shape. */
    private StepDeclaration declaration(String code, Map<String, String> slots) {
        StepDeclaration declaration = StepDeclaration.of(code, "1", "r5");
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            // THE TYPE. What the engine holds per slot is the shape a payload
            // is checked against, and 'Reference(Organization)' is not a shape
            // anything is: the declared form is the DECLARATION's, and what
            // ends up in the slot is an Organization either way.
            declaration = declaration.taking(slot.getKey(),
                    cloud.jengu.dbo.core.process.SlotShape.of(slot.getValue()).type());
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
     *
     * @param asking the client the authority read off the credential, which
     *               must be the one holding the run
     */
    private void read(HttpExchange exchange, String relative,
            TenantAuthority.AuthContext admitted) throws IOException {
        String asking = admitted.clientId();
        String[] segments = relative.split("/");
        if (segments.length == 2 && "done".equals(segments[1])) {
            done(exchange, segments[0], asking);
            return;
        }
        if (segments.length == 2 && "claim".equals(segments[1])) {
            claim(exchange, segments[0], admitted);
            return;
        }
        if (segments.length == 2 && "checkpoint".equals(segments[1])) {
            checkpoint(exchange, segments[0], asking);
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
        if (!held(found, asking) && found.isPresent()
                && found.get().collectableBy(asking, clock.instant())) {
            collect(exchange, segments, found.get());
            return;
        }
        // A run that has ended answers exactly as one that never existed. The
        // context is the work, so it lasts as long as the work does: a run
        // closed an hour ago whose base url still served would be a standing
        // grant left behind by a piece of work nobody is doing — and it is
        // the same answer either way, because saying "this run is over"
        // confirms it was real.
        //
        // And a run somebody ELSE holds answers the same, for the same
        // reason: the context is the performer's door, and a second
        // credential that may act in work is not performing this run because
        // it learned its id.
        if (!held(found, asking)) {
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        Run run = found.get();
        if ("metadata".equals(segments[2])) {
            // The run's own step, whichever level declared it: a context over
            // a fleet run answers for the types that run reaches exactly as a
            // context over one of the tenant's own does, because a capability
            // statement that went blank for half the runs would read as a
            // context that reaches nothing.
            String code = run.process() + "." + run.step();
            TenantSpec.Step own = declared.get(code);
            Map<String, String> slots = own != null ? own.slots() : null;
            if (slots == null) {
                Fleet.Offer offered = fleet.offer(tenant, code);
                slots = offered == null ? null : offered.slots();
            }
            respond(exchange, 200, metadata(slots));
            return;
        }
        if (segments.length != 4) {
            fail(exchange, 404, "not_found", "a document is read as <Type>/<id>");
            return;
        }
        String reference = segments[2] + "/" + segments[3];
        // ANY VALUE OF ANY SLOT. A slot holds a list now, so asking whether the
        // fill EQUALS this reference is a question that is always answered no —
        // and the answer here is a 404, which reads as a run that was never
        // given the document rather than as a check that stopped working.
        boolean given = run.inputs().values().stream()
                .anyMatch(filled -> filled.referred() && filled.values().contains(reference));
        if (!given) {
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
     * GET /run/&lt;id&gt; — the run, answered to the client that asked for it.
     *
     * <p>The answer is a {@code Task}: the run's id, its key as the
     * {@code urn:dbo:run} identifier, its status, the slots as they were
     * filled, and the step's result as outputs — the counts it kept and the
     * versions it produced. It is what the asking application is owed and no
     * more: the records the run reached are not in it, so the credential that
     * asked for work still holds no door onto the records.
     *
     * <p>Not recorded as a disclosure, unlike a read in the run's context. It
     * carries no document — references and counts, which the asker named or
     * the step reported — so there is nothing in it a trail of who read which
     * record would be about.
     */
    private void answer(HttpExchange exchange, String id) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run's answer is read");
            return;
        }
        Optional<TenantAuthority.AuthContext> asking = asking(exchange);
        if (asking.isEmpty()) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, 401, "access_denied", "a run answers the credential that asked for it");
            return;
        }
        cloud.jengu.dbo.core.api.Caller.set(asking.get().clientId());
        Optional<Run> found = runs.byId(id);
        if (reach(asking, found) != 200) {
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        Optional<String> task = rendered.apply(found.get().id()).map(StepSurface::theRun);
        if (task.isEmpty()) {
            fail(exchange, 501, "not_implemented",
                    "this tenant's face renders no run as a Task");
            return;
        }
        respond(exchange, 200, task.get());
    }

    /**
     * Whether a run answers who is asking: 200, or the refusal.
     *
     * <p><b>Not found, never forbidden</b>, for everything but a missing
     * credential. Another client, a credential that may not act in work, a run
     * asked for on the records surface or by a lane, and a run that does not
     * exist all get the same 404: a refusal that differed from absence would
     * tell whoever probes the address which runs exist. Only "you presented
     * nothing" is said as itself, because it says nothing about any run.
     */
    static int reach(Optional<TenantAuthority.AuthContext> asking, Optional<Run> run) {
        if (asking.isEmpty()) {
            return 401;
        }
        if (!asking.get().scopes().contains(cloud.jengu.dbo.auth.Scopes.WORK)) {
            return 404;
        }
        if (run.isEmpty() || run.get().requester() == null
                || !run.get().requester().equals(asking.get().clientId())) {
            return 404;
        }
        return 200;
    }

    /**
     * The run's own {@code Task} out of the face's document, which carries
     * the run first and its items after it. The items are the run's
     * exceptions for a person to act on, and the asker is owed the result.
     */
    private static String theRun(String document) {
        Object parsed = cloud.jengu.dbo.core.wire.RecordWire.read(document);
        if (parsed instanceof Map<?, ?> map && "Task".equals(map.get("resourceType"))) {
            return document;
        }
        if (parsed instanceof Map<?, ?> bundle && bundle.get("entry") instanceof List<?> entries
                && !entries.isEmpty() && entries.get(0) instanceof Map<?, ?> first
                && first.get("resource") instanceof Map<?, ?> resource) {
            return cloud.jengu.dbo.core.wire.RecordWire.write(resource);
        }
        throw new IllegalStateException("the face rendered a run as neither a Task nor a "
                + "document holding one");
    }

    /**
     * POST /run/&lt;id&gt;/done — the work is finished, and the context closes
     * with it.
     *
     * <p>The same answer as a read for a run that is not there, or that
     * somebody else holds, and for the same reason: a caller that may end a
     * run it cannot name would be told,
     * by the difference between the two refusals, which runs exist. Ending a
     * run twice is not an error — the second call finds a run nobody holds,
     * which is what it asked for.
     */
    private void done(HttpExchange exchange, String id, String asking) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run is ended by POSTing to it");
            return;
        }
        Optional<Run> found = runs.byId(id);
        if (!held(found, asking) && found.isPresent()
                && found.get().collectableBy(asking, clock.instant())) {
            // The asker is done collecting: its window shuts now, and the run
            // — over already — is otherwise untouched.
            Run collected = runs.collected(found.get(), clock.instant());
            respond(exchange, 200, "{\"run\":" + quote(collected.id()) + ",\"key\":"
                    + quote(collected.key()) + ",\"status\":"
                    + quote(collected.status().wire()) + "}");
            return;
        }
        // Ended only by whoever holds it. Somebody else ending a run would
        // close a context out from under the performer, and record a run as
        // done that its performer never said was.
        if (!held(found, asking)) {
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
                + quote(ended.key()) + ",\"status\":" + quote(ended.status().wire()) + "}");
    }

    /**
     * POST /run/&lt;id&gt;/claim — a person takes the run, as the role they
     * hold here (REQ-DBO-PROC-A-PERSON-CLAIMS-AS-A-PRACTITIONER-ROLE).
     *
     * <p><b>On the person's own token.</b> The tenant's identity provider
     * issued it and it names the practitioner; the role is the tenant's
     * record, read now, and the run names it as what holds it. A trail entry
     * the person signed is stronger than one recording what an application
     * said about them, which is why no application can take a run for a
     * person here.
     *
     * <p>Then the run is theirs as it would be an executor's: its context
     * answers them and nobody else, on a lease their checkpoints extend, and
     * they end it at {@code /done}. A run somebody holds, or one that is
     * over, is not taken — said as a conflict, because the person asking was
     * offered it and is owed the reason it was not theirs. A credential with
     * no role here, or one whose work does not reach the run's step, is
     * answered as for a run that never existed.
     */
    private void claim(HttpExchange exchange, String id, TenantAuthority.AuthContext asking)
            throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a run is taken by POSTing to it");
            return;
        }
        Optional<Run> found = runs.byId(id);
        String practitioner = asking.fhirUser() != null
                && asking.fhirUser().startsWith("Practitioner/")
                ? asking.fhirUser().substring("Practitioner/".length()) : null;
        List<String> held = practitioner == null ? List.of() : roles.apply(practitioner);
        if (found.isEmpty() || held.isEmpty()
                || !covers(asking, found.get().process() + "." + found.get().step())) {
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        String asked = query(exchange, "role");
        String role = asked == null ? held.get(0)
                : asked.startsWith("PractitionerRole/")
                        ? asked.substring("PractitionerRole/".length()) : asked;
        if (!held.contains(role)) {
            fail(exchange, 403, "access_denied", "the practitioner does not hold "
                    + "PractitionerRole/" + role + " here now");
            return;
        }
        Optional<Run> taken = runs.claimAsPerson(found.get(), "PractitionerRole/" + role,
                A_PERSONS_LEASE, asking.clientId());
        if (taken.isEmpty()) {
            fail(exchange, 409, "conflict", "somebody holds this run, or it is over");
            return;
        }
        respond(exchange, 200, "{\"run\":" + quote(taken.get().id()) + ",\"key\":"
                + quote(taken.get().key()) + ",\"status\":"
                + quote(taken.get().status().wire()) + ",\"owner\":"
                + quote(taken.get().assignment().role()) + ",\"until\":"
                + quote(String.valueOf(taken.get().assignment().until())) + "}");
    }

    /**
     * POST /run/&lt;id&gt;/checkpoint — the holder is still at it, which
     * extends the lease as an executor's checkpoint does. Answered as a read
     * is for anybody who does not hold the run.
     */
    private void checkpoint(HttpExchange exchange, String id, String asking) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            fail(exchange, 405, "invalid_request", "a checkpoint is POSTed to the run");
            return;
        }
        Optional<Run> found = runs.byId(id);
        if (!held(found, asking)) {
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        Run extended = runs.checkpoint(found.get(), Map.of(),
                java.time.Instant.now().plus(A_PERSONS_LEASE));
        respond(exchange, 200, "{\"run\":" + quote(extended.id()) + ",\"until\":"
                + quote(String.valueOf(extended.assignment().until())) + "}");
    }

    /**
     * Whether a credential's work reaches a step: the bare scope reaches
     * every step, and one bounded to steps reaches those it names, by the
     * catalogue's id or the step's own.
     */
    private static boolean covers(TenantAuthority.AuthContext asking, String stepCode) {
        if (cloud.jengu.dbo.auth.Scopes.worksAsTheTenant(asking.scopes())) {
            return true;
        }
        return cloud.jengu.dbo.auth.Scopes.workSteps(asking.scopes()).contains(stepCode);
    }

    /** One query parameter, decoded, or null. */
    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(equals + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Whether the client asking holds this run now, which opens it to its performer. */
    private boolean held(Optional<Run> run, String asking) {
        return run.isPresent() && run.get().heldBy(asking, clock.instant());
    }

    /**
     * A read by the run's requester, inside its window: what the run was
     * given, and each version it produced, as the audience its step names.
     *
     * <p><b>Reach is what the run names, and a produced record is read as the
     * version it produced</b> — {@code Type/id/_history/n}, not whatever the
     * record says now. What the asker is owed is what its run did, and a later
     * write by somebody else is not that.
     *
     * <p><b>What it sees is the audience's.</b> A type the audience is not
     * answered about is as absent as a document the run never named, in the
     * same words. A person is revealed as the audience's mode says, and whole
     * only when the request's {@code Purpose-Of-Use} is the code the step
     * declared: either key alone is the strict mode, never a refusal, because
     * a collection that answered differently for a wrong purpose would say
     * the record held somebody worth refusing.
     *
     * <p><b>Each read is a reading on the trail</b>: occasioned by the run,
     * acted by the asker's client, under the step's purpose — an access entry
     * on the document, never travel. As many as the asker makes: a retry is
     * the ordinary case, and each one is somebody reading.
     */
    private void collect(HttpExchange exchange, String[] segments, Run run) throws IOException {
        TenantSpec.Step step = declared.get(run.process() + "." + run.step());
        TenantSpec.Answer answer = step == null ? null : step.answer();
        if (answer == null) {
            // The step was re-declared without an answer since the run was
            // asked for: the window it recorded has nothing left to show.
            fail(exchange, 404, "not_found", "no such run");
            return;
        }
        if ("metadata".equals(segments[2])) {
            java.util.Set<String> types = new java.util.LinkedHashSet<>();
            step.slots().values().forEach(declared ->
                    types.add(cloud.jengu.dbo.core.process.SlotShape.of(declared).type()));
            types.addAll(step.writes());
            types.retainAll(answer.types());
            Map<String, String> collectable = new LinkedHashMap<>();
            types.forEach(type -> collectable.put(type, type));
            respond(exchange, 200, metadata(collectable));
            return;
        }
        boolean versioned = segments.length == 6 && "_history".equals(segments[4]);
        if (segments.length != 4 && !versioned) {
            fail(exchange, 404, "not_found", "a document is read as <Type>/<id>, and a "
                    + "version the run produced as <Type>/<id>/_history/<n>");
            return;
        }
        String reference = segments[2] + "/" + segments[3];
        boolean named = versioned
                ? run.produced().versions().contains(reference + "/" + segments[5])
                : run.inputs().values().stream().anyMatch(filled -> filled.referred()
                        && filled.values().contains(reference));
        if (!named || !answer.types().contains(segments[2])) {
            fail(exchange, 404, "not_found", "this run was not given " + reference);
            return;
        }
        String stated = exchange.getRequestHeaders().getFirst(PURPOSE_OF_USE);
        cloud.jengu.dbo.core.api.Disclosure.set(answer.revealing(stated == null ? null
                : stated.trim()), answer.purpose());
        cloud.jengu.dbo.core.api.Caller.setRun(run.key());
        try {
            if (versioned) {
                long version;
                try {
                    version = Long.parseLong(segments[5]);
                } catch (NumberFormatException notAVersion) {
                    fail(exchange, 404, "not_found", "this run was not given " + reference);
                    return;
                }
                FhirStoreFacade.VersionRead read =
                        store.versionForServing(segments[2], segments[3], version);
                if (read == null) {
                    fail(exchange, 404, "not_found", reference + " is named by the run and "
                            + "not held");
                } else if (read.deleted()) {
                    // The version that removed it: answered as gone, which
                    // discloses nothing and is not the same as never having been.
                    fail(exchange, 410, "gone", reference + "/_history/" + version
                            + " is the version that removed it");
                } else {
                    respond(exchange, 200, read.resourceJson());
                }
                return;
            }
            FhirStoreFacade.ReadResult result = store.readForServing(segments[2], segments[3]);
            if (result == null) {
                fail(exchange, 404, "not_found", reference + " is named by the run and not held");
                return;
            }
            respond(exchange, 200, result.resourceJson());
        } finally {
            cloud.jengu.dbo.core.api.Caller.clearRun();
        }
    }

    /** What this context answers for: the step's types, and no others. */
    private String metadata(Map<String, String> slots) {
        StringBuilder types = new StringBuilder();
        if (slots != null) {
            slots.values().stream()
                    .map(declared -> cloud.jengu.dbo.core.process.SlotShape.of(declared).type())
                    .distinct()
                    .forEach(type -> {
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
