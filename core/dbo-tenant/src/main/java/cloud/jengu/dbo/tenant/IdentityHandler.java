package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Adjudications;
import cloud.jengu.dbo.auth.Anonymity;
import cloud.jengu.dbo.auth.Bindings;
import cloud.jengu.dbo.auth.Identities;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.identity.Adjudication;
import cloud.jengu.dbo.core.api.identity.AnonymityEvent;
import cloud.jengu.dbo.core.api.identity.Assurance;
import cloud.jengu.dbo.core.api.identity.BindingEvent;
import cloud.jengu.dbo.core.api.identity.Candidate;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;
import cloud.jengu.dbo.core.api.identity.Resolution;
import cloud.jengu.dbo.core.wire.RecordWire;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The door identification is asked for through.
 *
 * <p>The toolset underneath it — claims, candidates, adjudication, binding,
 * anonymity — was built, proven and reachable by nothing. Its types are
 * registered for every tenant that has an authority, its rules are driven by
 * seven integration tests, and no production code called any of it: a harness
 * <i>is</i> the container and constructs whatever it needs, so every one of
 * those tests passed while a tenant could not identify anybody. This is the
 * door that makes the layer something a deployment can use.
 *
 * <p><b>Its own scope, outside the resource grammar</b>, like SCIM,
 * participation and erasure. Binding is the act no read control touches: an
 * anonymous subject has no identity to read, so a credential bounded to
 * reading resources protects nothing here. Separate from erasure rather than
 * folded into it, because they are opposite acts on the same person — one
 * attaches an identity, the other destroys the key that made one legible — and
 * letting a desk identify people is not saying it may erase them.
 *
 * <p><b>Mechanism, not policy.</b> This offers what the engine owes: claims
 * have strength, matching returns candidates, a decision is recorded, a
 * binding is an audited event, and anonymity is something a subject can
 * declare. What counts as identification at a pharmacy counter and what an
 * adjudicator is shown are the zone's to decide, and neither is expressible
 * here.
 */
