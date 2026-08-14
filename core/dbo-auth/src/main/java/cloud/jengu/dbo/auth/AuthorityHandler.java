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
                default -> respond(exchange, 404, "{\"error\":\"not_found\"}");
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
            case TenantAuthority.AuthorizeResult.LoginRequired ok -> {
                String form = "<!doctype html><html><body><form method=\"post\" action=\""
                        + basePath + "/authorize/login\">"
                        + hidden("client_id", q.get("client_id"))
                        + hidden("redirect_uri", q.get("redirect_uri"))
                        + hidden("state", q.getOrDefault("state", ""))
                        + hidden("code_challenge", q.getOrDefault("code_challenge", ""))
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
        switch (authority.completeLogin(form.get("client_id"), form.get("redirect_uri"),
                emptyToNull(form.get("code_challenge")), form.get("login"), form.get("password"))) {
            case TenantAuthority.LoginResult.Denied denied ->
                    respond(exchange, 401, "{\"error\":\"" + denied.error() + "\"}");
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

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
