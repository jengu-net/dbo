package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * One tenant's OIDC authority (§13): its issuer, its keys, its clients —
 * all regular records in the tenant's own identity store. Token issuance is
 * client_credentials only (services; humans stay platform-side); validation
 * is local against the tenant's own key set.
 */
public final class TenantAuthority {

    public static final long TOKEN_TTL_SECONDS = 600;

    private final ObjectStore store;
    private final String issuer;
    private final KeyProtector protector;
    private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();

    public TenantAuthority(ObjectStore identityStore, String issuer, KeyProtector protector) {
        this.store = identityStore;
        this.issuer = issuer;
        this.protector = protector;
    }

    public String issuer() {
        return issuer;
    }

    // ------------------------------------------------------------ keys

    /** Idempotent: ensures the tenant has an active signing key. */
    public void ensureSigningKey() {
        if (activeKey().isEmpty()) {
            generateKey();
        }
    }

    /** Rotation: the old key stays published (verifies) until retired keys are removed. */
    public String rotateSigningKey() {
        Optional<StoredObject> active = activeKey();
        String newKid = generateKey();
        active.ifPresent(old -> {
            String payload = new String(old.payload(), StandardCharsets.UTF_8)
                    .replace("\"status\":\"active\"", "\"status\":\"retired\"");
            store.put(PutRequest.update("SigningKey", old.id(), old.versionId(),
                    payload.getBytes(StandardCharsets.UTF_8)));
        });
        return newKid;
    }