public final class IdentityHandler implements HttpHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("dbo.identity");

    /** What a credential must carry to reach this door and nothing else. */
    public static final String SCOPE = cloud.jengu.dbo.auth.Scopes.IDENTITY;

    private final TenantAuthority authority;
    private final ObjectStore store;
    private final String base;
    /**
     * The type holding identity records — {@code Person} for the FHIR face.
     *
     * <p>Given rather than known. Naming it here would be this door knowing a
     * domain, and the whole of the layer below takes it as a parameter for
     * exactly that reason.
     */
    private final String identityType;

    public IdentityHandler(TenantAuthority authority, ObjectStore store, String base,
            String identityType) {
        this.authority = authority;
        this.store = store;
        this.base = base;
        this.identityType = identityType;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String verb = exchange.getRequestURI().getPath().substring(base.length());
            if (verb.startsWith("/")) {
                verb = verb.substring(1);
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                fail(exchange, 405, "invalid_request", "identification is asked for with POST");
                return;
            }
            if (!permitted(exchange)) {
                return;
            }
            Map<String, Object> body = asFields(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            switch (verb) {
                case "resolve" -> resolve(exchange, body);
                case "adjudicate" -> adjudicate(exchange, body);
                case "bind" -> bind(exchange, body, BindingEvent.Kind.BOUND);
                case "unbind" -> bind(exchange, body, BindingEvent.Kind.WITHDRAWN);
                case "anonymity" -> anonymity(exchange, body);
                case "subject" -> subject(exchange, body);
                default -> fail(exchange, 404, "invalid_request",
                        "this door offers resolve, adjudicate, bind, unbind, anonymity "
                                + "and subject; it was asked for '" + verb + "'");
            }
        } catch (Anonymity.AnonymityRefusedException declined) {
            // Not a fault. Somebody declared they are not to be identified,
            // and the refusal is the declaration working — said as its own
            // status so a caller can tell it from a bad request.
            fail(exchange, 409, "anonymity_declared", String.valueOf(declined.getMessage()));
        } catch (IllegalArgumentException refused) {
            fail(exchange, 400, "invalid_request", String.valueOf(refused.getMessage()));
        } catch (RuntimeException failed) {
            // Said in the log, not to the caller. A failed identification has
            // changed nothing, so there is no half-done state to describe —
            // but the fault may name a person's identifier, and a 500 body is
            // the one place that must not carry one (§14).
            LOG.warn("identification failed: tenant door={} ", base, failed);
            fail(exchange, 500, "identification_failed", "the request did not complete");
        } finally {
            exchange.close();
        }
    }

    /**
     * What the store makes of the claims presented — candidates, never an
     * answer.
     *
     * <p>An empty list is a normal outcome and is answered as one: nobody here
     * is what the store knows, and reporting it as an error would make "this
     * person is new" indistinguishable from "something went wrong".
     */
    private void resolve(HttpExchange exchange, Map<String, Object> body) throws IOException {
        Resolution resolution = Identities.resolve(store, identityType, claimsIn(body));
        List<Object> candidates = new ArrayList<>();
        for (Candidate candidate : resolution.candidates()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("subject", candidate.subjectId());
            one.put("confidence", candidate.confidence().name());
            one.put("matched", candidate.matched().stream().map(IdentityHandler::asFields).toList());
            // Marked rather than hidden, which is the adjudication record
            // earning its keep: an adjudicator sees the question was asked
            // before and may answer differently on new evidence.
            one.put("previouslyRejected", candidate.previouslyRejected());
            candidates.add(one);
        }
        respond(exchange, 200, Map.of("candidates", candidates));
    }

    private void adjudicate(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String actor = required(body, "decidedBy");
        Adjudication decision = new Adjudication(
                enumOf(Adjudication.Outcome.class, required(body, "outcome"), "outcome"),
                // Required even when the outcome is CREATED: a decision that
                // named nobody could not be revisited, and "a new person was
                // made" is only evidence if it says which one.
                required(body, "subject"),
                strings(body, "rejected"),
                claimsIn(body),
                actor, Instant.now(), str(body, "because"));
        respond(exchange, 201, Map.of("adjudication", Adjudications.record(store, decision)));
    }

    private void bind(HttpExchange exchange, Map<String, Object> body, BindingEvent.Kind kind)
            throws IOException {
        BindingEvent event = new BindingEvent(kind,
                required(body, "identity"), required(body, "subject"),
                enumOf(Assurance.class, str(body, "assurance") == null
                        ? Assurance.NONE.name() : str(body, "assurance"), "assurance"),
                required(body, "actor"), Instant.now(),
                // Both required by the record beneath, and asked for here so
                // the refusal is the door's rather than a null somebody has to
                // read a stack trace to understand.
                required(body, "purpose"), str(body, "because"));
        respond(exchange, 201, Map.of("binding", Bindings.record(store, event)));
    }

    private void anonymity(HttpExchange exchange, Map<String, Object> body) throws IOException {
        AnonymityEvent event = new AnonymityEvent(
                enumOf(AnonymityEvent.Kind.class, required(body, "kind"), "kind"),
                required(body, "subject"), required(body, "actor"), Instant.now(),
                str(body, "basis"), str(body, "because"));
        respond(exchange, 201, Map.of("anonymity", Anonymity.record(store, event)));
    }

    /**
     * Where one subject stands: whether it declared anonymity, what is bound to
     * it, and how strongly.
     *
     * <p>Here rather than left to a caller assembling three requests, because
     * the three answers are only useful together — a workflow that read the
     * bindings and not the declaration is precisely the one that identifies
     * somebody who had a right not to be.
     */
    private void subject(HttpExchange exchange, Map<String, Object> body) throws IOException {
        String subjectId = required(body, "subject");
        // Assurance is per identity, not per subject, and is reported that
        // way. Collapsing several bindings into one figure would have to pick
        // the strongest or the weakest, and either answer is wrong for the
        // rule it feeds: what somebody may do is bounded by the assurance of
        // the identification THEY presented.
        Map<String, Object> assurance = new LinkedHashMap<>();
        for (String identityId : Bindings.current(store, subjectId)) {
            assurance.put(identityId, Bindings.assuranceOf(store, subjectId, identityId).name());
        }
        respond(exchange, 200, Map.of(
                "subject", subjectId,
                "anonymous", Anonymity.declared(store, subjectId),
                "bound", assurance));
    }

    // ------------------------------------------------------------ reading

    private static List<IdentityClaim> claimsIn(Map<String, Object> body) {
        List<IdentityClaim> claims = new ArrayList<>();
        Object presented = body.get("claims");
        if (!(presented instanceof List<?> list)) {
            return claims;
        }
        for (Object entry : list) {
            Map<String, Object> claim = asFields(entry);
            claims.add(new IdentityClaim(
                    required(claim, "system"), required(claim, "value"),
                    enumOf(IdentityClaim.Verification.class,
                            str(claim, "verification") == null
                                    ? IdentityClaim.Verification.ASSERTED.name()
                                    : str(claim, "verification"), "verification"),
                    enumOf(IdentityClaim.Status.class,
                            str(claim, "status") == null
                                    ? IdentityClaim.Status.ACTIVE.name() : str(claim, "status"),
                            "status")));
        }
        return claims;
    }

    private static Map<String, Object> asFields(IdentityClaim claim) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("system", claim.system());
        out.put("value", claim.value());
        out.put("verification", claim.verification().name());
        out.put("status", claim.status().name());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asFields(Object node) {
        Object read = node instanceof String text ? RecordWire.read(text) : node;
        if (read instanceof Map<?, ?> fields) {
            return (Map<String, Object>) fields;
        }
        throw new IllegalArgumentException("the body must be an object");
    }

    private static String str(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String required(Map<String, Object> body, String field) {
        String value = str(body, field);
        if (value == null) {
            throw new IllegalArgumentException("'" + field + "' is required");
        }
        return value;
    }

    private static List<String> strings(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * A closed vocabulary read from an open request.
     *
     * <p>Named in the refusal rather than defaulted. A caller that spelt
     * {@code SUBSTANCIAL} meant something, and quietly reading it as
     * {@code NONE} would bind at the weakest assurance while reporting success
     * — which is the shape of failure this whole layer exists to prevent.
     */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        List<String> known = new ArrayList<>();
        for (E constant : type.getEnumConstants()) {
            known.add(constant.name().toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("'" + field + "' must be one of " + known
                + "; it said '" + value + "'");
    }

    private boolean permitted(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        Optional<TenantAuthority.AuthContext> context =
                bearer == null ? Optional.empty() : authority.validate(bearer);
        if (context.isEmpty() || !context.get().scopes().contains(SCOPE)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            fail(exchange, context.isEmpty() ? 401 : 403, "access_denied",
                    "identifying a person needs the '" + SCOPE + "' scope, which a grant "
                            + "over the store's resources does not imply");
            return false;
        }
        return true;
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] bytes = RecordWire.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void fail(HttpExchange exchange, int status, String error, String detail)
            throws IOException {
        respond(exchange, status, Map.of("error", error, "detail", detail));
    }
}
