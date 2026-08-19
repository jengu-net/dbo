package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityHub;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eeID slice (§16.2): the identity hub federates to the national broker
 * ONCE and its session serves every tenant authority — the cost proof is
 * the broker's own counter. Authorization stays per-tenant: the same valid
 * identity is access at a clinic with a grant and access_denied at one
 * without. The subject identifier system is zone-scoped configuration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FederatedAuthIT {

    /** Zone-scoped in production (the official national ValueSet); config here. */
    static final String SUBJECT_SYSTEM = "https://fhir.ee/sid/pid/est/ni";
    static final String ISIKUKOOD = "37001010021";
    static final String REDIRECT = "http://127.0.0.1/cb";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static byte[] kek = new byte[32];
    static HttpServer stubBroker;
    static KeyPair stubKey;
    static final AtomicInteger brokerCeremonies = new AtomicInteger();
    static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager()).build();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("FederatedAuthIT");
        new SecureRandom().nextBytes(kek);
        stubBroker = stubNationalBroker();

        dir = Files.createTempDirectory("dbo-federated");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null,
                        new IdentityHub.Upstream(
                                "http://127.0.0.1:" + stubBroker.getAddress().getPort(),
                                "zone-hub", "hub-secret", "EE"),
                        SUBJECT_SYSTEM));
        for (String code : List.of("kliinika", "kliinikb", "kliinikc")) {
            Files.writeString(dir.resolve(code + ".json"), """
                    {"code":"%s","fhirVersion":"r4","types":[
                      {"name":"Patient","identity":"internal","handling":"operational"},
                      {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                      {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                      {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                    .formatted(code, SUBJECT_SYSTEM, SUBJECT_SYSTEM));
        }
        UntilServed.scan(manager, "kliinika", "kliinikb", "kliinikc");

        // the doctor works at clinics A and B — not C
        for (String code : List.of("kliinika", "kliinikb")) {
            String service = serviceToken(code);
            String practitioner = idOf(fhirPost(code, "/Practitioner", service, """
                    {"resourceType":"Practitioner",
                     "identifier":[{"system":"%s","value":"%s"}]}"""
                    .formatted(SUBJECT_SYSTEM, ISIKUKOOD)));
            // the human the hub will assert, with the clinician as a relation
            assertEquals(201, fhirPost(code, "/Person", service, """
                    {"resourceType":"Person",
                     "identifier":[{"system":"%s","value":"%s"}],
                     "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                    .formatted(SUBJECT_SYSTEM, ISIKUKOOD, practitioner)).statusCode());
            assertEquals(201, fhirPost(code, "/PractitionerRole", service, """
                    {"resourceType":"PractitionerRole",
                     "practitioner":{"reference":"Practitioner/%s"},
                     "code":[{"coding":[{"system":"urn:example:role","code":"doctor"}]}]}"""
                    .formatted(practitioner)).statusCode());
            sideAuthority(code).ensureRoleGrant("doctor", List.of("user/*.read"));
            sideAuthority(code).ensureClient("webapp", null,
                    List.of("user/*.read", "user/*.write"), "public-pkce", List.of(REDIRECT));
        }
        sideAuthority("kliinikc").ensureClient("webapp", null,
                List.of("user/*.read"), "public-pkce", List.of(REDIRECT));
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
        if (stubBroker != null) {
            stubBroker.stop(0);
        }
    }

    /** A minimal national broker: OIDC discovery, auto-approving authorize, id_token. */
    private static HttpServer stubNationalBroker() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        stubKey = generator.generateKeyPair();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            int status = 200;
            switch (path) {
                case "/.well-known/openid-configuration" -> response =
                        "{\"issuer\":\"" + base + "\""
                                + ",\"authorization_endpoint\":\"" + base + "/authorize\""
                                + ",\"token_endpoint\":\"" + base + "/token\""
                                + ",\"jwks_uri\":\"" + base + "/jwks\"}";
                case "/authorize" -> {
                    // THE billable ceremony
                    brokerCeremonies.incrementAndGet();
                    String query = exchange.getRequestURI().getRawQuery();
                    String redirectUri = param(query, "redirect_uri");
                    String state = param(query, "state");
                    exchange.getResponseHeaders().set("Location",
                            redirectUri + "?code=stub-code&state=" + state);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                case "/token" -> {
                    long now = System.currentTimeMillis() / 1000;
                    String idToken = cloud.jengu.dbo.auth.Jws.sign("stub-kid",
                            "{\"iss\":\"" + base + "\",\"aud\":\"zone-hub\""
                                    + ",\"sub\":\"EE" + ISIKUKOOD + "\""
                                    + ",\"iat\":" + now + ",\"exp\":" + (now + 300) + "}",
                            stubKey.getPrivate());
                    response = "{\"id_token\":\"" + idToken + "\",\"access_token\":\"n/a\""
                            + ",\"token_type\":\"Bearer\"}";
                }
                case "/jwks" -> response = "{\"keys\":[" + cloud.jengu.dbo.auth.Jwk.render("stub-kid", (RSAPublicKey) stubKey.getPublic()) + "]}";
                default -> {
                    response = "{}";
                    status = 404;
                }
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(pair.substring(name.length() + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private TenantAuthority sideAuthority(String code) throws Exception {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        String jdbcBase = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        ds.setUrl(jdbcBase + "tenant_" + code);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return new TenantAuthority(new PgObjectStore(ds, IdentityModel.registrations()),
                base(code) + "/oidc", new KeyProtector(kek));
    }

    private String base(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code;
    }

    private String serviceToken(String code) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(base(code) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                                        + URLEncoder.encode(provisioner.bootstrapClientSecret(code),
                                                StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private HttpResponse<String> fhirPost(String code, String path, String token, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(code) + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(HttpResponse<String> created) {
        return created.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
    }

    /** Follows redirects manually (the cookie manager rides along) until the RP callback. */
    private String federatedLogin(String code) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String location = base(code) + "/oidc/authorize?response_type=code&client_id=webapp&state=zzz"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        for (int hop = 0; hop < 8; hop++) {
            if (location.startsWith(REDIRECT)) {
                if (location.contains("error=")) {
                    return "error:" + location.replaceAll(".*error=([^&]+).*", "$1");
                }
                String authCode = location.replaceAll(".*code=([^&]+).*", "$1");
                HttpResponse<String> tokens = http.send(HttpRequest.newBuilder(
                                URI.create(base(code) + "/oidc/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "grant_type=authorization_code&client_id=webapp&code=" + authCode
                                                + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                                                + "&code_verifier=" + verifier)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, tokens.statusCode(), tokens.body());
                return tokens.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
            }
            HttpResponse<String> hopResponse = http.send(HttpRequest.newBuilder(
                    URI.create(location)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, hopResponse.statusCode(),
                    "hop to " + location + " answered: " + hopResponse.body());
            location = hopResponse.headers().firstValue("Location").orElseThrow();
        }
        throw new AssertionError("redirect chain did not reach the RP");
    }

    /** One ceremony at clinic A: the full chain through the hub and the broker. */
    @Test
    @Order(1)
    void firstLoginRunsTheNationalCeremonyOnce() throws Exception {
        String token = federatedLogin("kliinika");
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"fhirUser\":\"Practitioner/"), claims);
        assertEquals(1, brokerCeremonies.get(), "exactly one billable ceremony");
    }

    /** Clinic B rides the hub session: a second tenant, ZERO further ceremonies. */
    @Test
    @Order(2)
    void secondTenantCostsNoCeremony() throws Exception {
        String token = federatedLogin("kliinikb");
        assertTrue(token.startsWith("ey"), token);
        assertEquals(1, brokerCeremonies.get(),
                "the hub session must serve the second tenant without the broker");
    }

    /** Clinic C: same valid identity, no grant — authorization is never shared. */
    @Test
    @Order(3)
    void aTenantWithoutAGrantDeniesTheSameIdentity() throws Exception {
        assertEquals("error:access_denied", federatedLogin("kliinikc"));
        assertEquals(1, brokerCeremonies.get(), "denial costs no ceremony either");
    }
}
