package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.asking.Asking;
import cloud.jengu.dbo.asking.Ongoing;
import cloud.jengu.dbo.work.RunInitiator;
import cloud.jengu.dbo.work.Status;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.core.api.feed.FeedSelection;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.runner.transport.Place;
import cloud.jengu.dbo.tenant.FleetRegister;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.TenantState;
import cloud.jengu.dbo.tenant.api.RegisterStanding;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.sync.Lanes;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Runs;
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

    /** What the clinic names its benches by, at home and on the site alike. */
    private static final String BENCHES = "urn:dbo-test:bench";
    /** What the clinic names its analysers by. */
    private static final String ANALYSERS = "urn:dbo-test:analyser";

    /**
     * The clinic as declared, on both nodes alike: taking its owner's
     * configuration, as the declaration a site is handed says it does.
     */
    private static final String CLINIC_DECLARATION = """
            {"code":"%s","face":"r4","audit":{"level":"writes"},
             "configuration":{"ref":"main"},
             "content":{"analyser-message":{"keepAtPlace":"P1D","keepAtTenant":"P30D"}},
             "dependencies":[{"name":"%s","face":true,
                              "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]},
                             {"name":"%s","types":["Organization","CodeSystem"]}],
             "steps":[{"code":"%s","slots":{"specimen":"Observation"},
                       "writes":["Observation","Device","Basic"]},
                      {"code":"jaam.lab.order","slots":{"order":"Reference(Observation)"},
                       "lasts":true},
                      {"code":"jaam.lab.calibrate",
                       "starts":{"every":"0 * * * * *","missed":"skip","keep":"P1D",
                                 "where":"places"}}],
             "types":[
              {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
              {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
              {"name":"ValueSet","identity":"canonical","handling":"replicated"},
              {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
              {"name":"Organization","identity":"internal","handling":"replicated"},
              {"name":"Observation","identity":"internal","handling":"operational"},
              {"name":"Patient","identity":"internal","handling":"operational"},
              {"name":"Location","identity":"identifier","systems":["%s"],
               "handling":"operational"},
              {"name":"Device","identity":"internal","handling":"operational"},
              {"name":"Basic","identity":"internal","handling":"projected-config"}]}"""
            .formatted(CLINIC, ROOT, COUNTY, "jaam.lab.record", BENCHES);

    /**
     * The clinic as its origin declares it on a deployment with a provider:
     * its orders handed to the provider. The cloud here has none and would
     * refuse it, so only the site is handed this one.
     */
    private static final String HANDING_DECLARATION = CLINIC_DECLARATION.replace(
            "\"lasts\":true}", "\"lasts\":true,\"handsTo\":\"provider\"}");

    /** The step the site performs on its own runners: a result captured from an instrument. */
    private static final String RECORD = "jaam.lab.record";

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
                {"code":"%s","face":"r4","zoneRoot":true,"audit":{"level":"none"},
                 "types":[{"name":"Organization","identity":"internal","handling":"operational"},
                          {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(COUNTY));
        Files.writeString(cloudDir.resolve(CLINIC + ".json"), CLINIC_DECLARATION);
        UntilServed.scan(cloud, ROOT, COUNTY);
        UntilServed.scan(cloud, CLINIC);
        assertEquals(TenantState.State.SERVING, stateOf(cloud, CLINIC),
                "the cloud's clinic did not apply its owner's configuration: "
                        + cloud.troubles().get(CLINIC));

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
        Files.writeString(siteDir.resolve(CLINIC + ".json"), HANDING_DECLARATION);
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
    @Order(0)
    @DisplayName("the site's clinic, declared as taking its owner's configuration, came up "
            + "serving and not degraded, with nothing of that configuration to apply there")
    @Proving(DboPromises.SYNC_A_PLACE_LEAVES_ITS_OWNERS_CONFIGURATION_TO_ITS_ORIGIN)
    void aPlaceLeavesItsOwnersConfigurationToItsOrigin() {
        assertEquals(TenantState.State.SERVING, stateOf(site, CLINIC),
                "the site's clinic is not serving as itself: " + site.troubles().get(CLINIC));
    }

    @Test
    @Order(0)
    @DisplayName("the site's clinic, whose declaration hands its orders to the provider, came up "
            + "serving them as its own, and asking it to hand one over is answered as not here")
    @Proving(DboPromises.SYNC_A_PLACE_LEAVES_HANDING_WORK_OVER_TO_ITS_ORIGIN)
    void aPlaceLeavesHandingWorkOverToItsOrigin() throws Exception {
        assertTrue(HANDING_DECLARATION.contains("\"handsTo\":\"provider\""),
                "the site was not handed a declaration that hands work over");
        assertEquals(TenantState.State.SERVING, stateOf(site, CLINIC),
                "the site's clinic is not serving: " + site.troubles().get(CLINIC));
        HttpResponse<String> written = http.send(HttpRequest.newBuilder(
                        URI.create(base(site, CLINIC) + "/fhir/Observation"))
                        .header("Authorization", "Bearer " + writer(site, CLINIC))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(OBSERVATION)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, written.statusCode(), written.body());
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"")
                .matcher(written.body());
        assertTrue(id.find(), written.body());
        site.authority(CLINIC).ensureClient("orders", "orders-secret", List.of("work"));
        String orders = clientToken(site, CLINIC, "orders", "orders-secret");

        HttpResponse<String> handed = order(id.group(1), ",\"for\":{\"provider\":true}",
                orders);
        assertEquals(409, handed.statusCode(), handed.body());
        assertTrue(handed.body().contains("not_here"),
                "asking a place to hand work over was not answered as not here: "
                        + handed.body());
        HttpResponse<String> kept = order(id.group(1), "", orders);
        assertEquals(201, kept.statusCode(),
                "the site's clinic does not open its orders as its own: " + kept.body());
    }

    /** An order opened at the site's clinic over an observation it holds. */
    private static HttpResponse<String> order(String observation, String more, String token)
            throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create(base(site, CLINIC) + "/step/jaam.lab.order"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{\"order\":"
                                + "\"Observation/" + observation + "\"}" + more + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
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
    @DisplayName("what the site does is in its tenant's trail in the cloud, as the site recorded "
            + "it and named as the site's")
    @Proving(DboPromises.SYNC_A_PLACE_HANDS_ITS_TRAIL_TO_ITS_TENANT)
    void whatTheSiteDoesReachesTheTenantsTrail() throws Exception {
        assertEquals(201, post(site, CLINIC, "Observation", OBSERVATION));

        site.syncRound();

        List<com.fasterxml.jackson.databind.JsonNode> here = writesOfObservations(site, "");
        List<com.fasterxml.jackson.databind.JsonNode> there =
                writesOfObservations(cloud, "site=site");
        assertTrue(!here.isEmpty(),
                "the site recorded nothing of its own writes, so there was nothing to hand");
        assertEquals(asRecorded(here), asRecorded(there),
                "the cloud does not hold the site's writes as the site recorded them");
        for (com.fasterxml.jackson.databind.JsonNode event : there) {
            assertEquals("site", event.path("source").path("observer").path("identifier")
                    .path("value").asText(), "an entry from the site is not named as the site's");
        }
    }

    @Test
    @Order(5)
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
    @Order(6)
    @DisplayName("a site that was cut off hands what it recorded meanwhile once it is back, all of "
            + "it and each entry once, even when it hands its whole trail again")
    @Proving({DboPromises.SYNC_A_PLACE_HANDS_ITS_TRAIL_TO_ITS_TENANT,
            DboPromises.PROC_AUDIT_REPLICATES_AS_RECORDED})
    void aSiteCatchesUpWithoutGapsOrDuplicates() throws Exception {
        int before = writesOfObservations(cloud, "site=site").size();
        site.readsItsOriginThrough(CLINIC, null);
        for (int write = 0; write < 3; write++) {
            assertEquals(201, post(site, CLINIC, "Observation", OBSERVATION));
        }
        site.syncRound();
        assertEquals(before, writesOfObservations(cloud, "site=site").size(),
                "the site handed its trail while it was cut off");

        site.readsItsOriginThrough(CLINIC, connected());
        site.syncRound();

        List<com.fasterxml.jackson.databind.JsonNode> caughtUp =
                writesOfObservations(cloud, "site=site");
        assertEquals(before + 3, caughtUp.size(),
                "what the site recorded while cut off did not all arrive, or arrived twice");
        assertEquals(asRecorded(writesOfObservations(site, "")), asRecorded(caughtUp),
                "the cloud's copy of the site's trail is not the site's trail");

        // The tenant's position now names a trail that is not the site's, as
        // it would after the site was rebuilt: the site hands everything again.
        lane.origin().trail().handed("not-the-site's-trail", List.of(), "0");
        site.syncRound();

        assertEquals(before + 3, writesOfObservations(cloud, "site=site").size(),
                "an entry the site handed again was recorded twice");
    }

    @Test
    @Order(7)
    @DisplayName("a place reads no trail: not through the tenant's FHIR door, and not on what the "
            + "tenant replicates to it")
    @Proving(DboPromises.SYNC_A_PLACE_HANDS_ITS_TRAIL_TO_ITS_TENANT)
    void aPlaceReadsNoTrail() throws Exception {
        HttpResponse<String> asked = http.send(HttpRequest.newBuilder(
                        URI.create(base(cloud, CLINIC) + "/fhir/AuditEvent"))
                        .header("Authorization", "Bearer "
                                + clientToken(cloud, CLINIC, "site", "site-secret"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(asked.statusCode() == 401 || asked.statusCode() == 403,
                "a place read its tenant's trail: " + asked.statusCode());

        FeedChunk<FeedItem> offered =
                lane.placeFeed(Place.RECORDS).readFor("site", 500,
                        FeedSelection.ofTypes(
                                java.util.Set.of("AuditEntry")));
        assertTrue(offered.items().stream().noneMatch(item -> "AuditEntry".equals(
                        item.typeName())),
                "a place was offered its tenant's trail on what the tenant replicates");
    }

    @Test
    @Order(8)
    @DisplayName("what a run on the site wrote is held by its tenant within a sync round, filed "
            + "under the site, while what the site wrote outside any run stays on the site")
    @Proving(DboPromises.SYNC_A_PLACE_HANDS_ITS_WORK_TO_ITS_TENANT)
    void whatASitesRunWroteReachesItsTenant() throws Exception {
        performedOnTheSite("hba1c-on-the-site");
        assertEquals(201, post(site, CLINIC, "Observation", """
                {"resourceType":"Observation","status":"final","code":{"text":"kept-on-the-site"}}"""));
        site.syncRound();

        String atTheTenant = read(cloud, CLINIC, "Observation");
        assertTrue(atTheTenant.contains("hba1c-on-the-site")
                        && atTheTenant.contains("urn:dbo:upstream:site"),
                "what the site's run wrote is not at the tenant, filed under the site: "
                        + atTheTenant);
        assertTrue(!atTheTenant.contains("kept-on-the-site"),
                "what the site wrote outside any run reached the tenant");
        assertTrue(new Runs(cloud.runtime(CLINIC).orElseThrow().engine())
                        .byKey("site" + Lanes.MIRROR_SEPARATOR + RECORD
                                + "/hba1c-on-the-site").isPresent(),
                "the site's run is not mirrored beside the tenant's own");
    }

    @Test
    @Order(9)
    @DisplayName("what a site's runs wrote while it was cut off reaches its tenant once it is "
            + "back, and once")
    @Proving(DboPromises.SYNC_A_PLACE_HANDS_ITS_WORK_TO_ITS_TENANT)
    void whatASiteDidCutOffArrivesOnceItIsBack() throws Exception {
        site.readsItsOriginThrough(CLINIC, null);
        performedOnTheSite("glucose-cut-off");
        site.syncRound();
        assertTrue(!read(cloud, CLINIC, "Observation").contains("glucose-cut-off"),
                "the site handed its work while it was cut off");

        site.readsItsOriginThrough(CLINIC, connected());
        site.syncRound();
        site.syncRound();
        assertEquals(1, times("glucose-cut-off", read(cloud, CLINIC, "Observation")),
                "what the site did while cut off did not arrive once it was back, or arrived "
                        + "twice");
    }

    @Test
    @Order(10)
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

    @Test
    @Order(12)
    @DisplayName("a step declared to start on the clinic's places starts on the site with its "
            + "origin away, under the site's own system run, and never in the cloud")
    @Proving(DboPromises.PROC_A_STEP_STARTS_WHERE_IT_IS_DECLARED_TO)
    void aSiteStartsItsOwnStepsWithItsOriginAway() throws Exception {
        site.readsItsOriginThrough(CLINIC, null);
        try {
            assertEquals(java.util.Map.of(CALIBRATE, "0 * * * * *"),
                    CLOCKS.get(siteDir).rules.get(CLINIC));
            assertTrue(CLOCKS.get(cloudDir).rules.get(CLINIC) == null,
                    "the cloud was given a step that starts on its places: "
                            + CLOCKS.get(cloudDir).rules);

            java.time.Instant tick = java.time.Instant.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            calibrated = tick;
            CLOCKS.get(siteDir).opened.get(CLINIC).due(CALIBRATE, tick);

            site.authority(CLINIC).ensureClient("bench", "bench-secret", List.of("work"));
            HttpResponse<String> steps = http.send(HttpRequest.newBuilder(
                                    URI.create(base(site, CLINIC) + "/steps"))
                            .header("Authorization", "Bearer "
                                    + clientToken(site, CLINIC, "bench", "bench-secret"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, steps.statusCode(), steps.body());
            assertTrue(steps.body().contains("\"lastRun\":\"" + CALIBRATE + "/tick/" + tick
                    + "\""), "the site started nothing with its origin away: " + steps.body());
        } finally {
            site.readsItsOriginThrough(CLINIC, connected());
        }
    }

    /** The clinic's step that starts on its places, every minute. */
    private static final String CALIBRATE = "jaam.lab.calibrate";

    /** The tick the site started its calibration on, with its origin away. */
    private static java.time.Instant calibrated;

    @Test
    @Order(13)
    @DisplayName("a run the site performed is listed at its tenant as any other, with the site "
            + "it was performed at, when it was authored there, and the run it was started "
            + "under filed under the site too")
    @Proving(DboPromises.PROC_A_PLACES_RUN_IS_LISTED_AT_HOME_UNDER_ITS_PLACE)
    void aSitesRunIsListedAtHomeUnderTheSite() throws Exception {
        java.time.Instant handed = java.time.Instant.now();
        site.syncRound();

        String key = "site" + Lanes.MIRROR_SEPARATOR + CALIBRATE + "/tick/" + calibrated;
        List<Ongoing> listed = Asking
                .at(cloud.runtime(CLINIC).orElseThrow().engine()).work().key(key)
                .page(10, null).runs();
        assertEquals(1, listed.size(), "the site's run is not listed at its tenant: " + listed);
        Ongoing run = listed.get(0);
        assertEquals("site", run.place(), run.toString());
        assertEquals("site" + Lanes.MIRROR_SEPARATOR + "dbo.tenant.system/serve/" + CLINIC,
                run.parentKey(), "the run it was started under is not filed under the site: "
                        + run);
        assertTrue(run.authored() != null && run.authored().isBefore(handed),
                "the run says it was authored when it arrived, not when the site authored it: "
                        + run);
    }

    @Test
    @Order(11)
    @DisplayName("what the clinic's stream applies from the county is in the clinic's trail "
            + "under the stream's run")
    @Proving(DboPromises.PROC_MACHINERY_WRITES_UNDER_ITS_RUN)
    void whatAStreamAppliesNamesItsRun() throws Exception {
        com.fasterxml.jackson.databind.JsonNode bundle =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(read(cloud, CLINIC,
                        "AuditEvent?_count=1000&run=dbo.sync.stream/apply/" + COUNTY));
        java.util.List<String> about = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode entry : bundle.path("entry")) {
            about.add(entry.path("resource").path("entity").toString());
        }
        assertTrue(about.stream().anyMatch(entity -> entity.contains("Organization/")),
                "the organisations the stream applied are not in the trail under its run: "
                        + about);
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
                new TenantRuntimeManager.AuthorityConfig(kek, null))
                .stepScheduler(CLOCKS.computeIfAbsent(dir, ignored -> new HandCrankedScheduler()));
    }

    /** Each node's clock, turned by hand, kept across its restarts. */
    private static final java.util.Map<Path, HandCrankedScheduler> CLOCKS =
            new java.util.HashMap<>();

    private static final String OBSERVATION = """
            {"resourceType":"Observation","status":"final",
             "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}}""";

    /** The trail's entries for writes of observations on that node's clinic. */
    private static List<com.fasterxml.jackson.databind.JsonNode> writesOfObservations(
            TenantRuntimeManager node, String query) throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> found = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode bundle =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(read(node, CLINIC,
                        "AuditEvent?_count=1000" + (query.isEmpty() ? "" : "&" + query)));
        for (com.fasterxml.jackson.databind.JsonNode entry : bundle.path("entry")) {
            com.fasterxml.jackson.databind.JsonNode event = entry.path("resource");
            if (event.toString().contains("Observation/") && "C".equals(
                    event.path("action").asText())) {
                found.add(event);
            }
        }
        return found;
    }

    /** Each entry as it was recorded: when, by whom, about what. */
    private static java.util.Set<String> asRecorded(
            List<com.fasterxml.jackson.databind.JsonNode> events) {
        java.util.Set<String> said = new java.util.TreeSet<>();
        for (com.fasterxml.jackson.databind.JsonNode event : events) {
            said.add(event.path("recorded").asText() + " "
                    + event.path("agent").path(0).path("who").path("identifier").path("value")
                            .asText() + " " + event.path("entity").toString());
        }
        return said;
    }

    private static TenantRuntimeManager.OriginFeeds connected() {
        return new TenantRuntimeManager.OriginFeeds(lane.placeFeed(Place.RECORDS),
                lane.placeFeed(Place.DEFINITIONS), lane.definitionsWithoutTheFace(),
                lane.origin().trail(), lane.origin().work(), lane.origin().authorisation());
    }

    /**
     * A run of the site's own step, performed on the site: it takes a
     * specimen and its result writes the observation it found.
     */
    private static void performedOnTheSite(String found) {
        performedOnTheSite(found, Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"%s"}}"""
                .formatted(found)));
    }

    /** The same run, its result the writes given. */
    private static void performedOnTheSite(String found, Outcome.Write... writes) {
        Run ended = attemptedOnTheSite(found, writes);
        assertEquals(null, ended.refused(), "the site refused its own result: " + ended);
    }

    /** The same run, its result the writes given, ended however the site ended it. */
    private static Run attemptedOnTheSite(String found, Outcome.Write... writes) {
        StepDeclaration declared =
                StepDeclaration.of(RECORD, "1", "r4")
                        .taking("specimen", "Observation");
        Runs runs = new Runs(
                site.runtime(CLINIC).orElseThrow().engine());
        Run run = runs.filling(declared,
                RunKind.PIPELINE, found,
                java.util.Map.of("specimen", RunSlot.given(
                        "{\"resourceType\":\"Observation\",\"status\":\"registered\","
                                + "\"code\":{\"text\":\"specimen\"}}")));
        Executor analyser = new Executor("analyser", "1", "example.site", Scope.BASELINE);
        Lane analysing =
                site.fleetLane(CLINIC, RECORD, analyser).orElseThrow();
        analysing.declare(new Declarations.Declared("jaam.lab", "record",
                analyser.name(), analyser.version(), analyser.provider(), analyser.scope(),
                analyser.name()));
        Run held = analysing.claim(run, java.time.Duration.ofMinutes(5))
                .orElseThrow();
        return analysing.committed(held, null, List.of(writes));
    }

    @Test
    @Order(14)
    @DisplayName("a part a run on the site started there reads at the tenant as that run's part, "
            + "both filed under the site")
    @Proving(DboPromises.PROC_A_RUN_IS_STARTED_AS_A_PART_OF_ANOTHER)
    void aSitesTreeReadsTheSameAtHome() throws Exception {
        StepDeclaration declared = StepDeclaration.of(RECORD, "1", "r4")
                .taking("specimen", "Observation");
        Runs runs = new Runs(site.runtime(CLINIC).orElseThrow().engine());
        String specimen = "{\"resourceType\":\"Observation\",\"status\":\"registered\","
                + "\"code\":{\"text\":\"specimen\"}}";
        Run run = runs.filling(declared, RunKind.PIPELINE, "with-a-part",
                java.util.Map.of("specimen", RunSlot.given(specimen)));
        Executor analyser = new Executor("analyser", "1", "example.site", Scope.BASELINE);
        Lane analysing = site.fleetLane(CLINIC, RECORD, analyser).orElseThrow();
        analysing.declare(new Declarations.Declared("jaam.lab", "record",
                analyser.name(), analyser.version(), analyser.provider(), analyser.scope(),
                analyser.name()));
        Run held = analysing.claim(run, java.time.Duration.ofMinutes(5)).orElseThrow();
        Run ended = analysing.committed(held, null, List.of(Outcome.Write.create(
                        "{\"resourceType\":\"Observation\",\"status\":\"final\","
                                + "\"code\":{\"text\":\"with-a-part\"}}")),
                List.of(new Outcome.Part(RECORD, java.util.Map.of("specimen",
                        RunInitiator.Slot.object(specimen)),
                        "the-part", null)));
        assertEquals(null, ended.refused(), "the site refused its own result: " + ended);
        site.syncRound();

        Runs home = new Runs(cloud.runtime(CLINIC).orElseThrow().engine());
        Run parentAtHome = home.byKey("site" + Lanes.MIRROR_SEPARATOR + RECORD + "/with-a-part")
                .orElseThrow(() -> new AssertionError("the site's run did not reach its tenant"));
        List<String> parts;
        try (var asked = Asking.at(cloud.runtime(CLINIC).orElseThrow().engine()).work()
                .childrenOf(parentAtHome.id()).stream()) {
            parts = asked.map(Ongoing::key).toList();
        }
        assertEquals(List.of("site" + Lanes.MIRROR_SEPARATOR + RECORD + "/the-part"), parts,
                "the part the site started is not its run's part at the tenant");
    }

    @Test
    @Order(15)
    @DisplayName("work that lasts opened on the site reads the same at its tenant, filed under "
            + "the site, and is moved only where it is")
    @Proving(DboPromises.PROC_WORK_THAT_LASTS_IS_OPENED_FOR_ITS_REQUEST)
    void workThatLastsComesHomeUnderTheSite() throws Exception {
        StepDeclaration ordering = StepDeclaration.of("jaam.lab.order", "1", "r4")
                .taking("order", "Observation");
        Runs onTheSite = new Runs(site.runtime(CLINIC).orElseThrow().engine());
        Run opened = onTheSite.opening(ordering, "order", "Observation/the-order", null, null);
        onTheSite.accepted(opened, "the-bench", "taken on at the site");
        site.syncRound();

        Runs home = new Runs(cloud.runtime(CLINIC).orElseThrow().engine());
        Run atHome = home.byKey("site" + Lanes.MIRROR_SEPARATOR + "jaam.lab.order/Observation/"
                + "the-order").orElseThrow(() -> new AssertionError(
                        "the site's lasting work did not reach its tenant"));
        assertTrue(atHome.lasting(), atHome.toString());
        assertEquals("accepted", atHome.status().wire(), atHome.toString());
        assertEquals("site", atHome.place(), atHome.toString());
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> home.ended(atHome, Status.CANCELLED,
                                "home", "not ours to end")).getMessage() != null,
                "the tenant moved work that lasts on its site");
    }

    @Test
    @Order(16)
    @DisplayName("a record the tenant holds, kept by a site's run by what it says, leaves the "
            + "tenant's record as it was, in content and version, and the tenant's trail says "
            + "the site's write stayed on the site")
    @Proving(DboPromises.SYNC_A_SITE_HANDS_HOME_ONLY_WHAT_IS_ITS_OWN)
    void aSitesWriteBackLeavesTheTenantsOwnRecord() throws Exception {
        String bench = theBench();
        assertEquals(201, post(cloud, CLINIC, "Device", """
                {"resourceType":"Device","identifier":[{"system":"%s","value":"analyser-1"}],
                 "manufacturer":"as the clinic set it up",
                 "location":{"reference":"Location?identifier=%s|bench-1"}}"""
                .formatted(ANALYSERS, BENCHES)));
        com.fasterxml.jackson.databind.JsonNode before = only(cloud, "Device?identifier="
                + ANALYSERS + "|analyser-1");
        assertEquals("Location/" + bench, before.path("location").path("reference").asText());

        performedOnTheSite("keeps-the-analyser", Outcome.Write.upsert(
                "Device?identifier=" + ANALYSERS + "|analyser-1", """
                {"resourceType":"Device","identifier":[{"system":"%s","value":"analyser-1"}],
                 "manufacturer":"as the site keeps it",
                 "location":{"reference":"Location?identifier=%s|bench-1"}}"""
                        .formatted(ANALYSERS, BENCHES)));
        site.syncRound();

        com.fasterxml.jackson.databind.JsonNode after = only(cloud, "Device?identifier="
                + ANALYSERS + "|analyser-1");
        assertEquals(before.path("id").asText(), after.path("id").asText(), after.toString());
        assertEquals(before.path("meta").path("versionId").asText(),
                after.path("meta").path("versionId").asText(), after.toString());
        assertEquals("as the clinic set it up", after.path("manufacturer").asText(),
                after.toString());
        assertEquals("Location/" + bench, after.path("location").path("reference").asText(),
                after.toString());
        com.fasterxml.jackson.databind.JsonNode said = writeBack("keeps-the-analyser");
        assertEquals("write-back." + Lanes.Untaken.KEPT_ON_THE_PLACE,
                said.path("type").path("code").asText(), said.toString());
        assertEquals("Device/" + before.path("id").asText(),
                said.path("entity").path(0).path("what").path("reference").asText(),
                "the tenant's trail does not say which of its records the site's write was "
                        + "about: " + said);
    }

    @Test
    @Order(17)
    @DisplayName("a record a site's run made comes home pointing at the tenant's own record "
            + "where it pointed at the site's record of the same thing")
    @Proving(DboPromises.SYNC_A_SITE_HANDS_HOME_ONLY_WHAT_IS_ITS_OWN)
    void aSitesRecordComesHomePointingAtTheTenantsOwn() throws Exception {
        String bench = theBench();
        performedOnTheSite("at-the-bench", Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"at-the-bench"},
                 "subject":{"reference":"Location?identifier=%s|bench-1"}}"""
                .formatted(BENCHES)));
        site.syncRound();

        com.fasterxml.jackson.databind.JsonNode home = observed("at-the-bench");
        assertEquals("Location/" + bench, home.path("subject").path("reference").asText(),
                home.toString());
    }

    @Test
    @Order(18)
    @DisplayName("a record a site's run made, pointing at a record its tenant does not hold — "
            + "asked by what it says, or by an id only the site minted — is refused at home and "
            + "nothing pointing nowhere is stored; the tenant's trail says the site's write was "
            + "refused")
    @Proving(DboPromises.SYNC_A_SITE_HANDS_HOME_ONLY_WHAT_IS_ITS_OWN)
    void aReferenceTheTenantCannotAnswerIsRefused() throws Exception {
        assertEquals(201, post(site, CLINIC, "Location", """
                {"resourceType":"Location","name":"only on the site",
                 "identifier":[{"system":"%s","value":"bench-on-the-site"}]}"""
                .formatted(BENCHES)));
        performedOnTheSite("pointing-nowhere", Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"pointing-nowhere"},
                 "subject":{"reference":"Location?identifier=%s|bench-on-the-site"}}"""
                .formatted(BENCHES)));
        site.syncRound();

        assertTrue(!read(cloud, CLINIC, "Observation").contains("pointing-nowhere"),
                "a record pointing at what its tenant does not hold was stored at home");
        com.fasterxml.jackson.databind.JsonNode said = writeBack("pointing-nowhere");
        assertEquals("write-back." + Lanes.Untaken.REFUSED,
                said.path("type").path("code").asText(), said.toString());
        assertTrue(said.path("entity").path(0).path("what").path("reference").asText()
                .startsWith("Observation/"), said.toString());

        // A patient says nothing it is claimed under here, so the site can
        // name it only by the id it minted for it.
        assertEquals(201, post(site, CLINIC, "Patient", """
                {"resourceType":"Patient","gender":"male","name":[{"text":"seen-on-the-site"}]}"""));
        String patient = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(read(site, CLINIC, "Patient?gender=male")).path("entry").path(0)
                .path("resource").path("id").asText();
        performedOnTheSite("pointing-at-an-id", Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"pointing-at-an-id"},
                 "subject":{"reference":"Patient/%s"}}""".formatted(patient)));
        site.syncRound();

        assertTrue(!read(cloud, CLINIC, "Observation").contains("pointing-at-an-id"),
                "a record pointing at an id only the site minted was stored at home");
        assertEquals("write-back." + Lanes.Untaken.REFUSED, writeBack("pointing-at-an-id")
                .path("type").path("code").asText());
    }

    @Test
    @Order(19)
    @DisplayName("a reference to a record the site holds as its tenant's, by the tenant's own "
            + "id, comes home as it was")
    @Proving(DboPromises.SYNC_A_SITE_HANDS_HOME_ONLY_WHAT_IS_ITS_OWN)
    void aReferenceBothSidesHoldByOneIdStays() throws Exception {
        String county = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(read(site, CLINIC, "Organization")).path("entry").path(0)
                .path("resource").path("id").asText();
        assertTrue(!county.isEmpty(), "the site holds no organisation of the county's");
        performedOnTheSite("by-the-county", Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"by-the-county"},
                 "performer":[{"reference":"Organization/%s"}]}""".formatted(county)));
        site.syncRound();

        com.fasterxml.jackson.databind.JsonNode home = observed("by-the-county");
        assertEquals("Organization/" + county,
                home.path("performer").path(0).path("reference").asText(), home.toString());
    }

    @Test
    @Order(20)
    @DisplayName("a type the configuration alone writes is refused to a site's run on the site "
            + "itself, so nothing of it is handed home")
    @Proving(DboPromises.SYNC_A_SITE_HANDS_HOME_ONLY_WHAT_IS_ITS_OWN)
    void whatOnlyTheConfigurationWritesIsRefusedOnTheSite() throws Exception {
        Run ended = attemptedOnTheSite("sets-the-clinic-up", Outcome.Write.create("""
                {"resourceType":"Basic","code":{"text":"set-up-on-the-site"}}"""));
        assertTrue(ended.refused() != null, "a site's run wrote what only the configuration "
                + "writes: " + ended);
        site.syncRound();
        assertTrue(!read(cloud, CLINIC, "Basic").contains("set-up-on-the-site"),
                "what only the configuration writes reached the tenant from a site");
    }

    @Test
    @Order(21)
    @DisplayName("what the clinic authorised in the cloud reaches the site and every later "
            + "authorisation with it; the site reads its register as the cloud does, says when "
            + "what it holds was given, and refuses to be authorised itself")
    @Proving(DboPromises.SYNC_A_PLACE_HOLDS_WHAT_ITS_TENANT_AUTHORISED)
    void aPlaceHoldsWhatItsTenantAuthorised() throws Exception {
        java.time.Instant before = java.time.Instant.now();
        HttpResponse<String> given = authorise(cloud, "{\"rows\":[],\"purposes\":[]}");
        assertEquals(200, given.statusCode(), given.body());
        java.time.Instant after = java.time.Instant.now();
        site.syncRound();

        com.fasterxml.jackson.databind.JsonNode atHome = register(cloud);
        com.fasterxml.jackson.databind.JsonNode onTheSite = register(site);
        java.time.Instant held = java.time.Instant.parse(
                onTheSite.path("held").path("authorisedAt").asText(before.minusSeconds(1)
                        .toString()));
        assertTrue(!held.isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                        && !held.isAfter(after),
                "the site does not say it holds the authorisation the clinic just gave: "
                        + onTheSite);
        assertTrue(onTheSite.path("held").hasNonNull("heardAt"),
                "the site does not say when it last heard its origin: " + onTheSite);
        assertEquals(atHome.path("rows"), onTheSite.path("rows"),
                "the site reads its register otherwise than the clinic does at home");
        assertEquals(atHome.path("purposes"), onTheSite.path("purposes"),
                "the site reads its purposes otherwise than the clinic does at home");
        assertTrue(atHome.path("held").isMissingNode(),
                "the clinic at home says it holds a copy: " + atHome);

        HttpResponse<String> here = authorise(site, "{\"rows\":[],\"purposes\":[]}");
        assertEquals(409, here.statusCode(), here.body());
        assertTrue(here.body().contains("not_here"), here.body());

        assertEquals(200, authorise(cloud, "{\"rows\":[],\"purposes\":[]}").statusCode());
        site.syncRound();
        assertTrue(java.time.Instant.parse(register(site).path("held").path("authorisedAt")
                        .asText()).isAfter(held),
                "an authorisation given after the first did not reach the site");
    }

    @Test
    @Order(22)
    @DisplayName("the site refuses a step whose row its clinic never authorised, naming what the "
            + "step is for, and keeps a row processed without authorisation on its incidents; "
            + "once the clinic authorises the purpose in the cloud, both stand on the site")
    @Proving(DboPromises.PROC_A_PLACE_REFUSES_WHAT_DOES_NOT_STAND_THERE)
    void aPlaceRefusesWhatDoesNotStandThere() throws Exception {
        // The same deployment on both nodes: what a provider runs for the
        // clinic in the cloud it runs on the site as well.
        Path management = Files.createTempDirectory("dbo-place-management")
                .resolve(OPERATOR + ".json");
        Files.writeString(management, OPERATOR_DECLARATION);
        cloud.manages(management);
        site.manages(management);
        assertEquals(benchDigests(cloud), benchDigests(site),
                "the site's register is not the one its clinic authorises in the cloud");

        RunInitiator.Started refusal = startWithheldOnTheSite();
        assertTrue(!refusal.accepted() && refusal.body().contains(UPKEEP),
                "the site started work under a row its clinic never authorised, or refused "
                        + "it without saying what it was for: " + refusal.status() + " "
                        + refusal.body());
        assertTrue(site.unapprovedProcessing(CLINIC).stream().anyMatch(incident ->
                        BENCH_NAMED.equals(incident.step()) && UPKEEP.equals(incident.purpose())),
                "the site keeps no incident, naming the purpose, for a row processed without "
                        + "authorisation: " + site.unapprovedProcessing(CLINIC));

        HttpResponse<String> given = authorise(cloud,
                "{\"rows\":[],\"purposes\":[\"" + UPKEEP + "\"]}");
        assertEquals(200, given.statusCode(), given.body());
        site.syncRound();

        assertEquals(java.util.Set.of(), benchSteps(site.unapprovedRows(CLINIC)),
                "the clinic authorised the purpose in the cloud and its rows do not stand on "
                        + "the site");
        assertTrue(site.unapprovedProcessing(CLINIC).stream()
                        .noneMatch(incident -> BENCH_NAMED.equals(incident.step())),
                "the incident stands on the site although the purpose was authorised");
        assertEquals(register(cloud).path("purposes"), register(site).path("purposes"),
                "the site reads the purpose otherwise than the clinic does at home");
    }

    @Test
    @Order(23)
    @DisplayName("cut off from the cloud, the site acts on the last authorisation it holds and "
            + "says how old it is; a withdrawal given meanwhile reaches it once it is back")
    @Proving(DboPromises.SYNC_A_PLACE_ACTS_ON_THE_LAST_AUTHORISATION_IT_HOLDS)
    void aPlaceActsOnTheLastAuthorisationItHolds() throws Exception {
        String heard = register(site).path("held").path("heardAt").asText();
        site.readsItsOriginThrough(CLINIC, null);
        assertEquals(200, authorise(cloud, "{\"rows\":[],\"purposes\":[]}").statusCode());
        site.syncRound();

        assertEquals(java.util.Set.of(), benchSteps(site.unapprovedRows(CLINIC)),
                "the site stopped acting on what it holds while it could not hear its origin");
        assertEquals(heard, register(site).path("held").path("heardAt").asText(),
                "the site says it heard its origin while it was cut off");

        site.readsItsOriginThrough(CLINIC, connected());
        site.syncRound();

        assertEquals(java.util.Set.of(BENCH_WITHHELD, BENCH_NAMED),
                benchSteps(site.unapprovedRows(CLINIC)),
                "the withdrawal given while the site was cut off did not reach it");
        RunInitiator.Started refusal = startWithheldOnTheSite();
        assertTrue(!refusal.accepted() && refusal.body().contains(UPKEEP),
                "the site started work under a withdrawn row: " + refusal.status() + " "
                        + refusal.body());
        assertTrue(java.time.Instant.parse(register(site).path("held").path("heardAt").asText())
                        .isAfter(java.time.Instant.parse(heard)),
                "the site does not say it heard its origin again");
    }

    @Test
    @Order(24)
    @DisplayName("an application beside the site reads what stands of its clinic's register "
            + "in-process and is told when it changes, an authorisation and a withdrawal "
            + "arriving from the cloud alike, and not when nothing changed")
    @Proving(DboPromises.PROC_AN_APPLICATION_AT_A_PLACE_IS_TOLD_WHAT_STANDS)
    void anApplicationAtAPlaceIsToldWhatStands() throws Exception {
        RegisterStanding standing = site.registerStanding(CLINIC);
        assertTrue(!standing.now().permits(UPKEEP)
                        && !standing.now().steps().contains(BENCH_WITHHELD),
                "the site's application reads a purpose withdrawn as standing: "
                        + standing.now());
        List<RegisterStanding.Standing> told =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        AutoCloseable telling = standing.whenChanged(told::add);

        assertEquals(200, authorise(cloud,
                "{\"rows\":[],\"purposes\":[\"" + UPKEEP + "\"]}").statusCode());
        site.syncRound();
        assertEquals(1, told.size(), "the application was not told once of the authorisation "
                + "arriving from the cloud: " + told);
        assertTrue(told.get(0).permits(UPKEEP)
                        && told.get(0).steps().containsAll(List.of(BENCH_WITHHELD, BENCH_NAMED))
                        && told.get(0).heardAt() != null,
                "what the application was told is not what stands: " + told.get(0));

        site.syncRound();
        assertEquals(1, told.size(), "the application was told again with nothing changed");

        assertEquals(200, authorise(cloud, "{\"rows\":[],\"purposes\":[]}").statusCode());
        site.syncRound();
        assertEquals(2, told.size(), "the application was not told of the withdrawal");
        assertEquals(RegisterStanding.State.NOT, told.get(1).purposes().get(UPKEEP),
                String.valueOf(told.get(1)));

        telling.close();
        assertEquals(200, authorise(cloud,
                "{\"rows\":[],\"purposes\":[\"" + UPKEEP + "\"]}").statusCode());
        site.syncRound();
        assertEquals(2, told.size(), "an application that stopped listening was told");
        assertTrue(standing.now().permits(UPKEEP), "reading on the spot did not see the "
                + "authorisation: " + standing.now());
    }

    @Test
    @Order(25)
    @DisplayName("the site keeps an analyser's message under the kind its clinic declares, and "
            + "refuses one put under a kind the clinic does not declare or under none, saying "
            + "which kinds there are")
    @Proving(DboPromises.POL_CONTENT_IS_OF_A_DECLARED_KIND)
    void contentIsPutUnderADeclaredKind() throws Exception {
        HttpResponse<String> kept = putContent(site, "?kind=analyser-message");
        assertEquals(201, kept.statusCode(), kept.body());
        assertTrue(kept.body().contains("\"kind\":\"analyser-message\""), kept.body());

        for (String undeclared : List.of("?kind=nonesuch", "")) {
            HttpResponse<String> refused = putContent(site, undeclared);
            assertEquals(400, refused.statusCode(), undeclared + ": " + refused.body());
            assertTrue(refused.body().contains("analyser-message"),
                    "the refusal does not say which kinds there are: " + refused.body());
        }
    }

    @Test
    @Order(26)
    @DisplayName("the site lets an analyser's message go when a place's window passes, and the "
            + "cloud keeps one as old for the tenant's")
    @Proving(DboPromises.POL_CONTENT_IS_OF_A_DECLARED_KIND)
    void eachPlaceKeepsContentForItsOwnWindow() throws Exception {
        String atTheSite = Extracted.field(putContent(site, "?kind=analyser-message").body(),
                "key");
        String atHome = Extracted.field(putContent(cloud, "?kind=analyser-message").body(),
                "key");
        backdate(siteDatabases.provision(TenantSpec.parse(HANDING_DECLARATION)).dataSource(),
                atTheSite);
        backdate(cloudDatabases.provision(TenantSpec.parse(CLINIC_DECLARATION)).dataSource(),
                atHome);

        site.retentionRound();
        cloud.retentionRound();
        assertEquals(404, readContent(site, atTheSite),
                "the site kept a message three days old past a place's window of one");
        assertEquals(200, readContent(cloud, atHome),
                "the cloud let a message three days old go inside the tenant's window of thirty");
    }

    @Test
    @Order(27)
    @DisplayName("an analyser's message a run on the site put, and one a record that run wrote "
            + "points at, reach the cloud with the run, under the keys the site gave them and "
            + "once; a message put outside any run stays on the site")
    @Proving(DboPromises.SYNC_CONTENT_TRAVELS_HOME_WITH_ITS_RUN)
    void contentGoesHomeWithItsRun() throws Exception {
        String put = "MSH|^~\\&|ANALYSER|put-by-the-run";
        String pointed = "MSH|^~\\&|ANALYSER|pointed-at";
        String left = "MSH|^~\\&|ANALYSER|left-on-the-site";
        Run run = filledOnTheSite("content-goes-home");
        String byTheRun = Extracted.field(putContent(site,
                "?kind=analyser-message&run=" + run.key(), put).body(), "key");
        String pointedAt = Extracted.field(putContent(site, "?kind=analyser-message", pointed)
                .body(), "key");
        String leftHere = Extracted.field(putContent(site, "?kind=analyser-message", left)
                .body(), "key");
        assertEquals(400, putContent(site, "?kind=analyser-message&run=nonesuch/none", put)
                .statusCode(), "content was put under a run the site never performed");

        Run ended = committedOnTheSite(run, Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"content-goes-home"},
                 "note":[{"text":"the analyser's message: %s/blob/%s"}]}"""
                .formatted(base(site, CLINIC), pointedAt)));
        assertEquals(null, ended.refused(), "the site refused its own result: " + ended);
        site.syncRound();

        assertEquals(put, readContentText(cloud, byTheRun),
                "what the site's run put did not reach the cloud as it was");
        assertEquals(pointed, readContentText(cloud, pointedAt),
                "what the site's record points at did not reach the cloud");
        assertEquals(404, readContent(cloud, leftHere),
                "what the site put outside any run reached the cloud");
        String mirrored = "site" + Lanes.MIRROR_SEPARATOR + run.key();
        assertEquals(List.of(byTheRun, pointedAt).stream().sorted().toList(),
                cloud.runtime(CLINIC).orElseThrow().blobs().keysPutBy(mirrored).stream()
                        .sorted().toList(),
                "the content is not filed under the site's run at the cloud");

        site.syncRound();
        assertEquals(2, cloud.runtime(CLINIC).orElseThrow().blobs().keysPutBy(mirrored).size(),
                "the content arrived again");
    }

    @Test
    @Order(28)
    @DisplayName("an analyser's message a run on the site put while it was cut off reaches the "
            + "cloud once the site is back")
    @Proving(DboPromises.SYNC_CONTENT_TRAVELS_HOME_WITH_ITS_RUN)
    void contentWaitsWhileCutOff() throws Exception {
        site.readsItsOriginThrough(CLINIC, null);
        Run run = filledOnTheSite("content-cut-off");
        String key = Extracted.field(putContent(site,
                "?kind=analyser-message&run=" + run.key(), "MSH|^~\\&|ANALYSER|cut-off")
                .body(), "key");
        assertEquals(null, committedOnTheSite(run, Outcome.Write.create("""
                {"resourceType":"Observation","status":"final","code":{"text":"content-cut-off"}}""")
        ).refused());
        site.syncRound();
        assertEquals(404, readContent(cloud, key), "the site handed content while cut off");

        site.readsItsOriginThrough(CLINIC, connected());
        site.syncRound();
        site.syncRound();
        assertEquals("MSH|^~\\&|ANALYSER|cut-off", readContentText(cloud, key),
                "content put while cut off did not arrive once the site was back");
    }

    /** A run of the site's own step, filled on the site and not yet taken. */
    private static Run filledOnTheSite(String found) {
        return new Runs(site.runtime(CLINIC).orElseThrow().engine()).filling(
                StepDeclaration.of(RECORD, "1", "r4").taking("specimen", "Observation"),
                RunKind.PIPELINE, found,
                java.util.Map.of("specimen", RunSlot.given(
                        "{\"resourceType\":\"Observation\",\"status\":\"registered\","
                                + "\"code\":{\"text\":\"specimen\"}}")));
    }

    /** That run, taken by the site's analyser and ended with the writes given. */
    private static Run committedOnTheSite(Run run, Outcome.Write... writes) {
        Executor analyser = new Executor("analyser", "1", "example.site", Scope.BASELINE);
        Lane analysing = site.fleetLane(CLINIC, RECORD, analyser).orElseThrow();
        analysing.declare(new Declarations.Declared("jaam.lab", "record",
                analyser.name(), analyser.version(), analyser.provider(), analyser.scope(),
                analyser.name()));
        Run held = analysing.claim(run, java.time.Duration.ofMinutes(5)).orElseThrow();
        return analysing.committed(held, null, List.of(writes));
    }

    private static String readContentText(TenantRuntimeManager node, String key)
            throws Exception {
        HttpResponse<String> read = http.send(HttpRequest.newBuilder(
                        URI.create(base(node, CLINIC) + "/blob/" + key))
                        .header("Authorization", "Bearer " + writer(node, CLINIC)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), key + ": " + read.body());
        return read.body();
    }

    /** An analyser's message the clinic could not take, put as it arrived. */
    private static HttpResponse<String> putContent(TenantRuntimeManager node, String query)
            throws Exception {
        return putContent(node, query, "MSH|^~\\&|ANALYSER|garbled");
    }

    private static HttpResponse<String> putContent(TenantRuntimeManager node, String query,
            String message) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(node, CLINIC) + "/blob" + query))
                        .header("Content-Type", "text/plain")
                        .header("Authorization", "Bearer " + writer(node, CLINIC))
                        .POST(HttpRequest.BodyPublishers.ofString(message))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int readContent(TenantRuntimeManager node, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(node, CLINIC) + "/blob/" + key))
                        .header("Authorization", "Bearer " + writer(node, CLINIC)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Content put three days ago, as far as its node's retention can tell. */
    private static void backdate(javax.sql.DataSource tenant, String key) throws Exception {
        try (java.sql.Connection c = tenant.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("UPDATE state.blob SET "
                     + "written_at = now() - interval '3 days' WHERE key = ?::uuid")) {
            ps.setString(1, key);
            assertEquals(1, ps.executeUpdate(), "no content to backdate under " + key);
        }
    }

    /** Who runs the deployment, on both nodes alike, and the steps it runs for every tenant. */
    private static final String OPERATOR = "haldur";
    private static final String UPKEEP = "bench-upkeep";
    private static final String BENCH_WITHHELD = "fleet.bench.withheld";
    private static final String BENCH_NAMED = "fleet.bench.named";
    private static final String OPERATOR_DECLARATION = """
            {"code":"%s","face":"r4","types":[
               {"name":"Observation","identity":"internal","handling":"operational"}],
             "purposes":{"%s":"Keeping the clinic's benches calibrated, by the provider alone"},
             "fleetSteps":[
               {"code":"%s","slots":{"record":"Reference(Observation)"},"opens":["record"],
                "posture":"not-until-approved","registerPurpose":"%s"},
               {"code":"%s","slots":{"record":"Reference(Observation)"},"opens":["record"],
                "registerPurpose":"%s"}]}"""
            .formatted(OPERATOR, UPKEEP, BENCH_WITHHELD, UPKEEP, BENCH_NAMED, UPKEEP);

    /** The withheld step asked for on the site, for an observation the site holds. */
    private static RunInitiator.Started startWithheldOnTheSite() {
        String record = site.runtime(CLINIC).orElseThrow().engine().put(PutRequest.create(
                "Observation", OBSERVATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .id();
        return site.runInitiator().start(CLINIC, BENCH_WITHHELD,
                java.util.Map.of("record", "Observation/" + record));
    }

    private static java.util.Set<String> benchDigests(TenantRuntimeManager node) {
        java.util.Set<String> digests = new java.util.TreeSet<>();
        node.fleetRegister(CLINIC).stream().filter(row -> row.step().startsWith("fleet.bench."))
                .forEach(row -> digests.add(row.digest()));
        return digests;
    }

    private static java.util.Set<String> benchSteps(List<FleetRegister.Row> rows) {
        java.util.Set<String> steps = new java.util.TreeSet<>();
        rows.stream().map(FleetRegister.Row::step).filter(step -> step.startsWith("fleet.bench."))
                .forEach(steps::add);
        return steps;
    }

    /** The clinic's register, as the given node's door reads it. */
    private static com.fasterxml.jackson.databind.JsonNode register(TenantRuntimeManager node)
            throws Exception {
        HttpResponse<String> read = http.send(HttpRequest.newBuilder(
                        URI.create(base(node, CLINIC) + "/register"))
                        .header("Authorization", "Bearer " + registrar(node)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(read.body());
    }

    /** Exactly what the body names, authorised at the given node's door. */
    private static HttpResponse<String> authorise(TenantRuntimeManager node, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create(base(node, CLINIC) + "/register/authorisation"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + registrar(node))
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String registrar(TenantRuntimeManager node) {
        node.authority(CLINIC).ensureClient("registrar", "registrar-secret",
                List.of("configuration"));
        return clientToken(node, CLINIC, "registrar", "registrar-secret");
    }

    /**
     * The clinic's bench, recorded at home and on the site alike by what it
     * says and under ids each store minted for itself: the tenant's id.
     */
    private static String theBench() throws Exception {
        String query = "Location?identifier=" + BENCHES + "|bench-1";
        if (!read(cloud, CLINIC, query.replace("|", "%7C")).contains("\"Location\"")) {
            String bench = """
                    {"resourceType":"Location","name":"bench one",
                     "identifier":[{"system":"%s","value":"bench-1"}]}""".formatted(BENCHES);
            assertEquals(201, post(cloud, CLINIC, "Location", bench));
            assertEquals(201, post(site, CLINIC, "Location", bench));
        }
        String home = only(cloud, query).path("id").asText();
        assertNotEquals(home, only(site, query).path("id").asText(),
                "the bench has one id in both stores, so nothing here crosses a boundary");
        return home;
    }

    /**
     * The one entry on the tenant's trail saying a write-back of that run on
     * the site was not taken: one, however often the site handed it.
     */
    private static com.fasterxml.jackson.databind.JsonNode writeBack(String found)
            throws Exception {
        String run = "site" + Lanes.MIRROR_SEPARATOR + RECORD + "/" + found;
        List<com.fasterxml.jackson.databind.JsonNode> said = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode entry : new com.fasterxml.jackson.databind
                .ObjectMapper().readTree(read(cloud, CLINIC, "AuditEvent?_count=1000"))
                .path("entry")) {
            com.fasterxml.jackson.databind.JsonNode event = entry.path("resource");
            if (event.path("type").path("code").asText().startsWith("write-back.")
                    && event.path("entity").toString().contains("\"" + run + "\"")) {
                said.add(event);
            }
        }
        assertEquals(1, said.size(), "write-backs of " + run + " on the tenant's trail: " + said);
        return said.get(0);
    }

    /** The one observation at home whose code reads so. */
    private static com.fasterxml.jackson.databind.JsonNode observed(String text) throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> found = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode entry : new com.fasterxml.jackson.databind
                .ObjectMapper().readTree(read(cloud, CLINIC, "Observation?_count=1000"))
                .path("entry")) {
            if (text.equals(entry.path("resource").path("code").path("text").asText())) {
                found.add(entry.path("resource"));
            }
        }
        assertEquals(1, found.size(), "observations at home reading '" + text + "': " + found);
        return found.get(0);
    }

    /** The one record a search on the clinic finds on that node. */
    private static com.fasterxml.jackson.databind.JsonNode only(TenantRuntimeManager node,
            String query) throws Exception {
        com.fasterxml.jackson.databind.JsonNode bundle = new com.fasterxml.jackson.databind
                .ObjectMapper().readTree(read(node, CLINIC, query.replace("|", "%7C")));
        assertEquals(1, bundle.path("entry").size(), query + " found " + bundle);
        return bundle.path("entry").path(0).path("resource");
    }

    private static int times(String found, String in) {
        return in.split(java.util.regex.Pattern.quote(found), -1).length - 1;
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

    private static TenantState.State stateOf(TenantRuntimeManager node, String code) {
        return node.tenantStates().stream().filter(state -> state.code().equals(code))
                .map(TenantState::state).findFirst().orElse(null);
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
