package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.runner.transport.Place;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A clinic served in the cloud and, as a second place of itself, on a site:
 * two nodes, the site's only connection to the cloud a lane held with a
 * credential that holds a place and nothing else.
 *
 * <p>The cloud runs the face root, a county the clinic takes its
 * organisations from, and the clinic. The site runs the clinic alone, from
 * the same declaration, and none of the upstreams it names.
 *
 * <p><b>A world of its own, and bring-up is why.</b> What is asserted is how
 * a tenant comes up — from where, with what, and with the link down — and
 * that is read off nodes while they bring tenants up. A site is also a
 * second node, which the sample world is not.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ASecondPlaceComesUpFromItsOriginIT {

    private static final String ROOT = "saar-r4";
    private static final String COUNTY = "vallamaa";
    private static final String CLINIC = "jaam";

    private static final String CLINIC_DECLARATION = """
            {"code":"%s","face":"r4","audit":{"level":"none"},
             "dependencies":[{"name":"%s","face":true,
                              "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]},
                             {"name":"%s","types":["Organization","CodeSystem"]}],
             "types":[
              {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
              {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
              {"name":"ValueSet","identity":"canonical","handling":"replicated"},
              {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
              {"name":"Organization","identity":"internal","handling":"replicated"},
              {"name":"Observation","identity":"internal","handling":"operational"},
              {"name":"Patient","identity":"internal","handling":"operational"}]}"""
            .formatted(CLINIC, ROOT, COUNTY);

    /** The face root, declared alike on both nodes: the site reads its face from its own. */
    private static final String ROOT_DECLARATION = """
            {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
             "types":[
              {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
              {"name":"SearchParameter","identity":"canonical","handling":"operational"},
              {"name":"ValueSet","identity":"canonical","handling":"operational"},
              {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
            .formatted(ROOT);

    static final HttpClient http = HttpClient.newHttpClient();
    static Path cloudDir;
    static Path siteDir;
    static LocalDatabasePerTenantProvisioner cloudDatabases;
    static LocalDatabasePerTenantProvisioner siteDatabases;
    /**
     * The site's own database server. A tenant's database is named by its
     * code alone, so two places of one tenant on one server would be one
     * database — and a site never shares the cloud's server anyway.
     */
    static PostgreSQLContainer<?> siteServer;
    static TenantRuntimeManager cloud;
    static TenantRuntimeManager site;
    static HttpLane lane;

    @BeforeAll
    void up() throws Exception {
        PostgreSQLContainer<?> postgres = SharedPostgres.get();
        cloudDir = Files.createTempDirectory("dbo-place-cloud");
        siteDir = Files.createTempDirectory("dbo-place-site");
        cloudDatabases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ASecondPlaceComesUpFromItsOriginIT_cloud"),
                postgres.getUsername(), postgres.getPassword());
        siteServer = new PostgreSQLContainer<>("postgres:17-alpine");
        siteServer.start();
        siteDatabases = new LocalDatabasePerTenantProvisioner(siteServer.getJdbcUrl(),
                siteServer.getUsername(), siteServer.getPassword());
        cloud = node(cloudDir, cloudDatabases);

        Files.writeString(cloudDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        Files.writeString(cloudDir.resolve(COUNTY + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[{"name":"Organization","identity":"internal","handling":"operational"},
                          {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(COUNTY));
        Files.writeString(cloudDir.resolve(CLINIC + ".json"), CLINIC_DECLARATION);
        UntilServed.scan(cloud, ROOT, COUNTY);
        UntilServed.scan(cloud, CLINIC);

        // What the clinic takes from the county, and what it authors itself.
        assertEquals(201, post(cloud, COUNTY, "Organization",
                "{\"resourceType\":\"Organization\",\"name\":\"Tartu maakonna haigla\"}"));
        assertEquals(201, post(cloud, CLINIC, "Patient",
                "{\"resourceType\":\"Patient\",\"gender\":\"female\"}"));
        // A definition that is not the face, behind it in the clinic's feed:
        // the clinic took its face first, so this comes after every face row.
        assertEquals(201, post(cloud, COUNTY, "CodeSystem", codeSystem("visit-kinds")));
        cloud.syncRound();

        cloud.authority(CLINIC).ensureClient("site", "site-secret", List.of("place"));
        // Held, and fetched again before it expires, as a host's client does:
        // a secret is slow to check on purpose, and asking on every read would
        // measure that rather than the place.
        lane = HttpLane.to(URI.create(base(cloud, CLINIC) + "/work"),
                new HeldToken(() -> clientToken(cloud, CLINIC, "site", "site-secret")), CLINIC,
                "site",
                new Executor("site", "1", "example.site", Scope.BASELINE));

        site = node(siteDir, siteDatabases);
        site.servesAPlaceOf(CLINIC);
        site.readsItsOriginThrough(CLINIC, connected());
        Files.writeString(siteDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        UntilServed.scan(site, ROOT);
        Files.writeString(siteDir.resolve(CLINIC + ".json"), CLINIC_DECLARATION);
        long began = System.currentTimeMillis();
        UntilServed.scan(site, CLINIC);
        System.out.println("MEASURED a place's first bring-up from its origin: "
                + (System.currentTimeMillis() - began) + "ms");
    }

    @AfterAll
    void down() {
        for (TenantRuntimeManager node : new TenantRuntimeManager[] {site, cloud}) {
            if (node != null) {
                node.close();
            }
        }
        if (cloudDatabases != null) {
            SuiteDatabases.retire(cloudDatabases);
        }
        if (siteServer != null) {
            siteServer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("the site's clinic came up from the cloud's clinic alone: it validates against "
            + "the face, holds the county's organisation, and holds none of the clinic's own "
            + "patients")
    @Proving({DboPromises.SYNC_A_PLACE_COMES_UP_FROM_ITS_ORIGIN,
            DboPromises.SYNC_A_PLACE_TAKES_ITS_FACE_FROM_A_ROOT_BESIDE_IT})
    void aPlaceComesUpFromItsOrigin() throws Exception {
        assertTrue(read(site, CLINIC, "Organization").contains("Tartu maakonna haigla"),
                "the county's organisation did not reach the site");
        String aPatient = "\"resourceType\":\"Patient\"";
        assertTrue(read(cloud, CLINIC, "Patient").contains(aPatient),
                "the cloud's clinic does not hold the patient this asserts the site lacks");
        assertTrue(!read(site, CLINIC, "Patient").contains(aPatient),
                "a patient the clinic authored reached the site by type");
        assertEquals(422, post(site, CLINIC, "Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"nonesuch\"}"),
                "the site's clinic validates against nothing");
        assertEquals(201, post(site, CLINIC, "Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}}"""),
                "the site's clinic refused a write of its own");
    }

    @Test
    @Order(2)
    @DisplayName("what arrived from the origin is the site's to serve and not to change")
    @Proving({DboPromises.SYNC_A_PLACE_COMES_UP_FROM_ITS_ORIGIN, DboPromises.SYNC_PROVENANCE_COPIES})
    void whatArrivedIsNotTheSitesToChange() throws Exception {
        assertNotEquals(201, post(site, CLINIC, "Organization",
                "{\"resourceType\":\"Organization\",\"name\":\"written on the site\"}"),
                "the site wrote a type its origin replicates to it");
    }

    @Test
    @Order(3)
    @DisplayName("the site holds the definitions behind its origin's face, and one published "
            + "upstream later reaches it on a later round")
    @Proving(DboPromises.SYNC_A_PLACE_TAKES_ITS_FACE_FROM_A_ROOT_BESIDE_IT)
    void aPlaceKeepsItsDefinitionsFromItsOrigin() throws Exception {
        assertTrue(holds(cloud, "visit-kinds"),
                "the cloud's clinic does not hold the definition this asserts the site took");
        assertTrue(holds(site, "visit-kinds"),
                "the site came up without the definitions behind its origin's face");

        assertEquals(201, post(cloud, COUNTY, "CodeSystem", codeSystem("referral-kinds")));
        cloud.syncRound();
        assertTrue(holds(cloud, "referral-kinds"),
                "the cloud's clinic does not hold the definition this asserts the site takes");
        site.syncRound();

        assertTrue(holds(site, "referral-kinds"),
                "a definition published upstream after the site came up never reached it");
    }

    @Test
    @Order(4)
    @DisplayName("with its origin away the site serves what it holds, and carries on from where "
            + "it acknowledged once it can read again")
    @Proving(DboPromises.SYNC_A_PLACE_SERVES_WHAT_IT_HOLDS_WHILE_ITS_ORIGIN_IS_AWAY)
    void aPlaceServesWhileItsOriginIsAway() throws Exception {
        site.readsItsOriginThrough(CLINIC, null);
        assertEquals(201, post(cloud, COUNTY, "Organization",
                "{\"resourceType\":\"Organization\",\"name\":\"Elva perearstikeskus\"}"));
        cloud.syncRound();
        site.syncRound();

        String whileAway = read(site, CLINIC, "Organization");
        assertTrue(whileAway.contains("Tartu maakonna haigla"), "the site stopped serving");
        assertTrue(!whileAway.contains("Elva perearstikeskus"),
                "the site read its origin with synchronisation off");

        site.readsItsOriginThrough(CLINIC, connected());
        site.syncRound();

        assertTrue(read(site, CLINIC, "Organization").contains("Elva perearstikeskus"),
                "the site did not catch up once it could read its origin again");
    }

    @Test
    @Order(5)
    @DisplayName("a site restarted with its origin away comes up from what it holds")
    @Proving(DboPromises.SYNC_A_PLACE_SERVES_WHAT_IT_HOLDS_WHILE_ITS_ORIGIN_IS_AWAY)
    void aPlaceRestartedOfflineServesWhatItHolds() throws Exception {
        site.close();
        site = node(siteDir, siteDatabases);
        site.servesAPlaceOf(CLINIC);

        UntilServed.scan(site, ROOT, CLINIC);

        assertTrue(read(site, CLINIC, "Organization").contains("Elva perearstikeskus"),
                "a site restarted offline did not serve what it held");
        assertEquals(422, post(site, CLINIC, "Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"nonesuch\"}"),
                "a site restarted offline came up without its face");
    }

    /** A token fetched once and again at half its life, never on every call. */
    private static final class HeldToken implements java.util.function.Supplier<String> {

        private final java.util.function.Supplier<String> fetch;
        private String token;
        private long fetchedAt;

        HeldToken(java.util.function.Supplier<String> fetch) {
            this.fetch = fetch;
        }

        @Override
        public synchronized String get() {
            long now = System.currentTimeMillis();
            if (token == null || now - fetchedAt
                    > TenantAuthority.TOKEN_TTL_SECONDS * 1000 / 2) {
                token = fetch.get();
                fetchedAt = now;
            }
            return token;
        }
    }

    /** Each node's key, kept across its restarts as a deployment keeps it. */
    private static final java.util.Map<Path, byte[]> KEYS = new java.util.HashMap<>();

    private static TenantRuntimeManager node(Path dir, LocalDatabasePerTenantProvisioner databases) {
        byte[] kek = KEYS.computeIfAbsent(dir, ignored -> {
            byte[] fresh = new byte[32];
            new java.security.SecureRandom().nextBytes(fresh);
            return fresh;
        });
        return new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
    }

    private static TenantRuntimeManager.OriginFeeds connected() {
        return new TenantRuntimeManager.OriginFeeds(lane.placeFeed(Place.RECORDS),
                lane.placeFeed(Place.DEFINITIONS), lane.definitionsWithoutTheFace());
    }

    private static String canonical(String name) {
        return "http://example.org/vallamaa/" + name;
    }

    /**
     * Whether the clinic on that node holds the county's code system by that
     * name. Searched by its canonical, because the face is thousands of code
     * systems and a first page holds none of the county's; and an entry is
     * asked for, because the search's own link names the canonical anyway.
     */
    private static boolean holds(TenantRuntimeManager node, String name) throws Exception {
        return read(node, CLINIC, "CodeSystem?url=" + canonical(name))
                .contains("\"resourceType\":\"CodeSystem\"");
    }

    private static String codeSystem(String name) {
        return """
                {"resourceType":"CodeSystem","url":"%s","name":"%s","status":"active",
                 "content":"complete","concept":[{"code":"a","display":"A"}]}"""
                .formatted(canonical(name), name.replace("-", ""));
    }

    private static int post(TenantRuntimeManager node, String code, String type, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(node, code) + "/fhir/" + type))
                        .header("Authorization", "Bearer " + writer(node, code))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String read(TenantRuntimeManager node, String code, String type)
            throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                        URI.create(base(node, code) + "/fhir/" + type))
                        .header("Authorization", "Bearer " + writer(node, code)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    private static String writer(TenantRuntimeManager node, String code) throws Exception {
        node.authority(code).ensureClient("writer", "writer-secret",
                List.of("system/*.read", "system/*.write"));
        return clientToken(node, code, "writer", "writer-secret");
    }

    private static String clientToken(TenantRuntimeManager node, String code, String client,
            String secret) {
        try {
            return Extracted.tokenIn(http.send(HttpRequest.newBuilder(
                            URI.create(base(node, code) + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "grant_type=client_credentials&client_id=" + client
                                            + "&client_secret=" + secret)).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static String base(TenantRuntimeManager node, String code) {
        return "http://127.0.0.1:" + node.port() + "/t/" + code;
    }
}
