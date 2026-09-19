package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant delivers a notification, which it never had.
 *
 * <p>The engine was complete, tested, bundled — and constructed by nothing. A
 * tenant could hold a `Subscription`, the store would accept it, the matching
 * was correct, and nobody was ever told anything. The reach ledger said it as
 * <i>NOT REACHED — no composition root knows subscriptions exist</i>, and the
 * sharper version turned out to be that none could: the two halves that knew
 * how to compose a notification hung off `R4Store` and `R5Store`, and a tenant
 * on the R4 face is served by the element store. They were wired to each other
 * across a seam no request crosses.
 *
 * <p>So the face that serves supplies them now, and this is the whole distance
 * proven end to end: a real tenant brought up the way a deployment brings one
 * up, a subscription written through its own door, a resource written through
 * its own door, and a POST arriving at an endpoint that is not this store.
 *
 * <p><b>And it carries the name rather than the record.</b> The channel
 * declares no payload — FHIR's own default, and the only shape that is safe
 * across a plane which may not read identifying elements. The criteria path
 * used to send the resource regardless of what the channel asked for, which
 * nobody noticed because nothing had ever delivered.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantDeliversWhatItSubscribedToIT {

    private static final String CLINIC = "teavitus";
    private static final String R5_CLINIC = "teavitus-r5";
    private static final String TOPIC = "https://teavitus.example/SubscriptionTopic/final-results";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static HttpServer subscriber;
    static final List<String> delivered = new CopyOnWriteArrayList<>();
    static final List<String> onTopic = new CopyOnWriteArrayList<>();
    static String endpoint;
    static String topicEndpoint;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-notify");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantDeliversWhatItSubscribedToIT"),
                postgres.getUsername(), postgres.getPassword());

        // Somebody else's endpoint: not this store, and reachable from it only
        // as a socket. A notification that never crossed it was not delivered.
        subscriber = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriber.createContext("/notify", exchange -> {
            delivered.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        subscriber.createContext("/on-topic", exchange -> {
            onTopic.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        subscriber.start();
        endpoint = "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/notify";
        topicEndpoint = "http://127.0.0.1:" + subscriber.getAddress().getPort() + "/on-topic";

        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Observation","identity":"internal","handling":"operational"},
                  {"name":"Subscription","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC));
        // An R5 tenant beside it: topics are records there, which is what
        // makes the topic half reachable at all.
        Files.writeString(dir.resolve(R5_CLINIC + ".json"), """
                {"code":"%s","face":"r5","types":[
                  {"name":"Observation","identity":"internal","handling":"operational"},
                  {"name":"Subscription","identity":"internal","handling":"operational"},
                  {"name":"SubscriptionTopic","identity":"canonical","handling":"operational"}]}"""
                .formatted(R5_CLINIC));
        UntilServed.scan(manager, CLINIC, R5_CLINIC);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (subscriber != null) {
            subscriber.stop(0);
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @Order(1)
    @DisplayName("a subscriber is told that something it asked about changed, by a tenant "
            + "nobody wired by hand — which is the whole of what was missing")
    @Proving(DboPromises.EVT_A_TENANT_DELIVERS)
    void aTenantDelivers() throws Exception {
        assertEquals(201, post("/Subscription", """
                {"resourceType":"Subscription","status":"active",
                 "reason":"a device changed","criteria":"Observation?status=final",
                 "channel":{"type":"rest-hook","endpoint":"%s"}}"""
                .formatted(endpoint)).statusCode());

        assertEquals(201, post("/Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"1234-5"}]}}""")
                .statusCode());

        // The tenant's own dispatcher delivers on its own beat. Nothing here
        // pumps it: a test that drove the engine would be proving the engine,
        // which was never what was in doubt.
        String notification = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (notification == null && System.nanoTime() < deadline) {
            notification = delivered.isEmpty() ? null : delivered.get(0);
            if (notification == null) {
                Thread.sleep(100);
            }
        }

        assertTrue(notification != null,
                "nothing arrived, so a tenant still holds subscriptions it never acts on — "
                        + "which is the state this was built to end");
        assertTrue(notification.contains("Observation/"),
                "the notification does not name what changed: " + notification);
    }

    @Test
    @Order(2)
    @DisplayName("and it carries the name rather than the record, because the channel "
            + "declared no payload")
    @Proving(DboPromises.EVT_A_TENANT_DELIVERS)
    void itIsIdOnly() {
        assertFalse(delivered.isEmpty(), "nothing to inspect — see the test above");
        String notification = delivered.get(0);

        assertFalse(notification.contains("1234-5"),
                "the resource travelled although no payload was asked for, so a carrier that "
                        + "may not read it is holding it: " + notification);
        assertTrue(notification.contains("Subscription/"),
                "and a subscriber cannot tell which of its subscriptions this answers: "
                        + notification);
    }

    @Test
    @Order(3)
    @DisplayName("a topic subscription delivers too, on the face that composes it — the half "
            + "that was written where no request could reach it")
    @Proving(DboPromises.EVT_FHIR_SUBSCRIPTIONS)
    void aTopicSubscriptionDelivers() throws Exception {
        assertEquals(201, post(R5_CLINIC, "/SubscriptionTopic", """
                {"resourceType":"SubscriptionTopic","url":"%s","status":"active",
                 "resourceTrigger":[{"resource":"Observation",
                                     "supportedInteraction":["create","update"]}],
                 "canFilterBy":[{"filterParameter":"status"}]}"""
                .formatted(TOPIC)).statusCode());

        assertEquals(201, post(R5_CLINIC, "/Subscription", """
                {"resourceType":"Subscription","status":"active","topic":"%s",
                 "channelType":{"code":"rest-hook"},"endpoint":"%s","content":"id-only",
                 "filterBy":[{"filterParameter":"status","value":"final"}]}"""
                .formatted(TOPIC, topicEndpoint)).statusCode());

        assertEquals(201, post(R5_CLINIC, "/Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"9999-1"}]}}""")
                .statusCode());

        String notification = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (notification == null && System.nanoTime() < deadline) {
            notification = onTopic.isEmpty() ? null : onTopic.get(0);
            if (notification == null) {
                Thread.sleep(100);
            }
        }

        assertTrue(notification != null,
                "a topic subscription delivered nothing, so the half that was unreachable is "
                        + "still unreachable — just somewhere else");
        assertTrue(notification.contains("subscription-notification"),
                "it is not the R5 notification shape: " + notification);
        assertTrue(notification.contains(TOPIC),
                "and it does not say which topic it answers: " + notification);
        assertFalse(notification.contains("9999-1"),
                "content was id-only and the resource travelled anyway: " + notification);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return post(CLINIC, path, body);
    }

    private static HttpResponse<String> post(String tenant, String path, String body)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(tenant) + path))
                        .header("Authorization", "Bearer " + token(tenant))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String tenant) throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(provisioner.bootstrapClientSecret(tenant),
                        StandardCharsets.UTF_8);
        return Extracted.tokenIn(HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(tenant).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body());
    }
}
