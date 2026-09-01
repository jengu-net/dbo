package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.Jwk;
import cloud.jengu.dbo.auth.Jws;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.auth.ZoneModel;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §17: the zone is a tenant whose declarations are records — two
 * national brokers (tara: government, eeid: private) declared in the zone
 * tenant; the tenant chooses its broker, may restrict what it accepts, and
 * the per-zone hub's sessions ACCUMULATE ceremonies. The fee proof is
 * per-broker counters across four logins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ZoneIT {

    static final String SUBJECT_SYSTEM = "https://fhir.ee/sid/pid/est/ni";
    static final String ISIKUKOOD = "37001010021";
    static final String REDIRECT = "http://127.0.0.1/cb";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static byte[] kek = new byte[32];
    static HttpServer stubBrokers;
    static final Map<String, KeyPair> brokerKeys = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> ceremonies = new ConcurrentHashMap<>();
    static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager()).build();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("ZoneIT");
        new SecureRandom().nextBytes(kek);
        stubBrokers = stubBrokerPair();

        dir = Files.createTempDirectory("dbo-zone");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null, null, null,
                        Map.of("tara", "tara-salajane", "eeid", "eeid-salajane")));

        // the ZONE tenant first — its declarations are records
        Files.writeString(dir.resolve("ee.json"), """
                {"code":"ee","face":"r4","types":[{"name":"Basic","identity":"internal","handling":"operational"}]}""");
        manager.scanOnce();
        String stubBase = "http://127.0.0.1:" + stubBrokers.getAddress().getPort();
        PgObjectStore zoneStore = new PgObjectStore(tenantDs("ee"), ZoneModel.registrations());
        zoneStore.put(PutRequest.create("ZoneBroker", ZoneModel.Broker.payload(
                new ZoneModel.Broker("tara", stubBase + "/tara", "zone-tara", "EE", "high"))));
        zoneStore.put(PutRequest.create("ZoneBroker", ZoneModel.Broker.payload(
                new ZoneModel.Broker("eeid", stubBase + "/eeid", "zone-eeid", "EE", "substantial"))));
        zoneStore.put(PutRequest.create("ZoneIdentifierDomain",
                ZoneModel.identifierDomainPayload(ZoneModel.USE_PERSON_PRIMARY, SUBJECT_SYSTEM)));

        // haigla: municipal (tara, tara-only policy); kliinik: private (eeid, accepts any)
        Files.writeString(dir.resolve("haigla.json"), """
                {"code":"haigla","face":"r4","zone":"ee","broker":"tara",
                 "acceptedBrokers":["tara"],"types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                .formatted(SUBJECT_SYSTEM, SUBJECT_SYSTEM));
        Files.writeString(dir.resolve("kliinik.json"), """
                {"code":"kliinik","face":"r4","zone":"ee","broker":"eeid","types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],"handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                .formatted(SUBJECT_SYSTEM, SUBJECT_SYSTEM));
        manager.scanOnce();

        for (String code : List.of("haigla", "kliinik")) {
            String service = serviceToken(code);
            String practitioner = idOf(fhirPost(code, "/Practitioner", service, """
                    {"resourceType":"Practitioner",
                     "identifier":[{"system":"%s","value":"%s"}]}"""
                    .formatted(SUBJECT_SYSTEM, ISIKUKOOD)));
            // the human behind the clinician, carrying the national identifier
            fhirPost(code, "/Person", service, """
                    {"resourceType":"Person",
                     "identifier":[{"system":"%s","value":"%s"}],
                     "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                    .formatted(SUBJECT_SYSTEM, ISIKUKOOD, practitioner));
            fhirPost(code, "/PractitionerRole", service, """
                    {"resourceType":"PractitionerRole",
                     "practitioner":{"reference":"Practitioner/%s"},
                     "code":[{"coding":[{"system":"urn:example:role","code":"doctor"}]}]}"""
                    .formatted(practitioner));
            TenantAuthority side = sideAuthority(code);
            side.ensureRoleGrant("doctor", List.of("user/*.read"));
            side.ensureClient("webapp", null, List.of("user/*.read"),
                    "public-pkce", List.of(REDIRECT));
        }
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
        if (stubBrokers != null) {
            stubBrokers.stop(0);
        }
    }

    /** Two national brokers on one stub server: /tara/* and /eeid/*. */
    private static HttpServer stubBrokerPair() throws Exception {
        for (String code : List.of("tara", "eeid")) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            brokerKeys.put(code, generator.generateKeyPair());
            ceremonies.put(code, new AtomicInteger());
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String broker = path.startsWith("/tara") ? "tara" : "eeid";
            String brokerBase = base + "/" + broker;
            String clientId = "zone-" + broker;
            String response;
            int status = 200;
            if (path.endsWith("/.well-known/openid-configuration")) {
                response = "{\"issuer\":\"" + brokerBase + "\""
                        + ",\"authorization_endpoint\":\"" + brokerBase + "/authorize\""
                        + ",\"token_endpoint\":\"" + brokerBase + "/token\""
                        + ",\"jwks_uri\":\"" + brokerBase + "/jwks\"}";
            } else if (path.endsWith("/authorize")) {
                ceremonies.get(broker).incrementAndGet();
                String query = exchange.getRequestURI().getRawQuery();
                exchange.getResponseHeaders().set("Location",
                        param(query, "redirect_uri") + "?code=" + broker + "-code&state="
                                + param(query, "state"));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            } else if (path.endsWith("/token")) {
                long now = System.currentTimeMillis() / 1000;
                String idToken = Jws.sign(broker + "-kid",
                        "{\"iss\":\"" + brokerBase + "\",\"aud\":\"" + clientId + "\""
                                + ",\"sub\":\"EE" + ISIKUKOOD + "\""
                                + ",\"iat\":" + now + ",\"exp\":" + (now + 300) + "}",
                        brokerKeys.get(broker).getPrivate());
                response = "{\"id_token\":\"" + idToken + "\"}";
            } else if (path.endsWith("/jwks")) {
                response = "{\"keys\":[" + Jwk.render(broker + "-kid",
                        (RSAPublicKey) brokerKeys.get(broker).getPublic()) + "]}";
            } else {
                response = "{}";
                status = 404;
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

    private static org.postgresql.ds.PGSimpleDataSource tenantDs(String code) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        String jdbcBase = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        ds.setUrl(jdbcBase + "tenant_" + code);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private TenantAuthority sideAuthority(String code) {
        return new TenantAuthority(new PgObjectStore(tenantDs(code), IdentityModel.registrations()),
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

    private String federatedLogin(String code) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String location = base(code) + "/oidc/authorize?response_type=code&client_id=webapp"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        for (int hop = 0; hop < 10; hop++) {
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

    /** The private clinic first: one eeID ceremony, subject system FROM THE ZONE. */
    @Test
    @Order(1)
    @Proving({DboPromises.AUTH_FEDERATED_HUMANS, DboPromises.ZONE_BROKER_CHOICE,
            DboPromises.ZONE_SUBJECT_DOMAINS})
    void privateClinicRunsItsContractedBrokerOnce() throws Exception {
        String token = federatedLogin("kliinik");
        assertTrue(token.startsWith("ey"), token);
        assertEquals(1, ceremonies.get("eeid").get());
        assertEquals(0, ceremonies.get("tara").get());
    }

    /** The hospital's tara-only policy is NOT satisfied by the eeid ceremony — tara runs, session accumulates. */
    @Test
    @Order(2)
    @Proving({DboPromises.ZONE_BROKER_CHOICE, DboPromises.ZONE_SESSIONS_ACCUMULATE})
    void municipalPolicyTriggersItsOwnCeremonyOntoTheSameSession() throws Exception {
        String token = federatedLogin("haigla");
        assertTrue(token.startsWith("ey"), token);
        assertEquals(1, ceremonies.get("tara").get(), "the required broker's ceremony ran");
        assertEquals(1, ceremonies.get("eeid").get(), "eeid was NOT re-run");
    }

    /** The accumulated session now serves everyone with zero further ceremonies. */
    @Test
    @Order(3)
    @Proving({DboPromises.AUTH_ONE_CEREMONY_MANY_TENANTS, DboPromises.ZONE_SESSIONS_ACCUMULATE})
    void theAccumulatedSessionServesEveryoneFreely() throws Exception {
        assertTrue(federatedLogin("kliinik").startsWith("ey"));
        assertTrue(federatedLogin("haigla").startsWith("ey"));
        assertEquals(1, ceremonies.get("eeid").get());
        assertEquals(1, ceremonies.get("tara").get());
    }
}
