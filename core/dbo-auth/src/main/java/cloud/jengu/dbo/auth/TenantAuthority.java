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
    /** §16: the tenant's main store — subjects and grants are ITS records. */
    private volatile ObjectStore subjectStore;
    private volatile HumanAuthenticator humanAuthenticator;
    private final Map<String, PendingAuthorization> pendingCodes = new ConcurrentHashMap<>();

    public TenantAuthority(ObjectStore identityStore, String issuer, KeyProtector protector) {
        this.store = identityStore;
        this.issuer = issuer;
        this.protector = protector;
    }

    public String issuer() {
        return issuer;
    }

    /** §16.1: subjects and grants are the tenant's own records. */
    public void attachSubjects(ObjectStore mainStore) {
        this.subjectStore = mainStore;
        this.humanAuthenticator = new LocalCredentialAuthenticator();
    }

    /** §16.2: swap the dev-local fallback for a federated broker. */
    public void humanAuthenticator(HumanAuthenticator authenticator) {
        this.humanAuthenticator = authenticator;
    }

    /** §16.2 federated mode: humans authenticate at the deployment's hub. */
    /** §17: broker = the tenant's contracted choice; accepted = its policy. */
    public record Federation(String hubAuthorizeUrl,
            java.util.function.Supplier<java.security.interfaces.RSAPublicKey> hubKey,
            String hubIssuer, String broker, List<String> acceptedBrokers) {
        public Federation(String hubAuthorizeUrl,
                java.util.function.Supplier<java.security.interfaces.RSAPublicKey> hubKey,
                String hubIssuer) {
            this(hubAuthorizeUrl, hubKey, hubIssuer, null, List.of());
        }
    }

    private volatile Federation federation;
    private final Map<String, PendingFrontChannel> pendingFederated = new ConcurrentHashMap<>();

    record PendingFrontChannel(String clientId, String redirectUri, String codeChallenge,
            String nonce,
            String rpState, long expiresAt) {}

    public void federation(Federation federation) {
        this.federation = federation;
    }

    public Federation federation() {
        return federation;
    }

    /** Front-channel start under federation: park the RP request, return the hub redirect. */
    public String beginFederated(String clientId, String redirectUri, String codeChallenge,
            String rpState, String nonce) {
        String stateId = UuidV7.newId();
        pendingFederated.put(stateId, new PendingFrontChannel(clientId, redirectUri,
                codeChallenge, nonce == null ? "" : nonce,
                rpState, System.currentTimeMillis() + 300_000));
        return federation.hubAuthorizeUrl()
                + "?cb=" + java.net.URLEncoder.encode(issuer + "/federated", StandardCharsets.UTF_8)
                + "&state=" + stateId
                + (federation.broker() != null ? "&broker=" + federation.broker() : "")
                + (federation.acceptedBrokers().isEmpty() ? "" : "&accepted="
                        + String.join(",", federation.acceptedBrokers()));
    }

    public sealed interface FederatedOutcome {
        record Success(String redirectUri, String rpState, String code) implements FederatedOutcome {}
        record Denied(String redirectUri, String rpState, String error) implements FederatedOutcome {}
        record Invalid() implements FederatedOutcome {}
    }

    /** The hub's assertion arrives: verify, resolve, evaluate, mint the code. */
    public FederatedOutcome completeFederated(String assertionJwt, String stateId) {
        PendingFrontChannel parked = pendingFederated.remove(stateId);
        if (parked == null || parked.expiresAt() < System.currentTimeMillis()) {
            return new FederatedOutcome.Invalid();
        }
        Object claims;
        try {
            Jws.Parts parts = Jws.parse(assertionJwt);
            if (!Jws.verify(parts, federation.hubKey().get())) {
                return new FederatedOutcome.Denied(parked.redirectUri(), parked.rpState(), "access_denied");
            }
            claims = Json.parse(parts.claimsJson());
        } catch (RuntimeException invalid) {
            return new FederatedOutcome.Denied(parked.redirectUri(), parked.rpState(), "access_denied");
        }
        if (!"identity-assertion".equals(Json.strOpt(claims, "typ"))
                || !federation.hubIssuer().equals(Json.str(claims, "iss"))
                || !issuer.equals(Json.str(claims, "aud"))
                || Json.num(claims, "exp") < System.currentTimeMillis() / 1000) {
            return new FederatedOutcome.Denied(parked.redirectUri(), parked.rpState(), "access_denied");
        }
        // §17.3 defence in depth: the hub enforced acceptance; re-verify here
        if (!federation.acceptedBrokers().isEmpty()
                && Json.strings(claims, "amr").stream()
                        .noneMatch(federation.acceptedBrokers()::contains)) {
            return new FederatedOutcome.Denied(parked.redirectUri(), parked.rpState(), "access_denied");
        }
        Optional<String> practitioner = resolveByNationalId(
                Json.str(claims, "sys"), Json.str(claims, "val"));
        Grants grants = practitioner.isEmpty()
                ? new Grants(List.of(), List.of()) : evaluateGrants(practitioner.get());
        List<String> scopes = grants.scopes();
        if (scopes.isEmpty()) {
            // valid national identity, but THIS tenant grants nothing — the
            // §16.2 promise: authentication shared, authorization never
            return new FederatedOutcome.Denied(parked.redirectUri(), parked.rpState(), "access_denied");
        }
        String code = UuidV7.newId() + UuidV7.newId().substring(0, 8);
        pendingCodes.put(code, new PendingAuthorization(parked.clientId(), parked.redirectUri(),
                parked.codeChallenge(), practitioner.get(),
                String.join(" ", scopes), String.join(",", grants.roles()),
                parked.nonce(), System.currentTimeMillis() + 60_000));
        return new FederatedOutcome.Success(parked.redirectUri(), parked.rpState(), code);
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
        String payload = clientPayload(clientId, SecretHash.hash(secret), scopes,
                "confidential", List.of());
        if (existing.isPresent()) {
            store.put(PutRequest.update("ClientApplication", existing.get().id(),
                    existing.get().versionId(), payload.getBytes(StandardCharsets.UTF_8)));
        } else {
            store.putIfAbsent(IdentityRef.identifier(IdentityModel.CLIENT_ID_SYSTEM, clientId),
                    PutRequest.create("ClientApplication", payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** §16.5: a relying party or PKCE app — redirect targets validated at /authorize. */
    public void ensureClient(String clientId, String secretOrNull, List<String> scopes,
            String clientType, List<String> redirectUris) {
        scopes.forEach(scope -> {
            if (!Scopes.isValid(scope)) {
                throw new IllegalArgumentException("invalid scope: " + scope);
            }
        });
        String payload = clientPayload(clientId,
                secretOrNull != null ? SecretHash.hash(secretOrNull) : null,
                scopes, clientType, redirectUris);
        Optional<StoredObject> existing = findClient(clientId);
        if (existing.isPresent()) {
            store.put(PutRequest.update("ClientApplication", existing.get().id(),
                    existing.get().versionId(), payload.getBytes(StandardCharsets.UTF_8)));
        } else {
            store.putIfAbsent(IdentityRef.identifier(IdentityModel.CLIENT_ID_SYSTEM, clientId),
                    PutRequest.create("ClientApplication", payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String clientPayload(String clientId, String secretHash, List<String> scopes,
            String clientType, List<String> redirectUris) {
        return "{\"clientId\":\"" + clientId + "\""
                + (secretHash != null ? ",\"secretHash\":\"" + secretHash + "\"" : "")
                + ",\"scopes\":[" + scopes.stream().map(s -> "\"" + s + "\"")
                        .collect(Collectors.joining(",")) + "]"
                + ",\"clientType\":\"" + clientType + "\""
                + ",\"redirectUris\":[" + redirectUris.stream().map(u -> "\"" + u + "\"")
                        .collect(Collectors.joining(",")) + "]"
                + ",\"status\":\"active\"}";
    }

    // ------------------------------------------------------------ humans (§16)

    /** Dev/admin surface: the tenant-administered role→scope mapping. */
    public void ensureRoleGrant(String roleCode, List<String> scopes) {
        scopes.forEach(scope -> {
            if (!Scopes.isValid(scope)) {
                throw new IllegalArgumentException("invalid scope: " + scope);
            }
        });
        String payload = "{\"roleCode\":\"" + roleCode + "\""
                + ",\"scopes\":[" + scopes.stream().map(s -> "\"" + s + "\"")
                        .collect(Collectors.joining(",")) + "]"
                + ",\"status\":\"active\"}";
        Optional<StoredObject> existing = store.getByIdentifier("RoleGrant",
                List.of(new Identifier(IdentityModel.ROLE_CODE_SYSTEM, roleCode))).stream().findFirst();
        if (existing.isPresent()) {
            store.put(PutRequest.update("RoleGrant", existing.get().id(),
                    existing.get().versionId(), payload.getBytes(StandardCharsets.UTF_8)));
        } else {
            store.putIfAbsent(IdentityRef.identifier(IdentityModel.ROLE_CODE_SYSTEM, roleCode),
                    PutRequest.create("RoleGrant", payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Dev/embedded fallback credential (§16.2) — production humans federate. */
    public void ensureLocalCredential(String login, String secret, String practitionerId) {
        Optional<StoredObject> existing = store.getByIdentifier("LocalCredential",
                List.of(new Identifier(IdentityModel.LOGIN_SYSTEM, login))).stream().findFirst();
        // A login's other factors survive its password being set. Rewriting the
        // whole record would drop the edge PIN somebody set for themselves,
        // which is the same whole-object overwrite that put the PIN on a
        // configured resource in the first place (jengu-platform#871) — one
        // level down, and just as quiet.
        String keptFactors = existing.map(TenantAuthority::factorsOf).orElse(null);
        String payload = "{\"login\":\"" + login + "\""
                + ",\"secretHash\":\"" + SecretHash.hash(secret) + "\""
                + (keptFactors == null ? "" : ",\"factors\":" + keptFactors)
                + ",\"practitionerId\":\"" + practitionerId + "\""
                + ",\"status\":\"active\"}";
        if (existing.isPresent()) {
            store.put(PutRequest.update("LocalCredential", existing.get().id(),
                    existing.get().versionId(), payload.getBytes(StandardCharsets.UTF_8)));
        } else {
            store.putIfAbsent(IdentityRef.identifier(IdentityModel.LOGIN_SYSTEM, login),
                    PutRequest.create("LocalCredential", payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Sets one authentication factor for a login, leaving the others alone
     * (jengu-platform#871).
     *
     * <p><b>Factors are kinds, not fields.</b> A PIN presented at a bench with
     * no network is one kind; a password is another; a passkey and a one-time
     * code will be more. Naming the field after the first case we met would
     * have made every later one an exception.
     *
     * <p>The kind is an <b>RFC 8176 {@code amr} value</b> — {@code pwd},
     * {@code pin}, {@code otp}, {@code swk}, {@code face}, {@code fpt} — rather
     * than a name of ours. That vocabulary already exists, and it is the one
     * that belongs in the issued token's {@code amr} claim, so a relying party
     * can tell that a bench PIN is not the assurance a password is. Inventing
     * "edgePin" would have meant translating at the token boundary forever.
     *
     * <p>Credentials live here rather than on a {@code Practitioner} because
     * FHIR deliberately layers authentication outside the resource model — a
     * PIN in a clinical resource was never a FHIR shape, only an extension
     * standing where none would ever exist. Here it is store-authored state:
     * it rides a backup, never a portable export, and configuration cannot
     * overwrite it.
     */
    public void setFactor(String login, String amr, String rawSecret) {
        StoredObject existing = store.getByIdentifier("LocalCredential",
                        List.of(new Identifier(IdentityModel.LOGIN_SYSTEM, login)))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no credential for " + login + " — a factor belongs to an existing "
                                + "login, and must not be a way to create one"));
        if (!amr.matches("[a-z]{2,10}")) {
            throw new IllegalArgumentException("not an amr value: " + amr);
        }
        String json = new String(existing.payload(), StandardCharsets.UTF_8);
        Object node = Json.parse(json);
        Object factors = node instanceof java.util.Map<?, ?> m ? m.get("factors") : null;
        StringBuilder rebuilt = new StringBuilder("{");
        if (factors instanceof java.util.Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (!amr.equals(k)) {
                    rebuilt.append(rebuilt.length() > 1 ? "," : "")
                            .append('"').append(k).append("\":\"").append(v).append('"');
                }
            });
        }
        rebuilt.append(rebuilt.length() > 1 ? "," : "")
                .append('"').append(amr).append("\":\"")
                .append(SecretHash.hash(rawSecret)).append("\"}");

        // Remove only the factors block. Truncating everything after it took
        // the fields that followed — status among them — and the store refused
        // the write rather than storing a credential with a hole in it.
        String withoutFactors = removeFactors(json);
        String stripped = withoutFactors.substring(0, withoutFactors.lastIndexOf('}'));
        store.put(PutRequest.update("LocalCredential", existing.id(), existing.versionId(),
                (stripped + ",\"factors\":" + rebuilt + "}").getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether this secret is the one set for that login and that factor kind. */
    public boolean verifyFactor(String login, String amr, String rawSecret) {
        return store.getByIdentifier("LocalCredential",
                        List.of(new Identifier(IdentityModel.LOGIN_SYSTEM, login)))
                .stream().findFirst()
                .map(o -> factorHash(o, amr))
                .filter(hash -> hash != null && SecretHash.verify(rawSecret, hash))
                .isPresent();
    }

    private static String factorHash(StoredObject credential, String amr) {
        Object node = Json.parse(new String(credential.payload(), StandardCharsets.UTF_8));
        Object factors = node instanceof java.util.Map<?, ?> m ? m.get("factors") : null;
        return factors instanceof java.util.Map<?, ?> map && map.get(amr) != null
                ? String.valueOf(map.get(amr)) : null;
    }

    /** The payload without its factors block, everything else in place. */
    private static String removeFactors(String json) {
        int at = json.indexOf(",\"factors\":");
        if (at < 0) {
            return json;
        }
        int close = json.indexOf('}', json.indexOf('{', at));
        return json.substring(0, at) + json.substring(close + 1);
    }

    /** The factors block verbatim, so setting a password preserves it. */
    private static String factorsOf(StoredObject credential) {
        String json = new String(credential.payload(), StandardCharsets.UTF_8);
        int at = json.indexOf("\"factors\":");
        if (at < 0) {
            return null;
        }
        int start = json.indexOf('{', at);
        int end = json.indexOf('}', start);
        return json.substring(start, end + 1);
    }

    /**
     * §16.1 subject resolution: a verified national identifier finds the
     * Practitioner through the engine's identifier lookup — the vault's
     * HMAC index under PDI, the envelope otherwise; the same call either way.
     */
    public Optional<String> resolveByNationalId(String system, String value) {
        return subjectStore.getByIdentifier("Practitioner",
                List.of(new Identifier(system, value))).stream()
                .map(StoredObject::id).findFirst();
    }

    /** The evaluated authorization: SMART scopes AND the role codes behind them. */
    public record Grants(List<String> scopes, List<String> roles) {}

    /** Active PractitionerRoles → role codes → RoleGrants → scopes + roles. */
    public Grants evaluateGrants(String practitionerId) {
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        for (StoredObject role : subjectStore.select(
                cloud.jengu.dbo.core.api.Criteria.of("PractitionerRole")
                        .referencing("practitioner", "Practitioner", practitionerId))) {
            Object payload = Json.parse(new String(role.payload(), StandardCharsets.UTF_8));
            if (!periodActive(payload)) {
                continue;
            }
            for (Object code : Json.array(payload, "code")) {
                for (Object coding : Json.array(code, "coding")) {
                    String roleCode = Json.strOpt(coding, "code");
                    if (roleCode != null) {
                        store.getByIdentifier("RoleGrant", List.of(new Identifier(
                                        IdentityModel.ROLE_CODE_SYSTEM, roleCode))).stream()
                                .filter(g -> "active".equals(field(g, "status")))
                                .findFirst()
                                .ifPresent(g -> {
                                    roles.add(roleCode);
                                    scopes.addAll(Json.strings(Json.parse(
                                            new String(g.payload(), StandardCharsets.UTF_8)), "scopes"));
                                });
                    }
                }
            }
        }
        return new Grants(List.copyOf(scopes), List.copyOf(roles));
    }

    private static boolean periodActive(Object practitionerRolePayload) {
        Object period = ((Map<?, ?>) practitionerRolePayload).get("period");
        if (period == null) {
            return true;
        }
        String now = java.time.LocalDate.now().toString();
        String start = Json.strOpt(period, "start");
        String end = Json.strOpt(period, "end");
        return (start == null || start.compareTo(now) <= 0)
                && (end == null || end.compareTo(now) >= 0);
    }

    // -------------------------------------------------- authorization code

    record PendingAuthorization(String clientId, String redirectUri, String codeChallenge,
            String practitionerId, String scope, String roles, String nonce, long expiresAt) {}

    public sealed interface AuthorizeResult {
        record LoginRequired(String clientId, String redirectUri) implements AuthorizeResult {}
        record Rejected(String error) implements AuthorizeResult {}
    }

    /** Validates the front-channel request BEFORE any credentials are seen. */
    public AuthorizeResult beginAuthorization(String clientId, String redirectUri,
            String codeChallenge) {
        Optional<StoredObject> client = findClient(clientId);
        if (client.isEmpty() || !"active".equals(field(client.get(), "status"))) {
            return new AuthorizeResult.Rejected("unauthorized_client");
        }
        Object payload = Json.parse(new String(client.get().payload(), StandardCharsets.UTF_8));
        if (!Json.strings(payload, "redirectUris").contains(redirectUri)) {
            // NEVER redirect to an unregistered target
            return new AuthorizeResult.Rejected("invalid_redirect_uri");
        }
        if ("public-pkce".equals(Json.strOpt(payload, "clientType"))
                && (codeChallenge == null || codeChallenge.isBlank())) {
            return new AuthorizeResult.Rejected("invalid_request");
        }
        return new AuthorizeResult.LoginRequired(clientId, redirectUri);
    }

    public sealed interface LoginResult {
        record Redirect(String code) implements LoginResult {}
        record Denied(String error) implements LoginResult {}
    }

    /** Authenticates (via the seam), evaluates grants, mints the one-time code. */
    public LoginResult completeLogin(String clientId, String redirectUri, String codeChallenge,
            String nonce,
            String login, String secret) {
        if (beginAuthorization(clientId, redirectUri, codeChallenge)
                instanceof AuthorizeResult.Rejected rejected) {
            return new LoginResult.Denied(rejected.error());
        }
        Optional<String> practitioner = humanAuthenticator.authenticate(login, secret);
        if (practitioner.isEmpty()) {
            return new LoginResult.Denied("access_denied");
        }
        Grants grants = evaluateGrants(practitioner.get());
        if (grants.scopes().isEmpty()) {
            return new LoginResult.Denied("access_denied");
        }
        String code = UuidV7.newId() + UuidV7.newId().substring(0, 8);
        pendingCodes.put(code, new PendingAuthorization(clientId, redirectUri, codeChallenge,
                practitioner.get(), String.join(" ", grants.scopes()),
                String.join(",", grants.roles()), nonce == null ? "" : nonce,
                System.currentTimeMillis() + 60_000));
        return new LoginResult.Redirect(code);
    }

    /** RFC 7636 S256: the verifier hashes to the challenge. */
    private static boolean pkceMatches(String challenge, String verifier) {
        if (challenge == null) {
            return true; // confidential client authenticated with its secret
        }
        if (verifier == null) {
            return false;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return challenge.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (Exception e) {
            return false;
        }
    }

    public TokenResult exchangeCode(String code, String redirectUri, String clientId,
            String clientSecret, String codeVerifier) {
        PendingAuthorization pending = pendingCodes.remove(code);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()
                || !pending.clientId().equals(clientId)
                || !pending.redirectUri().equals(redirectUri)) {
            return new TokenResult.Rejected("invalid_grant", "code invalid or expired");
        }
        Optional<StoredObject> client = findClient(clientId);
        if (client.isEmpty()) {
            return new TokenResult.Rejected("invalid_client", "unknown client");
        }
        Object payload = Json.parse(new String(client.get().payload(), StandardCharsets.UTF_8));
        boolean confidential = !"public-pkce".equals(Json.strOpt(payload, "clientType"));
        if (confidential) {
            String hash = Json.strOpt(payload, "secretHash");
            if (hash == null || clientSecret == null || !SecretHash.verify(clientSecret, hash)) {
                return new TokenResult.Rejected("invalid_client", "client authentication failed");
            }
        }
        if (!pkceMatches(pending.codeChallenge(), codeVerifier)) {
            return new TokenResult.Rejected("invalid_grant", "PKCE verification failed");
        }
        return humanTokens(pending.practitionerId(), clientId, pending.scope(), pending.roles(),
                pending.nonce());
    }

    /** Refresh RE-EVALUATES grants — yesterday's revocation is today's denial. */
    public TokenResult refresh(String refreshToken) {
        Object claims;
        try {
            Jws.Parts parts = Jws.parse(refreshToken);
            RSAPublicKey key = keyCache.get(parts.kid());
            if (key == null) {
                refreshKeyCache();
                key = keyCache.get(parts.kid());
            }
            if (key == null || !Jws.verify(parts, key)) {
                return new TokenResult.Rejected("invalid_grant", "refresh token invalid");
            }
            claims = Json.parse(parts.claimsJson());
        } catch (RuntimeException invalid) {
            return new TokenResult.Rejected("invalid_grant", "refresh token invalid");
        }
        if (!"refresh".equals(Json.strOpt(claims, "typ"))
                || !issuer.equals(Json.str(claims, "iss"))
                || Json.num(claims, "exp") < System.currentTimeMillis() / 1000) {
            return new TokenResult.Rejected("invalid_grant", "refresh token invalid");
        }
        String practitionerId = Json.str(claims, "sub");
        Grants grants = evaluateGrants(practitionerId);
        if (grants.scopes().isEmpty()) {
            return new TokenResult.Rejected("access_denied", "no active grants");
        }
        return humanTokens(practitionerId, Json.str(claims, "client_id"),
                String.join(" ", grants.scopes()), String.join(",", grants.roles()));
    }

    // -------------------------------------------------- on-behalf-of (§16.4)

    /**
     * RFC 8693 live delegation: a service holding the user's token exchanges
     * it — subject stays the practitioner, {@code act} names the client, and
     * the scopes attenuate to subject ∩ requested.
     */
    public TokenResult exchangeToken(String subjectToken, String clientId, String clientSecret,
            String requestedScope) {
        if (!clientAuthenticated(clientId, clientSecret)) {
            return new TokenResult.Rejected("invalid_client", "client authentication failed");
        }
        Optional<AuthContext> subject = validate(subjectToken);
        if (subject.isEmpty() || subject.get().fhirUser() == null) {
            return new TokenResult.Rejected("invalid_grant", "subject token invalid or not a human's");
        }
        List<String> scopes = attenuate(subject.get().scopes(), requestedScope);
        if (scopes.isEmpty()) {
            return new TokenResult.Rejected("access_denied", "no delegable scope remains");
        }
        List<String> roles = Json.strings(Json.parse(Jws.parse(subjectToken).claimsJson()), "roles");
        return actToken(subject.get().clientId(), clientId, scopes, roles);
    }

    /**
     * §16.4 durable delegation: recorded while the human's token is live,
     * exchanged against AFTER it expired. Revocable by ending it; the
     * exchange re-evaluates the human's CURRENT grants and intersects with
     * the recorded scopes — a delegation can neither outlive a revocation
     * nor widen with a later grant.
     */
    public Optional<String> createDelegation(String subjectToken, String clientId,
            String processRef, List<String> scopes, long validUntilEpochSeconds) {
        Optional<AuthContext> subject = validate(subjectToken);
        if (subject.isEmpty() || subject.get().fhirUser() == null
                || !subject.get().scopes().containsAll(scopes)) {
            return Optional.empty();
        }
        String payload = "{\"practitionerId\":\"" + subject.get().clientId() + "\""
                + ",\"clientId\":\"" + clientId + "\""
                + (processRef != null ? ",\"processRef\":\"" + processRef + "\"" : "")
                + ",\"scopes\":[" + scopes.stream().map(x -> "\"" + x + "\"")
                        .collect(Collectors.joining(",")) + "]"
                + ",\"validUntil\":" + validUntilEpochSeconds
                + ",\"status\":\"active\"}";
        return Optional.of(store.put(PutRequest.create("Delegation",
                payload.getBytes(StandardCharsets.UTF_8))).id());
    }

    /** @return true when the caller (the delegation's subject) could end it */
    public boolean endDelegation(String delegationId, String subjectToken) {
        Optional<AuthContext> subject = validate(subjectToken);
        Optional<StoredObject> delegation = store.get("Delegation", delegationId);
        if (subject.isEmpty() || delegation.isEmpty()
                || !field(delegation.get(), "practitionerId").equals(subject.get().clientId())) {
            return false;
        }
        String payload = new String(delegation.get().payload(), StandardCharsets.UTF_8)
                .replace("\"status\":\"active\"", "\"status\":\"ended\"");
        store.put(PutRequest.update("Delegation", delegationId,
                delegation.get().versionId(), payload.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    public TokenResult exchangeDelegation(String delegationId, String clientId,
            String clientSecret, String requestedScope) {
        if (!clientAuthenticated(clientId, clientSecret)) {
            return new TokenResult.Rejected("invalid_client", "client authentication failed");
        }
        Optional<StoredObject> delegation = store.get("Delegation", delegationId);
        if (delegation.isEmpty()
                || !"active".equals(field(delegation.get(), "status"))
                || !clientId.equals(field(delegation.get(), "clientId"))) {
            return new TokenResult.Rejected("invalid_grant", "delegation invalid or ended");
        }
        Object payload = Json.parse(new String(delegation.get().payload(), StandardCharsets.UTF_8));
        if (Json.num(payload, "validUntil") < System.currentTimeMillis() / 1000) {
            return new TokenResult.Rejected("invalid_grant", "delegation expired");
        }
        String practitionerId = Json.str(payload, "practitionerId");
        // recorded scopes ∩ the human's CURRENT grants ∩ the request
        Grants current = evaluateGrants(practitionerId);
        List<String> scopes = attenuate(Json.strings(payload, "scopes"), requestedScope).stream()
                .filter(current.scopes()::contains)
                .toList();
        if (scopes.isEmpty()) {
            return new TokenResult.Rejected("access_denied", "no delegable scope remains");
        }
        return actToken(practitionerId, clientId, scopes, current.roles());
    }

    private boolean clientAuthenticated(String clientId, String clientSecret) {
        Optional<StoredObject> client = findClient(clientId);
        if (client.isEmpty() || !"active".equals(field(client.get(), "status"))) {
            return false;
        }
        String hash = Json.strOpt(
                Json.parse(new String(client.get().payload(), StandardCharsets.UTF_8)), "secretHash");
        return hash != null && clientSecret != null && SecretHash.verify(clientSecret, hash);
    }

    private static List<String> attenuate(List<String> granted, String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return granted;
        }
        return List.of(requestedScope.trim().split("\\s+")).stream()
                .filter(granted::contains)
                .toList();
    }

    private TokenResult actToken(String practitionerId, String actingClientId,
            List<String> scopes, List<String> roles) {
        StoredObject key = activeKey().orElseThrow(() -> new IllegalStateException("no active signing key"));
        long now = System.currentTimeMillis() / 1000;
        String rolesJson = roles.isEmpty() ? "[]"
                : "[\"" + String.join("\",\"", roles) + "\"]";
        String claims = "{\"iss\":\"" + issuer + "\",\"sub\":\"" + practitionerId + "\""
                + ",\"aud\":\"" + issuer + "\",\"client_id\":\"" + actingClientId + "\""
                + ",\"fhirUser\":\"Practitioner/" + practitionerId + "\""
                + ",\"roles\":" + rolesJson
                + ",\"act\":{\"sub\":\"" + actingClientId + "\"}"
                + ",\"scope\":\"" + String.join(" ", scopes) + "\""
                + ",\"jti\":\"" + UuidV7.newId() + "\""
                + ",\"iat\":" + now + ",\"exp\":" + (now + TOKEN_TTL_SECONDS) + "}";
        return new TokenResult.Issued(
                Jws.sign(field(key, "kid"), claims, privateKey(key)), TOKEN_TTL_SECONDS,
                String.join(" ", scopes));
    }

    private TokenResult humanTokens(String practitionerId, String clientId, String scope,
            String rolesCsv) {
        return humanTokens(practitionerId, clientId, scope, rolesCsv, null);
    }

    /**
     * OIDC: the auth-code exchange carries an id_token (aud = the CLIENT, unlike
     * the access token's aud = issuer) so standard RPs — Spring oauth2Login —
     * can build a principal without touching the access token. {@code nonce}
     * null = refresh (no id_token); empty = code flow without a nonce.
     */
    private TokenResult humanTokens(String practitionerId, String clientId, String scope,
            String rolesCsv, String nonce) {
        StoredObject key = activeKey().orElseThrow(() -> new IllegalStateException("no active signing key"));
        long now = System.currentTimeMillis() / 1000;
        String rolesJson = rolesCsv == null || rolesCsv.isEmpty() ? "[]"
                : "[\"" + rolesCsv.replace(",", "\",\"") + "\"]";
        String base = "\"iss\":\"" + issuer + "\",\"sub\":\"" + practitionerId + "\""
                + ",\"aud\":\"" + issuer + "\",\"client_id\":\"" + clientId + "\""
                + ",\"fhirUser\":\"Practitioner/" + practitionerId + "\""
                + ",\"roles\":" + rolesJson
                + ",\"scope\":\"" + scope + "\",\"iat\":" + now;
        String access = "{" + base + ",\"jti\":\"" + UuidV7.newId() + "\""
                + ",\"exp\":" + (now + TOKEN_TTL_SECONDS) + "}";
        String refresh = "{" + base + ",\"jti\":\"" + UuidV7.newId() + "\",\"typ\":\"refresh\""
                + ",\"exp\":" + (now + 43_200) + "}";
        String kid = field(key, "kid");
        String idToken = null;
        if (nonce != null) {
            idToken = Jws.sign(kid, "{\"iss\":\"" + issuer + "\",\"sub\":\"" + practitionerId + "\""
                    + ",\"aud\":\"" + clientId + "\""
                    + ",\"fhirUser\":\"Practitioner/" + practitionerId + "\""
                    + ",\"roles\":" + rolesJson
                    + (nonce.isEmpty() ? "" : ",\"nonce\":\"" + nonce + "\"")
                    + ",\"iat\":" + now + ",\"exp\":" + (now + TOKEN_TTL_SECONDS) + "}",
                    privateKey(key));
        }
        return new TokenResult.IssuedHuman(
                Jws.sign(kid, access, privateKey(key)), TOKEN_TTL_SECONDS, scope,
                Jws.sign(kid, refresh, privateKey(key)), idToken);
    }

    private Optional<StoredObject> findClient(String clientId) {
        return store.getByIdentifier("ClientApplication",
                List.of(new Identifier(IdentityModel.CLIENT_ID_SYSTEM, clientId))).stream().findFirst();
    }

    // ------------------------------------------------------------ token issuance

    public sealed interface TokenResult {
        record Issued(String accessToken, long expiresIn, String scope) implements TokenResult {}
        record IssuedHuman(String accessToken, long expiresIn, String scope,
                // idToken null on refresh — RPs already hold their principal
                String refreshToken, String idToken) implements TokenResult {}
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

    /** For delegated tokens {@code actClient} names the acting service (§16.4). */
    public record AuthContext(String clientId, String fhirUser, String actClient,
            List<String> scopes) {}

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
            if ("refresh".equals(Json.strOpt(claims, "typ"))) {
                return Optional.empty(); // refresh tokens never touch the surface
            }
            String actClient = null;
            if (((Map<?, ?>) claims).get("act") instanceof Map<?, ?> act) {
                actClient = String.valueOf(act.get("sub"));
            }
            return Optional.of(new AuthContext(Json.str(claims, "sub"),
                    Json.strOpt(claims, "fhirUser"), actClient,
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
                + ",\"authorization_endpoint\":\"" + issuer + "/authorize\""
                + ",\"token_endpoint\":\"" + issuer + "/token\""
                + ",\"jwks_uri\":\"" + issuer + "/.well-known/jwks.json\""
                + ",\"grant_types_supported\":[\"client_credentials\",\"authorization_code\",\"refresh_token\"]"
                + ",\"code_challenge_methods_supported\":[\"S256\"]"
                + ",\"token_endpoint_auth_methods_supported\":[\"client_secret_post\",\"client_secret_basic\",\"none\"]"
                + ",\"response_types_supported\":[\"code\",\"token\"]"
                + ",\"subject_types_supported\":[\"public\"]"
                + ",\"scopes_supported\":[\"openid\"]"
                + ",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
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

    /** §16.2 dev/embedded fallback: LocalCredential records in the identity store. */
    private final class LocalCredentialAuthenticator implements HumanAuthenticator {
        @Override
        public Optional<String> authenticate(String login, String secret) {
            return store.getByIdentifier("LocalCredential",
                            List.of(new Identifier(IdentityModel.LOGIN_SYSTEM, login))).stream()
                    .filter(c -> "active".equals(field(c, "status")))
                    .filter(c -> SecretHash.verify(secret, field(c, "secretHash")))
                    .map(c -> field(c, "practitionerId"))
                    .findFirst();
        }
    }
}
