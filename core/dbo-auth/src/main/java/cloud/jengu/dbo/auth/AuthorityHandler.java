package cloud.jengu.dbo.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * The authority's HTTP face at {@code /t/<code>/oidc}: discovery, JWKS, and
 * the client_credentials token endpoint (RFC 6749 §4.4; client auth via
 * form fields or HTTP Basic). Rides the same shared JDK server as the store
 * surface.
 */
public final class AuthorityHandler implements HttpHandler {

    private final TenantAuthority authority;
    private final String basePath;

    public AuthorityHandler(TenantAuthority authority, String basePath) {
        this.authority = authority;
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath().substring(basePath.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            switch (relative) {
                case ".well-known/openid-configuration" -> respond(exchange, 200, authority.discoveryJson());
                case ".well-known/jwks.json" -> respond(exchange, 200, authority.jwksJson());
                case "token" -> token(exchange);
                case "authorize" -> authorize(exchange);
                case "authorize/login" -> authorizeLogin(exchange);
                case "delegation" -> delegation(exchange);
                case "federated" -> federated(exchange);
                case "admin/role-grants" -> adminRoleGrants(exchange);
                case "credentials" -> credentials(exchange);
                case "admin/credentials" -> adminCredentials(exchange);
                case "admin/edge-factors" -> adminEdgeFactors(exchange);
                case "admin/secret-grants" -> adminSecretGrants(exchange);
                case "admin/clients" -> adminClients(exchange);
                case "admin/signing-keys" -> adminSigningKeys(exchange);
                case "secret-grants/redeem" -> redeemSecretGrant(exchange);
                default -> {
                    if (relative.startsWith("delegation/") && "DELETE".equals(exchange.getRequestMethod())) {
                        endDelegation(exchange, relative.substring("delegation/".length()));
                    } else {
                        respond(exchange, 404, "{\"error\":\"not_found\"}");
                    }
                }
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"error\":\"server_error\"}");
        } finally {
            exchange.close();
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String clientId = form.get("client_id");
        String clientSecret = form.get("client_secret");
        String basic = exchange.getRequestHeaders().getFirst("Authorization");
        if (basic != null && basic.startsWith("Basic ")) {
            String[] creds = new String(Base64.getDecoder().decode(basic.substring(6)),
                    StandardCharsets.UTF_8).split(":", 2);
            if (creds.length == 2) {
                clientId = URLDecoder.decode(creds[0], StandardCharsets.UTF_8);
                clientSecret = URLDecoder.decode(creds[1], StandardCharsets.UTF_8);
            }
        }
        TenantAuthority.TokenResult result = switch (String.valueOf(form.get("grant_type"))) {
            case "client_credentials" -> clientId == null || clientSecret == null
                    ? new TenantAuthority.TokenResult.Rejected("invalid_client", "client authentication required")
                    : authority.token(clientId, clientSecret, form.get("scope"),
                            form.get("purpose_of_use"));
            case "authorization_code" -> authority.exchangeCode(form.get("code"),
                    form.get("redirect_uri"), clientId, clientSecret, form.get("code_verifier"));
            case "refresh_token" -> authority.refresh(form.get("refresh_token"));
            case "urn:ietf:params:oauth:grant-type:token-exchange" ->
                    form.get("delegation_id") != null
                            ? authority.exchangeDelegation(form.get("delegation_id"),
                                    clientId, clientSecret, form.get("scope"),
                                    form.get("purpose_of_use"))
                            : authority.exchangeToken(form.get("subject_token"),
                                    clientId, clientSecret, form.get("scope"),
                                    form.get("purpose_of_use"));
            default -> new TenantAuthority.TokenResult.Rejected("unsupported_grant_type",
                    "unknown grant type");
        };
        respondToken(exchange, result);
    }

    private static void respondToken(HttpExchange exchange, TenantAuthority.TokenResult result)
            throws IOException {
        switch (result) {
            case TenantAuthority.TokenResult.Issued issued -> respond(exchange, 200,
                    "{\"access_token\":\"" + issued.accessToken() + "\",\"token_type\":\"Bearer\""
                            + ",\"expires_in\":" + issued.expiresIn()
                            + ",\"scope\":\"" + issued.scope() + "\"}");
            case TenantAuthority.TokenResult.IssuedHuman issued -> respond(exchange, 200,
                    "{\"access_token\":\"" + issued.accessToken() + "\",\"token_type\":\"Bearer\""
                            + ",\"expires_in\":" + issued.expiresIn()
                            + ",\"scope\":\"" + issued.scope() + "\""
                            + (issued.idToken() == null ? ""
                                    : ",\"id_token\":\"" + issued.idToken() + "\"")
                            + ",\"refresh_token\":\"" + issued.refreshToken() + "\"}");
            case TenantAuthority.TokenResult.Rejected rejected -> respond(exchange,
                    "invalid_client".equals(rejected.error()) ? 401 : 400,
                    "{\"error\":\"" + rejected.error() + "\",\"error_description\":\""
                            + rejected.description() + "\"}");
        }
    }

    /** GET /authorize: validate the front-channel request, serve the login form. */
    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> q = parseForm(exchange.getRequestURI().getRawQuery() == null
                ? "" : exchange.getRequestURI().getRawQuery());
        if (!"code".equals(q.get("response_type"))) {
            respond(exchange, 400, "{\"error\":\"unsupported_response_type\"}");
            return;
        }
        switch (authority.beginAuthorization(q.get("client_id"), q.get("redirect_uri"),
                q.get("code_challenge"))) {
            case TenantAuthority.AuthorizeResult.Rejected rejected ->
                    // NEVER redirect on an invalid client/target
                    respond(exchange, 400, "{\"error\":\"" + rejected.error() + "\"}");
            case TenantAuthority.AuthorizeResult.LoginRequired ok when authority.federation() != null -> {
                // §16.2: humans authenticate at the deployment's hub
                exchange.getResponseHeaders().set("Location", authority.beginFederated(
                        q.get("client_id"), q.get("redirect_uri"),
                        q.get("code_challenge"), q.getOrDefault("state", ""),
                        q.getOrDefault("nonce", "")));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(302, -1);
            }
            case TenantAuthority.AuthorizeResult.LoginRequired ok -> {
                String form = "<!doctype html><html><body><form method=\"post\" action=\""
                        + basePath + "/authorize/login\">"
                        + hidden("client_id", q.get("client_id"))
                        + hidden("redirect_uri", q.get("redirect_uri"))
                        + hidden("state", q.getOrDefault("state", ""))
                        + hidden("code_challenge", q.getOrDefault("code_challenge", ""))
                        + hidden("nonce", q.getOrDefault("nonce", ""))
                        + "<input name=\"login\" autocomplete=\"username\">"
                        + "<input name=\"password\" type=\"password\" autocomplete=\"current-password\">"
                        + "<button type=\"submit\">Sign in</button></form></body></html>";
                byte[] body = form.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\""
                + value.replace("\"", "&quot;") + "\">";
    }

    /** POST /authorize/login: authenticate via the seam, redirect with the code. */
    private void authorizeLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        // Validate the CLIENT half first: an unknown client or unregistered
        // redirect stays a hard 401 (never redirect an invalid target) —
        // while a credential failure for a VALID client is the standard
        // OAuth error redirect (RFC 6749 §4.1.2.1), so the RP's own login
        // page shows the failure instead of a bare JSON body.
        if (authority.beginAuthorization(form.get("client_id"), form.get("redirect_uri"),
                emptyToNull(form.get("code_challenge")))
                instanceof TenantAuthority.AuthorizeResult.Rejected rejected) {
            respond(exchange, 401, "{\"error\":\"" + rejected.error() + "\"}");
            return;
        }
        switch (authority.completeLogin(form.get("client_id"), form.get("redirect_uri"),
                emptyToNull(form.get("code_challenge")), form.getOrDefault("nonce", ""),
                form.get("login"), form.get("password"))) {
            case TenantAuthority.LoginResult.Denied denied -> {
                String location = form.get("redirect_uri")
                        + (form.get("redirect_uri").contains("?") ? "&" : "?")
                        + "error=" + denied.error()
                        + (form.getOrDefault("state", "").isEmpty() ? ""
                                : "&state=" + java.net.URLEncoder.encode(form.get("state"), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Location", location);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(302, -1);
            }
            case TenantAuthority.LoginResult.Redirect redirect -> {
                String location = form.get("redirect_uri")
                        + (form.get("redirect_uri").contains("?") ? "&" : "?")
                        + "code=" + redirect.code()
                        + (form.getOrDefault("state", "").isEmpty() ? ""
                                : "&state=" + java.net.URLEncoder.encode(form.get("state"), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Location", location);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(302, -1);
            }
        }
    }

    /** The hub's assertion returns here; the browser continues to the RP. */
    private void federated(HttpExchange exchange) throws IOException {
        Map<String, String> q = parseForm(exchange.getRequestURI().getRawQuery() == null
                ? "" : exchange.getRequestURI().getRawQuery());
        switch (authority.completeFederated(q.get("assertion"), q.get("state"))) {
            case TenantAuthority.FederatedOutcome.Invalid ignored ->
                    respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            case TenantAuthority.FederatedOutcome.Denied denied -> {
                String location = denied.redirectUri()
                        + (denied.redirectUri().contains("?") ? "&" : "?")
                        + "error=" + denied.error()
                        + (denied.rpState().isEmpty() ? "" : "&state="
                                + java.net.URLEncoder.encode(denied.rpState(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Location", location);
                exchange.sendResponseHeaders(302, -1);
            }
            case TenantAuthority.FederatedOutcome.Success success -> {
                String location = success.redirectUri()
                        + (success.redirectUri().contains("?") ? "&" : "?")
                        + "code=" + success.code()
                        + (success.rpState().isEmpty() ? "" : "&state="
                                + java.net.URLEncoder.encode(success.rpState(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Location", location);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(302, -1);
            }
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * §16.4 durable delegation, created while the human's token is live:
     * POST {client_id, process_ref?, scope, valid_until} with the human's
     * Bearer token → {delegation_id}.
     */
    private void delegation(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return;
        }
        String bearer = bearerOf(exchange);
        Map<String, String> form = parseForm(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (bearer == null || form.get("client_id") == null
                || form.get("scope") == null || form.get("valid_until") == null) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        var created = authority.createDelegation(bearer, form.get("client_id"),
                form.get("process_ref"),
                java.util.List.of(form.get("scope").trim().split("\\s+")),
                Long.parseLong(form.get("valid_until")));
        if (created.isEmpty()) {
            respond(exchange, 403, "{\"error\":\"access_denied\"}");
        } else {
            respond(exchange, 201, "{\"delegation_id\":\"" + created.get() + "\"}");
        }
    }

    /**
     * A subject changes their own password (§13.6).
     *
     * <p>The one credential ceremony that is self-service. Recovery is not:
     * it needs a channel this authority does not have, and acquiring one would
     * put delivery inside the trust root — so a subject who cannot sign in is
     * recovered by provisioning or by an operator, deliberately.
     *
     * <p>One answer for every refusal, and the same work behind it. A caller
     * learns whether their own change succeeded and nothing about anybody
     * else's login (REQ-DBO-AUTH-NO-SUBJECT-ENUMERATION).
     */
    private void credentials(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        boolean changed = authority.changeOwnSecret(bearerOf(exchange), form.get("login"),
                form.get("current_secret"), form.get("new_secret"));
        if (changed) {
            respond(exchange, 204, "");
        } else {
            respond(exchange, 403, "{\"error\":\"access_denied\"}");
        }
    }

    /**
     * Mints a one-time grant for a subject to set their own first secret
     *.
     *
     * <p>Privileged, because asking for one is an administrative act. It
     * answers identically whether or not the subject exists, whether or not
     * they could hold a password, and whether or not their credential was
     * retired this morning — the caller learns nothing by asking, which is the
     * property {@code REQ-DBO-AUTH-NO-SUBJECT-ENUMERATION} exists to keep.
     *
     * <p><b>The authority does not deliver it.</b> What comes back goes to the
     * consumer, which owns the address and the mail; nothing about delivery
     * enters the trust root.
     */
    /**
     * Register a machine credential, or a relying party.
     *
     * <p>An appliance authenticates with a credential of its own so that one
     * can be revoked without touching the others, and it has no human and no
     * interactive step — the enrolment is headless by design. That is a
     * different thing from {@code admin/secret-grants}, which exists so a
     * PERSON can set their own secret.
     *
     * <p><b>The caller's secret is authoritative</b>, and nothing is generated
     * here. Whoever approves the appliance generates the secret, records it,
     * and hands it to the appliance; a store that minted its own would be a
     * second custody path for one credential, and the two would disagree the
     * first time an enrolment was retried.
     *
     * <p>Idempotent for the same reason: re-approving an appliance after a
     * failed enrolment is the ordinary case, not an error, and it must not need
     * a different call from the first attempt.
     */
    private void adminClients(HttpExchange exchange) throws IOException {
        if (!systemWrite(exchange)) {
            return;
        }
        Object body = Json.parse(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String clientId = Json.strOpt(body, "client_id");
        if (clientId == null || clientId.isBlank()) {
            respond(exchange, 400, "{\"error\":\"invalid_request\","
                    + "\"error_description\":\"client_id is required\"}");
            return;
        }
        String secret = Json.strOpt(body, "secret");
        List<String> scopes = Json.strings(body, "scope");
        String clientType = Json.strOpt(body, "client_type");
        List<String> redirectUris = Json.strings(body, "redirect_uris");
        // The public half of a keypair the participant generated before it
        // came here, offered as a JWK. What payload data keys will be wrapped
        // to; the private half is the one thing this call must never see,
        // and a JWK carrying it is refused rather than stripped.
        Object offered = ((java.util.Map<?, ?>) body).get("public_key");
        Object offeredSigning = ((java.util.Map<?, ?>) body).get("signing_key");
        cloud.jengu.dbo.core.api.seal.ParticipantKey participantKey = null;
        cloud.jengu.dbo.core.api.seal.SigningKey signingKey = null;
        try {
            if (offered != null) {
                participantKey = cloud.jengu.dbo.core.api.seal.ParticipantKey.parse(
                        offered instanceof String s ? s : Json.render(offered));
            }
            if (offeredSigning != null) {
                signingKey = cloud.jengu.dbo.core.api.seal.SigningKey.parse(
                        offeredSigning instanceof String s ? s : Json.render(offeredSigning));
            }
        } catch (IllegalArgumentException refused) {
            respond(exchange, 400, "{\"error\":\"invalid_request\",\"error_description\":\""
                    + String.valueOf(refused.getMessage()).replace("\"", "'") + "\"}");
            return;
        }
        try {
            if (clientType == null && redirectUris.isEmpty()) {
                // The machine shape: a secret it holds and the scopes it may
                // ask for. A confidential client with no redirect anywhere,
                // because nothing about it is interactive.
                if (secret == null || secret.isBlank()) {
                    respond(exchange, 400, "{\"error\":\"invalid_request\","
                            + "\"error_description\":\"secret is required for a machine "
                            + "credential — this store does not mint one\"}");
                    return;
                }
                authority.ensureClient(clientId, secret, scopes, participantKey, signingKey);
            } else if (participantKey != null || signingKey != null) {
                // A relying party is a place people log in, not a thing work
                // is sealed to; a key on it would be recorded and wrapped to
                // by nothing.
                respond(exchange, 400, "{\"error\":\"invalid_request\","
                        + "\"error_description\":\"a public key is offered by a machine "
                        + "credential, not a relying party\"}");
                return;
            } else {
                authority.ensureClient(clientId, secret, scopes, clientType, redirectUris);
            }
        } catch (IllegalArgumentException refused) {
            // An invalid scope is the caller's mistake and is named, rather
            // than registering a client that can never ask for anything.
            respond(exchange, 400, "{\"error\":\"invalid_scope\",\"error_description\":\""
                    + String.valueOf(refused.getMessage()).replace("\"", "'") + "\"}");
            return;
        }
        // The kid is the thumbprint of what was offered: the version the
        // store will wrap to, so the holder can tell a wrap made to this key
        // from one made to a key it has since replaced.
        respond(exchange, 200, "{\"client_id\":\"" + clientId + "\""
                + (participantKey != null ? ",\"kid\":\"" + participantKey.kid() + "\"" : "")
                + (signingKey != null ? ",\"signing_kid\":\"" + signingKey.kid() + "\"" : "")
                + "}");
    }

    /**
     * Rotate this tenant's signing key.
     *
     * <p>A route rather than only a schedule, because the case that cannot
     * wait for one is a key somebody believes is compromised. The old key
     * keeps verifying until it is pruned, so nothing signed a moment ago
     * breaks; what changes is what the next token is signed with.
     *
     * <p>The write goes through the tenant's own store, so the rotation lands
     * in the trail like any other change — which is where a change to how
     * every token in the tenant is signed belongs.
     */
    private void adminSigningKeys(HttpExchange exchange) throws IOException {
        if (!systemWrite(exchange)) {
            return;
        }
        String kid = authority.rotateSigningKey();
        respond(exchange, 200, "{\"kid\":\"" + kid + "\"}");
    }

    private void adminSecretGrants(HttpExchange exchange) throws IOException {
        if (!systemWrite(exchange)) {
            return;
        }
        Object body = Json.parse(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String login = Json.strOpt(body, "login");
        long minutes = 60;
        String requested = Json.strOpt(body, "minutes");
        if (requested != null) {
            try {
                minutes = Math.max(1, Math.min(24 * 60, Long.parseLong(requested)));
            } catch (NumberFormatException notANumber) {
                // The default stands. A lifetime somebody mistyped is not a
                // reason to refuse an onboarding.
                minutes = 60;
            }
        }
        String grant = authority.mintSecretGrant(login, java.time.Duration.ofMinutes(minutes));
        respond(exchange, 200, "{\"grant\":\"" + grant + "\"}");
    }

    /**
     * The holder presents the grant and the secret they chose.
     *
     * <p>Unauthenticated by design: whoever holds the grant is who this is for,
     * and requiring a token would mean the person needed a credential in order
     * to set their first one. One answer for every refusal — a grant nobody
     * minted, one already spent, a subject who federates — because the shapes
     * of the refusals are what an enumeration reads.
     */
    private void redeemSecretGrant(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (authority.redeemSecretGrant(form.get("grant"), form.get("new_secret"))) {
            respond(exchange, 204, "");
        } else {
            respond(exchange, 403, "{\"error\":\"access_denied\"}");
        }
    }

    /**
     * §16.3 provisioning surface: the tenant-bootstrap M2M client writes
     * RoleGrant defaults (from the git config repo) and dev LocalCredentials
     * over the SAME authenticated REST path in every deployment shape —
     * embedded local-dev and the k8s dbo-server alike.
     * Guarded by a system-plane write scope of this authority's own tokens.
     */
    private void adminRoleGrants(HttpExchange exchange) throws IOException {
        if (!systemWrite(exchange)) {
            return;
        }
        Object body = Json.parse(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String role = Json.strOpt(body, "role");
        java.util.List<String> scopes = Json.strings(body, "scopes");
        if (role == null || role.isBlank() || scopes.isEmpty()) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        authority.ensureRoleGrant(role, scopes);
        respond(exchange, 200, "{\"status\":\"ensured\",\"role\":\"" + role + "\"}");
    }

    /**
     * The PIN verifiers a bench needs to authenticate people offline.
     *
     * <p>A deliberate credential-distribution surface. An edge cannot ask
     * anybody at the moment somebody presents a PIN, so it holds verifiers in
     * advance — the cost of working in a basement. Naming the endpoint after
     * what it does is the point: this used to happen as a side effect of
     * syncing clinical records, where nobody chose it.
     *
     * <p>Hashes only. What leaves here checks a PIN and cannot produce one.
     */
    private void adminEdgeFactors(HttpExchange exchange) throws IOException {
        if (!systemWrite(exchange)) {
            return;
        }
        StringBuilder json = new StringBuilder("{\"factors\":[");
        boolean first = true;
        for (java.util.Map.Entry<String, String> holder : authority.factorsFor("pin")) {
            json.append(first ? "" : ",")
                    .append("{\"login\":").append(Json.quote(holder.getKey()))
                    .append(",\"pinHash\":").append(Json.quote(holder.getValue())).append('}');
            first = false;
        }
        respond(exchange, 200, json.append("]}").toString());
    }

    private void adminCredentials(HttpExchange exchange) throws IOException {
        if (!systemWrite(exchange)) {
            return;
        }
        Object body = Json.parse(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String login = Json.strOpt(body, "login");
        String secret = Json.strOpt(body, "secret");
        // The credential binds to the person, not to a capacity they act in.
        // No fallback to the old field: a credential
        // pointing at a practitioner id would authenticate somebody to a
        // subject that grants nothing, and the failure would read as a
        // permissions problem rather than a wiring one.
        String personId = Json.strOpt(body, "personId");
        String edgePin = Json.strOpt(body, "edgePin");
        String status = Json.strOpt(body, "status");
        // Retirement is an operator act, and the same act recovery uses in the
        // other direction (§13.6): a deactivated subject's credential is
        // retired rather than deleted, because history and audit need the
        // record and a login that vanishes cannot be told from one that never
        // existed.
        if ("retired".equals(status) && login != null) {
            authority.retireCredential(login);
            // 204 whether or not that login existed: an operator acting on a
            // name they already hold learns nothing they did not bring.
            respond(exchange, 204, "");
            return;
        }
        if (login == null || (secret == null && edgePin == null)) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        // Setting a PIN is not creating a login, so it does not need the fields
        // that creating one does — and it must not silently create a
        // credential nobody has a password for.
        if (secret != null) {
            if (personId == null) {
                respond(exchange, 400, "{\"error\":\"invalid_request\"}");
                return;
            }
            authority.ensureLocalCredential(login, secret, personId);
        }
        if (edgePin != null) {
            try {
                authority.setFactor(login, "pin", edgePin);
            } catch (IllegalArgumentException unknownLogin) {
                respond(exchange, 404, "{\"error\":\"unknown_login\"}");
                return;
            }
        }
        respond(exchange, 200, "{\"status\":\"ensured\",\"login\":\"" + login + "\"}");
    }

    private boolean systemWrite(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return false;
        }
        String bearer = bearerOf(exchange);
        var context = bearer == null ? java.util.Optional.<TenantAuthority.AuthContext>empty()
                : authority.validate(bearer);
        // provisioning is system-plane ONLY: a human's user/*.write must
        // never reach it, so the check is explicit, not Scopes.allows
        boolean systemPlane = context.isPresent()
                && (context.get().scopes().contains("system/*.write")
                        || context.get().scopes().contains("system/Identity.write"));
        if (!systemPlane) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            respond(exchange, context.isEmpty() ? 401 : 403, "{\"error\":\"access_denied\"}");
            return false;
        }
        return true;
    }

    private void endDelegation(HttpExchange exchange, String delegationId) throws IOException {
        String bearer = bearerOf(exchange);
        if (bearer == null || !authority.endDelegation(delegationId, bearer)) {
            respond(exchange, 403, "{\"error\":\"access_denied\"}");
        } else {
            respond(exchange, 200, "{\"status\":\"ended\"}");
        }
    }

    private static String bearerOf(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
