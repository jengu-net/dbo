package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.asking.Questions;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.tenant.api.Change;
import cloud.jengu.dbo.tenant.api.TenantDomain;
import cloud.jengu.dbo.tenant.api.TenantFacts;
import cloud.jengu.dbo.tenant.api.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.api.TenantObserver;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application added one dependency, and it is a node.
 *
 * <p>Everything else in this assembly is a piece: the container boots, the
 * adapter converts an exchange, the filter offers a request, a seam takes a
 * server off the whiteboard. Each is proved where it lives, and none of them
 * says what an integrator actually wants to know — that a Spring Boot
 * application with this jar on its classpath serves a tenant, on its own
 * port, through its own filter chain, and can reach what the tenant offers
 * from an ordinary bean.
 *
 * <p>A toolset can be built, proved and unreachable. This is the test that
 * asks who reaches it: not a fixture written to pass, but an application of
 * the shape somebody would write, doing the things they would do.
 *
 * <p><b>A world of its own, and the reason is the bring-up.</b> The claim is
 * that a tenant comes up inside an application and answers there, so there is
 * nothing shared to assert it against — the runtime under test is the one
 * this application booted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnApplicationThatAddedThisJarServesATenantIT {

    private static final String TENANT = "springtenant";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static Path specs;

    @LocalServerPort
    int applicationPort;

    @Autowired
    DboTenants tenants;

    @Autowired
    TheApplication.NoticingATenant noticing;

    @Autowired
    TheApplication.WatchingTheWork watching;

    @DynamicPropertySource
    static void theDeployment(DynamicPropertyRegistry registry) throws IOException {
        POSTGRES.start();
        specs = Files.createTempDirectory("dbo-spring-specs");
        // Declared the way a deployment declares one: a spec file in the
        // directory this node reads. Nothing about the tenant is a Spring
        // property, because only the management tenant ever is.
        Files.writeString(specs.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));

        registry.add("dbo.tenants.directory", () -> specs.toString());
        registry.add("dbo.admin.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("dbo.admin.user", POSTGRES::getUsername);
        registry.add("dbo.admin.password", POSTGRES::getPassword);
        registry.add("dbo.auth.kek",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    @DisplayName("a tenant declared to this application comes up and answers a FHIR read on "
            + "the application's own port, through its own filter chain")
    void aTenantAnswersOnTheApplicationsPort() throws Exception {
        String base = "http://127.0.0.1:" + applicationPort + "/t/" + TENANT;
        untilServing();

        HttpResponse<String> capability = get(base + "/fhir/metadata", null);
        assertEquals(200, capability.statusCode(),
                "a tenant declared to this application does not answer on its port, so adding "
                        + "this jar starts a container and serves nobody: " + capability.body());
        assertTrue(capability.body().contains("CapabilityStatement"),
                "something answered and it was not the records door: " + capability.body());

        // A read that needs a credential, with one this tenant minted — which
        // is the whole arrangement in one request: the application's port, the
        // application's filters, the tenant's own authority.
        String token = aTokenFromTheTenantItself();
        HttpResponse<String> search = get(base + "/fhir/Patient", token);
        assertEquals(200, search.statusCode(),
                "the tenant refused a token it minted itself: " + search.body());

        assertEquals(401, get(base + "/fhir/Patient", null).statusCode(),
                "the records door answered a caller carrying nothing, so this application "
                        + "serves a tenant's records to anybody who knows the path");
    }

    @Test
    @DisplayName("an ordinary bean of the application's own reaches what the tenant offers, "
            + "without a URL, a credential or anything the container knows about")
    void aBeanReachesWhatTheTenantOffers() throws Exception {
        untilServing();

        assertEquals(List.of(TENANT), tenants.serving(),
                "the application cannot say which tenants it is serving");
        assertTrue(tenants.records(TENANT).isPresent(),
                "the tenant's records door is not reachable from a bean");

        Questions asking = tenants.asking(TENANT).orElseThrow(
                () -> new AssertionError("the questions a product asks this tenant are not "
                        + "reachable from a bean, so a screen over this store would have to "
                        + "compose criteria against the engine instead"));
        // Asked rather than merely obtained: a handle that is never called is
        // the shape of proof this repository exists to replace.
        assertEquals(0, asking.work().open().count(),
                "a tenant with no work says some is open");

        assertTrue(tenants.authority(TENANT).isPresent(),
                "the authority that verifies this tenant's tokens is not reachable, so an "
                        + "application securing its own doors with them has to verify somebody "
                        + "else's word about these records");
    }

    @Test
    @DisplayName("beans that implement the extension points are the extension points: one is "
            + "told the tenant reached serving, the other is handed what changed")
    void theApplicationsBeansAreExtensionPoints() throws Exception {
        untilServing();

        assertTrue(noticing.points.contains(TENANT),
                "a bean implementing " + TenantLifecycleListener.class.getSimpleName()
                        + " was never told this tenant reached serving, so the extension point "
                        + "is a jar an application can implement and nothing more");

        // Something for the observer to see. A write through the tenant's own
        // door, with a credential the tenant minted, which is what an
        // integrator would do.
        String token = aTokenFromTheTenantItself();
        HttpResponse<String> written = post(
                "http://127.0.0.1:" + applicationPort + "/t/" + TENANT + "/fhir/Patient",
                token, "{\"resourceType\":\"Patient\"}");
        assertTrue(written.statusCode() == 201,
                "the write this test's observer is waiting for did not happen: "
                        + written.statusCode() + " " + written.body());

        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (watching.seen.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(watching.seen.get() > 0,
                "a bean implementing " + TenantObserver.class.getSimpleName() + " was never "
                        + "handed a change, so nothing an application writes can be reacted to "
                        + "in the process that holds the store");
    }

    @Test
    @DisplayName("the application's own controllers still answer on the port they share with "
            + "the tenant, because the store claims only the paths it has doors at")
    void theApplicationsOwnEndpointsStillAnswer() throws Exception {
        HttpResponse<String> mine = get("http://127.0.0.1:" + applicationPort + "/mine", null);
        assertEquals(200, mine.statusCode(),
                "the store swallowed a request to a path it has no door at");
        assertEquals("the application's own", mine.body());
    }

    /** A credential this tenant issued, obtained the way an integrator would. */
    private String aTokenFromTheTenantItself() {
        TenantAuthority authority = tenants.authority(TENANT).orElseThrow();
        String client = "a-spring-application";
        String secret = "a-secret-for-" + client;
        authority.ensureClient(client, secret, List.of("system/*.read", "system/*.write"));
        TenantAuthority.TokenResult issued = authority.token(client, secret, null);
        if (issued instanceof TenantAuthority.TokenResult.Issued minted) {
            return minted.accessToken();
        }
        throw new AssertionError("the tenant would not issue a token to a client it had just "
                + "registered: " + issued);
    }

    /**
     * A liveness wait, not a budget.
     *
     * <p>How long a tenant takes to come up on whatever machine this is
     * running on is not this test's subject; that a tenant comes up at all
     * inside an application is.
     */
    private void untilServing() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (!tenants.isServing(TENANT) && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        assertTrue(tenants.isServing(TENANT),
                "the tenant never came up inside this application. What it is doing: "
                        + tenants.serving());
    }

    private static HttpResponse<String> get(String url, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), token);
    }

    private static HttpResponse<String> post(String url, String token, String body)
            throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), token);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, String token)
            throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The application: a controller of its own, and two beans that are
     * extension points.
     *
     * <p>Nothing here mentions a bundle, a context or a service. The two
     * listeners are the sample's own, in shape and in what they do with what
     * they are given.
     */
    @SpringBootApplication
    @RestController
    static class TheApplication {

        @GetMapping("/mine")
        String mine() {
            return "the application's own";
        }

        @org.springframework.context.annotation.Bean
        NoticingATenant noticing() {
            return new NoticingATenant();
        }

        @org.springframework.context.annotation.Bean
        WatchingTheWork watching() {
            return new WatchingTheWork();
        }

        /** Told when a tenant reaches a point. */
        @DboTenantListener(point = TenantPoint.SERVING)
        static class NoticingATenant implements TenantLifecycleListener {

            final List<String> points = new CopyOnWriteArrayList<>();

            @Override
            public void reached(TenantPoint point, TenantFacts tenant) {
                points.add(tenant.code());
            }
        }

        /** A named durable consumer of one of a tenant's streams. */
        @DboObserver(domain = TenantDomain.CONTENT, consumer = "a-spring-application")
        static class WatchingTheWork implements TenantObserver {

            final AtomicInteger seen = new AtomicInteger();

            @Override
            public void observed(TenantFacts tenant, List<Change> changes) {
                seen.addAndGet(changes.size());
            }
        }
    }
}
