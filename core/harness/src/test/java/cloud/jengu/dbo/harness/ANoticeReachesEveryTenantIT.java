package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider's notice of a system update, written once in its management
 * tenant, reaching every tenant; each acknowledging it at its own door; and
 * the provider reading who did.
 *
 * <p>A world of its own because the deployment declares a fleet step, and a
 * fleet step is declared for the whole deployment: one declared in the shared
 * world would open work in every shared tenant. Its tenants are named for this
 * run, because a tenant's database is named by its code and a database server
 * may outlive the run that made it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ANoticeReachesEveryTenantIT {

    private static final String RUN = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private static final String PROVIDER = "provider-" + RUN;
    private static final String NORTH = "north-" + RUN;
    private static final String SOUTH = "south-" + RUN;
    private static final String LATE = "late-" + RUN;
    private static final String REFUSING = "refusing-" + RUN;
    private static final String ACKNOWLEDGE = "fleet.update.acknowledge";
    private static final String OPS = "the-operators-token";

    private final HttpClient http = HttpClient.newHttpClient();
    private PostgreSQLContainer<?> postgres;
    private LocalDatabasePerTenantProvisioner databases;
    private TenantRuntimeManager manager;
    private Path dir;
    private Path management;
    private String notice;

    @BeforeAll
    void aProviderAndTwoTenants() throws Exception {
        postgres = SharedPostgres.get();
        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ANoticeReachesEveryTenantIT"),
                postgres.getUsername(), postgres.getPassword());
        dir = Files.createTempDirectory("dbo-notice");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.serveRuntimeState(OPS);
        management = Files.createTempDirectory("dbo-notice-management")
                .resolve(PROVIDER + ".json");
        Files.writeString(management, provider(""));
        manager.manages(management);
        for (String tenant : List.of(NORTH, SOUTH)) {
            Files.writeString(dir.resolve(tenant + ".json"), tenantTakingNotices(tenant, ""));
        }
        UntilServed.scan(manager, NORTH, SOUTH);
        manager.authority(PROVIDER).ensureClient("desk", "desk-secret",
                List.of("system/*.read", "system/*.write"));
        for (String tenant : List.of(NORTH, SOUTH)) {
            manager.authority(tenant).ensureClient("admin", "admin-secret", List.of("work"));
        }
    }

    /** The provider's declaration, with any further fleet steps after the acknowledgement. */
    private static String provider(String more) {
        return """
                {"code":"%s","face":"r5","types":[
                  {"name":"Communication","identity":"internal","handling":"operational"}],
                 "fleetSteps":[{"code":"%s","slots":{"notice":"Reference(Communication)"},
                   "required":true,"lasts":true,
                   "starts":{"change":"Communication?category=urn:dbo:notice|system-update",
                             "keep":"P365D","where":"everywhere"}}%s]}"""
                .formatted(PROVIDER, ACKNOWLEDGE, more);
    }

    private static String tenantTakingNotices(String code, String more) {
        return """
                {"code":"%s","face":"r5"%s,
                 "dependencies":[{"name":"%s","types":["Communication"]}],
                 "types":[{"name":"Communication","identity":"internal",
                           "handling":"replicated"}]}""".formatted(code, more, PROVIDER);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        SuiteDatabases.retire(databases);
    }

    @Test
    @Order(1)
    @DisplayName("one notice the provider writes opens one acknowledgement in every tenant "
            + "that takes its notices, keyed by the notice and held by nobody")
    @Proving({DboPromises.PROC_A_FLEET_STEP_MAY_LAST_AND_START_ITSELF_IN_EVERY_TENANT,
            DboPromises.PROC_WORK_THAT_LASTS_IS_OPENED_FOR_ITS_REQUEST})
    void aNoticeOpensAnAcknowledgementEverywhere() throws Exception {
        HttpResponse<String> written = http.send(HttpRequest.newBuilder(URI.create(
                                base(PROVIDER) + "/fhir/Communication"))
                        .header("Authorization", "Bearer " + token(PROVIDER, "desk"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Communication","status":"completed",
                                 "category":[{"coding":[{"system":"urn:dbo:notice",
                                   "code":"system-update"}]}],
                                 "payload":[{"contentCodeableConcept":{"text":"Upgrade on 20 October, 22:00 UTC"}}]}
                                """)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, written.statusCode(), written.body());
        notice = "Communication/" + field(written.body(), "id");

        for (String tenant : List.of(NORTH, SOUTH)) {
            Run opened = acknowledgementIn(tenant);
            assertTrue(opened.lasting(), opened.toString());
            assertEquals("received", opened.status().wire(), opened.toString());
        }
        // Read again, nothing more is opened: one per notice.
        manager.syncRound();
        assertEquals("received", acknowledgementIn(NORTH).status().wire());
    }

    @Test
    @Order(2)
    @DisplayName("a tenant cannot decline an acknowledgement the deployment requires")
    @Proving(DboPromises.PROC_A_FLEET_STEP_MAY_LAST_AND_START_ITSELF_IN_EVERY_TENANT)
    void aRequiredAcknowledgementCannotBeDeclined() throws Exception {
        Files.writeString(dir.resolve(REFUSING + ".json"), tenantTakingNotices(REFUSING,
                ",\"declines\":[\"" + ACKNOWLEDGE + "\"]"));
        manager.scanOnce();
        manager.scanOnce();
        assertTrue(manager.runtime(REFUSING).isEmpty(),
                "a tenant declining a required acknowledgement came up");
        assertTrue(String.valueOf(manager.troubles()).contains(ACKNOWLEDGE),
                "the refusal does not name the step: " + manager.troubles());
        Files.delete(dir.resolve(REFUSING + ".json"));
        manager.scanOnce();
    }

    @Test
    @Order(3)
    @DisplayName("a tenant's admin acknowledges at the tenant's own door, and the provider "
            + "reads, tenant by tenant, who acknowledged, when and why, and who has not")
    @Proving({DboPromises.PROC_THE_PROVIDER_SEES_WHO_ACKNOWLEDGED,
            DboPromises.PROC_WORK_THAT_LASTS_IS_MOVED_BY_ITS_PARTS})
    void theProviderSeesWhoAcknowledged() throws Exception {
        Run north = acknowledgementIn(NORTH);
        assertEquals(200, say(NORTH, north.id(), "accept",
                "{\"because\":\"read; window agreed\"}").statusCode());
        assertEquals(200, say(NORTH, north.id(), "end",
                "{\"status\":\"completed\",\"because\":\"prepared\"}").statusCode());

        String view = fleetView();
        String northRow = rowOf(view, NORTH);
        assertTrue(northRow.contains("\"status\":\"completed\""), northRow);
        assertTrue(northRow.contains("\"act\":\"accepted\"")
                        && northRow.contains("\"by\":\"admin\"")
                        && northRow.contains("read; window agreed"),
                "the provider cannot see who acknowledged: " + northRow);
        assertTrue(rowOf(view, SOUTH).contains("\"status\":\"received\""),
                "a tenant that has not acknowledged is not shown as received: " + view);
        assertTrue(!view.contains("Upgrade on 20 October"),
                "the provider's view carried the tenants' records: " + view);
    }

    @Test
    @Order(4)
    @DisplayName("a tenant joining after the notice is asked to acknowledge it too")
    @Proving(DboPromises.PROC_A_FLEET_STEP_MAY_LAST_AND_START_ITSELF_IN_EVERY_TENANT)
    void aTenantJoiningLaterIsAskedToo() throws Exception {
        Files.writeString(dir.resolve(LATE + ".json"), tenantTakingNotices(LATE, ""));
        UntilServed.scan(manager, LATE);
        assertEquals("received", acknowledgementIn(LATE).status().wire());
        assertTrue(rowOf(fleetView(), LATE).contains("\"status\":\"received\""));
    }

    @Test
    @Order(5)
    @DisplayName("a step the provider declares once its tenants serve reaches them where they "
            + "stand: the next notice of its kind opens its work in each, without a rebuild")
    @Proving(DboPromises.PROC_A_FLEET_STEP_MAY_LAST_AND_START_ITSELF_IN_EVERY_TENANT)
    void aStepDeclaredLaterReachesServingTenants() throws Exception {
        Files.writeString(management, provider("""
                ,{"code":"fleet.advisory.read","slots":{"notice":"Reference(Communication)"},
                  "required":true,"lasts":true,
                  "starts":{"change":"Communication?category=urn:dbo:notice|advisory",
                            "keep":"P365D","where":"everywhere"}}"""));
        manager.manages(management);

        HttpResponse<String> written = http.send(HttpRequest.newBuilder(URI.create(
                                base(PROVIDER) + "/fhir/Communication"))
                        .header("Authorization", "Bearer " + token(PROVIDER, "desk"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Communication","status":"completed",
                                 "category":[{"coding":[{"system":"urn:dbo:notice",
                                   "code":"advisory"}]}]}""")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, written.statusCode(), written.body());
        String advisory = "Communication/" + field(written.body(), "id");
        for (String tenant : List.of(NORTH, SOUTH)) {
            assertEquals("received", openedIn(tenant, "fleet.advisory.read/" + advisory)
                    .status().wire());
        }
    }

    // ── helpers ──

    /** The acknowledgement a tenant opened for the notice, once its copy arrived. */
    private Run acknowledgementIn(String tenant) throws Exception {
        return openedIn(tenant, ACKNOWLEDGE + "/" + notice);
    }

    private Run openedIn(String tenant, String key) throws Exception {
        Runs runs = new Runs(manager.runtime(tenant).orElseThrow().engine());
        long giveUp = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Optional<Run> found = runs.byKey(key);
        while (found.isEmpty() && System.nanoTime() < giveUp) {
            manager.syncRound();
            Thread.sleep(500);
            found = runs.byKey(key);
        }
        return found.orElseThrow(() -> new AssertionError(tenant + " opened no " + key));
    }

    private String fleetView() throws Exception {
        HttpResponse<String> view = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + manager.port() + "/runtime/fleet/steps/"
                                        + ACKNOWLEDGE + "?notice="
                                        + URLEncoder.encode(notice, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + OPS).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, view.statusCode(), view.body());
        return view.body();
    }

    private static String rowOf(String view, String tenant) {
        int at = view.indexOf("{\"tenant\":\"" + tenant + "\"");
        assertTrue(at >= 0, tenant + " is not in the provider's view: " + view);
        int next = view.indexOf("{\"tenant\":", at + 1);
        return view.substring(at, next < 0 ? view.length() : next);
    }

    private HttpResponse<String> say(String tenant, String id, String act, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(tenant) + "/run/" + id + "/"
                                + act))
                        .header("Authorization", "Bearer " + token(tenant, "admin"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String token(String tenant, String client) throws Exception {
        String body = http.send(HttpRequest.newBuilder(URI.create(base(tenant) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=" + client
                                        + "&client_secret=" + client + "-secret")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return field(body, "access_token");
    }

    private String base(String tenant) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + tenant;
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }
}