    private String generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String kid = UuidV7.newId();
            String payload = "{\"kid\":\"" + kid + "\",\"alg\":\"RS256\""
                    + ",\"publicJwk\":" + Jwk.render(kid, (RSAPublicKey) pair.getPublic())
                    + ",\"privateEnc\":\"" + Base64.getEncoder().encodeToString(
                            protector.wrap(pair.getPrivate().getEncoded())) + "\""
                    + ",\"status\":\"active\"}";
            store.putIfAbsent(IdentityRef.identifier(IdentityModel.KID_SYSTEM, kid),
                    PutRequest.create("SigningKey", payload.getBytes(StandardCharsets.UTF_8)));
            return kid;
        } catch (Exception e) {
            throw new IllegalStateException("signing-key generation failed", e);
        }
    }

    private Optional<StoredObject> activeKey() {
        return allKeys().stream()
                .filter(k -> "active".equals(field(k, "status")))
                .findFirst();
    }

    private List<StoredObject> allKeys() {
        return store.select(cloud.jengu.dbo.core.api.Criteria.of("SigningKey"));
    }

    private static String field(StoredObject object, String name) {
        return Json.str(Json.parse(new String(object.payload(), StandardCharsets.UTF_8)), name);
    }

    // ------------------------------------------------------------ clients

    /**
     * Idempotent bootstrap: creates the client if absent; if present with a
     * different secret, re-hashes to the provided one — the provisioner's
     * secret custody (the k8s Secret) is authoritative.
     */
    public void ensureClient(String clientId, String secret, List<String> scopes) {
        scopes.forEach(scope -> {
            if (!Scopes.isValid(scope)) {
                throw new IllegalArgumentException("invalid scope: " + scope);
            }
        });
        Optional<StoredObject> existing = findClient(clientId);
        if (existing.isPresent() && SecretHash.verify(secret, field(existing.get(), "secretHash"))) {
            return;
        }
        String payload = "{\"clientId\":\"" + clientId + "\""
                + ",\"secretHash\":\"" + SecretHash.hash(secret) + "\""
                + ",\"scopes\":[" + scopes.stream().map(s -> "\"" + s + "\"")
                        .collect(Collectors.joining(",")) + "]"
                + ",\"status\":\"active\"}";
        if (existing.isPresent()) {
            store.put(PutRequest.update("ClientApplication", existing.get().id(),
                    existing.get().versionId(), payload.getBytes(StandardCharsets.UTF_8)));
        } else {
            store.putIfAbsent(IdentityRef.identifier(IdentityModel.CLIENT_ID_SYSTEM, clientId),
                    PutRequest.create("ClientApplication", payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private Optional<StoredObject> findClient(String clientId) {
        return store.getByIdentifier("ClientApplication",
                List.of(new Identifier(IdentityModel.CLIENT_ID_SYSTEM, clientId))).stream().findFirst();
    }

    // ------------------------------------------------------------ token issuance

    public sealed interface TokenResult {
        record Issued(String accessToken, long expiresIn, String scope) implements TokenResult {}
        record Rejected(String error, String description) implements TokenResult {}
    }

    public TokenResult token(String clientId, String clientSecret, String requestedScope) {
        Optional<StoredObject> client = findClient(clientId);
        if (client.isEmpty()
                || !"active".equals(field(client.get(), "status"))
                || !SecretHash.verify(clientSecret, field(client.get(), "secretHash"))) {
            return new TokenResult.Rejected("invalid_client", "client authentication failed");
        }
        List<String> granted = Json.strings(
                Json.parse(new String(client.get().payload(), StandardCharsets.UTF_8)), "scopes");
        List<String> scopes = requestedScope == null || requestedScope.isBlank()
                ? granted
                : List.of(requestedScope.trim().split("\\s+"));
        if (!granted.containsAll(scopes)) {
            return new TokenResult.Rejected("invalid_scope", "scope exceeds the client's grants");
        }
        StoredObject key = activeKey().orElseThrow(() -> new IllegalStateException("no active signing key"));
        long now = System.currentTimeMillis() / 1000;
        String scope = String.join(" ", scopes);
        String claims = "{\"iss\":\"" + issuer + "\",\"sub\":\"" + clientId + "\""
                + ",\"aud\":\"" + issuer + "\",\"client_id\":\"" + clientId + "\""
                + ",\"scope\":\"" + scope + "\""
                + ",\"jti\":\"" + UuidV7.newId() + "\""
                + ",\"iat\":" + now + ",\"exp\":" + (now + TOKEN_TTL_SECONDS) + "}";
        return new TokenResult.Issued(
                Jws.sign(field(key, "kid"), claims, privateKey(key)), TOKEN_TTL_SECONDS, scope);
    }

    private PrivateKey privateKey(StoredObject key) {
        try {
            byte[] pkcs8 = protector.unwrap(Base64.getDecoder().decode(field(key, "privateEnc")));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalStateException("signing key unusable", e);
        }
    }

    // ------------------------------------------------------------ validation

    public record AuthContext(String clientId, List<String> scopes) {}

    /** Local validation (§13.5): the tenant's own cached keys, refresh on unknown kid. */
    public Optional<AuthContext> validate(String token) {
        try {
            Jws.Parts parts = Jws.parse(token);
            RSAPublicKey key = keyCache.get(parts.kid());
            if (key == null) {
                refreshKeyCache();
                key = keyCache.get(parts.kid());
            }
            if (key == null || !Jws.verify(parts, key)) {
                return Optional.empty();
            }
            Object claims = Json.parse(parts.claimsJson());
            if (!issuer.equals(Json.str(claims, "iss"))) {
                return Optional.empty();
            }
            if (Json.num(claims, "exp") < System.currentTimeMillis() / 1000) {
                return Optional.empty();
            }
            return Optional.of(new AuthContext(Json.str(claims, "sub"),
                    List.of(Json.str(claims, "scope").split(" "))));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private void refreshKeyCache() {
        for (StoredObject key : allKeys()) {
            String status = field(key, "status");
            if ("active".equals(status) || "retired".equals(status)) {
                Object payload = Json.parse(new String(key.payload(), StandardCharsets.UTF_8));
                keyCache.put(field(key, "kid"),
                        Jwk.parse(Json.render(((Map<?, ?>) payload).get("publicJwk"))));
            }
        }
    }

    // ------------------------------------------------------------ documents

    public String discoveryJson() {
        return "{\"issuer\":\"" + issuer + "\""
                + ",\"token_endpoint\":\"" + issuer + "/token\""
                + ",\"jwks_uri\":\"" + issuer + "/.well-known/jwks.json\""
                + ",\"grant_types_supported\":[\"client_credentials\"]"
                + ",\"token_endpoint_auth_methods_supported\":[\"client_secret_post\",\"client_secret_basic\"]"
                + ",\"response_types_supported\":[\"token\"]}";
    }

    public String jwksJson() {
        String keys = allKeys().stream()
                .filter(k -> {
                    String status = field(k, "status");
                    return "active".equals(status) || "retired".equals(status);
                })
                .map(k -> Json.render(((Map<?, ?>) Json.parse(
                        new String(k.payload(), StandardCharsets.UTF_8))).get("publicJwk")))
                .collect(Collectors.joining(","));
        return "{\"keys\":[" + keys + "]}";
    }
}
