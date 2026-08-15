package cloud.jengu.dbo.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deployment's identity hub (§16.2): one national authentication serves
 * every tenant authority. The hub federates to the upstream broker (eeID/
 * TARA — an ordinary OIDC provider), keeps a SESSION (a signed cookie
 * carrying only the verified national identifier and auth time), and issues
 * short-lived identity ASSERTIONS to tenant authorities — who verify them
 * against the hub's key and keep authorization entirely their own.
 *
 * <p>The hub's signing key and sessions are in-memory: a pod restart ends
 * every session (the next ceremony re-establishes them) — deliberate, keys
 * at rest belong to tenants, not deployment infra.
 */
public final class IdentityHub implements HttpHandler {

    /** The upstream broker: plain OIDC, discovered from its issuer. */
    public record Upstream(String issuer, String clientId, String clientSecret,
            String subjectStripPrefix) {}

    public static final String COOKIE = "dbo_hub";

    private final Map<String, Upstream> upstreams;
    private final String defaultBroker;
    private final String subjectSystem;
    private final String baseUrl;
    private final String basePath;
    private final long sessionTtlSeconds;
    private final KeyPair key;
    private final String kid;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
    private final Map<String, Map<String, Object>> discoveries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RSAPublicKey>> upstreamKeysByBroker = new ConcurrentHashMap<>();

    private record Pending(String callback, String tenantState, String broker, long expiresAt) {}

    /** §16.2 single-broker deployments (env-configured, zone-less). */
    public IdentityHub(Upstream upstream, String subjectSystem, String baseUrl,
            String basePath, long sessionTtlSeconds) {
        this(Map.of("default", upstream), "default", subjectSystem, baseUrl, basePath, sessionTtlSeconds);
    }

