package cloud.jengu.dbo.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
                case "admin/credentials" -> adminCredentials(exchange);
                case "admin/edge-factors" -> adminEdgeFactors(exchange);
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
                    : authority.token(clientId, clientSecret, form.get("scope"));
            case "authorization_code" -> authority.exchangeCode(form.get("code"),
                    form.get("redirect_uri"), clientId, clientSecret, form.get("code_verifier"));
            case "refresh_token" -> authority.refresh(form.get("refresh_token"));
            case "urn:ietf:params:oauth:grant-type:token-exchange" ->
                    form.get("delegation_id") != null
                            ? authority.exchangeDelegation(form.get("delegation_id"),
                                    clientId, clientSecret, form.get("scope"))
                            : authority.exchangeToken(form.get("subject_token"),
                                    clientId, clientSecret, form.get("scope"));
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
     * §16.3 provisioning surface: the tenant-bootstrap M2M client writes
     * RoleGrant defaults (from the git config repo) and dev LocalCredentials
     * over the SAME authenticated REST path in every deployment shape —
     * embedded local-dev and the k8s dbo-server alike (jengu-platform#847).
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
        // The credential binds to the person, not to a capacity they act in
        // (jengu-platform#879). No fallback to the old field: a credential
        // pointing at a practitioner id would authenticate somebody to a
        // subject that grants nothing, and the failure would read as a
        // permissions problem rather than a wiring one.
        String personId = Json.strOpt(body, "personId");
        String edgePin = Json.strOpt(body, "edgePin");
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
