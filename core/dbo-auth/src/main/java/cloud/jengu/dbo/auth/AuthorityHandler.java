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
        if (!"client_credentials".equals(form.get("grant_type"))) {
            respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        if (clientId == null || clientSecret == null) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        switch (authority.token(clientId, clientSecret, form.get("scope"))) {
            case TenantAuthority.TokenResult.Issued issued -> respond(exchange, 200,
                    "{\"access_token\":\"" + issued.accessToken() + "\",\"token_type\":\"Bearer\""
                            + ",\"expires_in\":" + issued.expiresIn()
                            + ",\"scope\":\"" + issued.scope() + "\"}");
            case TenantAuthority.TokenResult.Rejected rejected -> respond(exchange,
                    "invalid_client".equals(rejected.error()) ? 401 : 400,
                    "{\"error\":\"" + rejected.error() + "\",\"error_description\":\""
                            + rejected.description() + "\"}");
        }
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