    /** §17.3: one hub per zone, N declared brokers, sessions accumulate. */
    public IdentityHub(Map<String, Upstream> upstreams, String defaultBroker, String subjectSystem,
            String baseUrl, String basePath, long sessionTtlSeconds) {
        this.upstreams = Map.copyOf(upstreams);
        this.defaultBroker = defaultBroker;
        this.subjectSystem = subjectSystem;
        this.baseUrl = baseUrl;
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.sessionTtlSeconds = sessionTtlSeconds;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.key = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("hub key generation failed", e);
        }
        this.kid = cloud.jengu.dbo.core.UuidV7.newId();
    }

    /** Tenant authorities verify assertions against this key (same deployment). */
    public RSAPublicKey assertionKey() {
        return (RSAPublicKey) key.getPublic();
    }

    public String issuer() {
        return baseUrl + basePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String relative = exchange.getRequestURI().getPath().substring(basePath.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            switch (relative) {
                case "authorize" -> authorize(exchange);
                case "callback" -> callback(exchange);
                case "jwks.json", ".well-known/jwks.json" -> respond(exchange, 200,
                        "{\"keys\":[" + Jwk.render(kid, assertionKey()) + "]}");
                default -> respond(exchange, 404, "{\"error\":\"not_found\"}");
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"error\":\"server_error\"}");
        } finally {
            exchange.close();
        }
    }

    /**
     * A tenant authority sends the browser here. §17.3 acceptance: a session
     * whose ceremonies satisfy the tenant's accepted set asserts immediately
     * — no broker, no fee; otherwise the REQUIRED broker's ceremony runs and
     * ACCUMULATES onto the session.
     */
    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> q = query(exchange);
        String callback = q.get("cb");
        String tenantState = q.getOrDefault("state", "");
        String requestedBroker = q.getOrDefault("broker", defaultBroker);
        java.util.Set<String> accepted = q.get("accepted") == null || q.get("accepted").isBlank()
                ? java.util.Set.of()
                : java.util.Set.of(q.get("accepted").split(","));
        if (callback == null || !upstreams.containsKey(requestedBroker)) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        Optional<Session> session = sessionOf(exchange);
        if (session.isPresent() && (accepted.isEmpty()
                || session.get().amr().stream().anyMatch(accepted::contains))) {
            redirect(exchange, callback + "?assertion="
                    + URLEncoder.encode(assertion(session.get(), callback), StandardCharsets.UTF_8)
                    + "&state=" + URLEncoder.encode(tenantState, StandardCharsets.UTF_8));
            return;
        }
        String ceremonyBroker = accepted.isEmpty() || accepted.contains(requestedBroker)
                ? requestedBroker : accepted.iterator().next();
        Upstream upstream = upstreams.get(ceremonyBroker);
        String nonce = cloud.jengu.dbo.core.UuidV7.newId();
        pending.put(nonce, new Pending(callback, tenantState, ceremonyBroker,
                System.currentTimeMillis() + 300_000));
        redirect(exchange, String.valueOf(discovery(ceremonyBroker).get("authorization_endpoint"))
                + "?response_type=code&scope=openid"
                + "&client_id=" + URLEncoder.encode(upstream.clientId(), StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(baseUrl + basePath + "/callback", StandardCharsets.UTF_8)
                + "&state=" + nonce + "&nonce=" + nonce);
    }

    /** Back from the broker: verify the id_token, establish the session, assert. */
    private void callback(HttpExchange exchange) throws IOException {
        Map<String, String> q = query(exchange);
        Pending request = q.get("state") != null ? pending.remove(q.get("state")) : null;
        if (request == null || request.expiresAt() < System.currentTimeMillis()
                || q.get("code") == null) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        Upstream upstream = upstreams.get(request.broker());
        String idToken = fetchIdToken(request.broker(), q.get("code"));
        Jws.Parts parts = Jws.parse(idToken);
        RSAPublicKey signer = upstreamKey(request.broker(), parts.kid());
        Object claims = Json.parse(parts.claimsJson());
        if (signer == null || !Jws.verify(parts, signer)
                || !upstream.clientId().equals(Json.strOpt(claims, "aud"))
                || Json.num(claims, "exp") < System.currentTimeMillis() / 1000) {
            respond(exchange, 401, "{\"error\":\"invalid_token\"}");
            return;
        }
        String subject = Json.str(claims, "sub");
        if (upstream.subjectStripPrefix() != null
                && subject.startsWith(upstream.subjectStripPrefix())) {
            subject = subject.substring(upstream.subjectStripPrefix().length());
        }
        // §17.3 accumulation: same person → the ceremony joins the session;
        // a DIFFERENT person on this browser replaces it
        java.util.List<String> amr = new java.util.ArrayList<>(java.util.List.of(request.broker()));
        Optional<Session> existing = sessionOf(exchange);
        if (existing.isPresent() && existing.get().value().equals(subject)
                && existing.get().system().equals(subjectSystem)) {
            existing.get().amr().stream().filter(a -> !amr.contains(a)).forEach(amr::add);
        }
        Session session = new Session(subjectSystem, subject, List.copyOf(amr),
                System.currentTimeMillis() / 1000,
                System.currentTimeMillis() / 1000 + sessionTtlSeconds);
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "="
                + sessionToken(session) + "; HttpOnly; Path=" + basePath + "; SameSite=Lax");
        redirect(exchange, request.callback() + "?assertion="
                + URLEncoder.encode(assertion(session, request.callback()), StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(request.tenantState(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------- sessions

    record Session(String system, String value, java.util.List<String> amr,
            long authTime, long expiresAt) {}

    private String sessionToken(Session session) {
        String claims = "{\"iss\":\"" + issuer() + "\",\"typ\":\"session\""
                + ",\"sys\":\"" + session.system() + "\",\"val\":\"" + session.value() + "\""
                + ",\"amr\":[" + session.amr().stream().map(a -> "\"" + a + "\"")
                        .reduce((a, b) -> a + "," + b).orElse("") + "]"
                + ",\"auth_time\":" + session.authTime() + ",\"exp\":" + session.expiresAt() + "}";
        return Jws.sign(kid, claims, key.getPrivate());
    }

    private Optional<Session> sessionOf(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String cookie : header.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && COOKIE.equals(pair[0])) {
                    try {
                        Jws.Parts parts = Jws.parse(pair[1]);
                        if (!Jws.verify(parts, assertionKey())) {
                            return Optional.empty();
                        }
                        Object claims = Json.parse(parts.claimsJson());
                        if (!"session".equals(Json.strOpt(claims, "typ"))
                                || Json.num(claims, "exp") < System.currentTimeMillis() / 1000) {
                            return Optional.empty();
                        }
                        return Optional.of(new Session(Json.str(claims, "sys"),
                                Json.str(claims, "val"), Json.strings(claims, "amr"),
                                Json.num(claims, "auth_time"), Json.num(claims, "exp")));
                    } catch (RuntimeException invalid) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The identity assertion: WHO this is — nothing more. 60s, audience-bound. */
    private String assertion(Session session, String callback) {
        String audience = callback.endsWith("/federated")
                ? callback.substring(0, callback.length() - "/federated".length())
                : callback;
        long now = System.currentTimeMillis() / 1000;
        String claims = "{\"iss\":\"" + issuer() + "\",\"aud\":\"" + audience + "\""
                + ",\"typ\":\"identity-assertion\""
                + ",\"sys\":\"" + session.system() + "\",\"val\":\"" + session.value() + "\""
                + ",\"auth_time\":" + session.authTime()
                + ",\"amr\":[" + session.amr().stream().map(a -> "\"" + a + "\"")
                        .reduce((a, b) -> a + "," + b).orElse("") + "]"
                + ",\"iat\":" + now + ",\"exp\":" + (now + 60) + "}";
        return Jws.sign(kid, claims, key.getPrivate());
    }

    // ------------------------------------------------------------- upstream

    private Map<String, Object> discovery(String broker) {
        return discoveries.computeIfAbsent(broker, b ->
                fetchJson(upstreams.get(b).issuer() + "/.well-known/openid-configuration"));
    }

    private String fetchIdToken(String broker, String code) {
        try {
            Upstream upstream = upstreams.get(broker);
            String form = "grant_type=authorization_code&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(baseUrl + basePath + "/callback", StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(upstream.clientId(), StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(upstream.clientSecret(), StandardCharsets.UTF_8);
            var response = http.send(java.net.http.HttpRequest.newBuilder(
                            URI.create(String.valueOf(discovery(broker).get("token_endpoint"))))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return Json.str(Json.parse(response.body()), "id_token");
        } catch (Exception e) {
            throw new IllegalStateException("broker token exchange failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private RSAPublicKey upstreamKey(String broker, String kidWanted) {
        Map<String, RSAPublicKey> cached = upstreamKeysByBroker.get(broker);
        if (cached != null && cached.containsKey(kidWanted)) {
            return cached.get(kidWanted);
        }
        Map<String, Object> jwks = fetchJson(String.valueOf(discovery(broker).get("jwks_uri")));
        Map<String, RSAPublicKey> keys = new java.util.HashMap<>();
        for (Object entry : (java.util.List<Object>) jwks.getOrDefault("keys", java.util.List.of())) {
            Map<String, Object> jwk = (Map<String, Object>) entry;
            if ("RSA".equals(jwk.get("kty"))) {
                keys.put(String.valueOf(jwk.get("kid")), Jwk.parse(Json.render(jwk)));
            }
        }
        upstreamKeysByBroker.put(broker, Map.copyOf(keys));
        return keys.get(kidWanted);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJson(String url) {
        try {
            var response = http.send(java.net.http.HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return (Map<String, Object>) Json.parse(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("broker fetch failed: " + url, e);
        }
    }

    // ------------------------------------------------------------- plumbing

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new java.util.HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
