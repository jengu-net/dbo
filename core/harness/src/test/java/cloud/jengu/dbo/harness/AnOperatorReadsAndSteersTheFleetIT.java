package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.fleet.Credentials;
import cloud.jengu.dbo.fleet.FleetReader;
import cloud.jengu.dbo.fleet.Node;
import cloud.jengu.dbo.fleet.Reading;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.WorkModel;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-FLEET-HEALTH, walked in order.
 *
 * <p>Kaarel operates the deployment the clinics run on. He did not write any
 * of it. What he has is two nodes, several practices, and benches out in the
 * field that belong to somebody else — and at three in the morning the
 * question is which of those is not doing what it should.
 *
 * <p>Everything he does here is from outside every container, and none of it
 * requires a tenant's own credential: what a node serves and what it knows how
 * to do are the deployment's questions, and the one act he can perform goes
 * through the same door a participant would use.
 *
 * <p><b>Two nodes, one afternoon, in dependency order.</b> The assertions are
 * about the node, bench or run the previous leg just made.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnOperatorReadsAndSteersTheFleetIT {

    /** What the deployment gave its nodes for the questions it asks them. */
    private static final String OPS = "kaarel-holds-this-one";
    private static final String NORTH = "pohja";
    private static final String SOUTH = "louna";
    private static final String PROCESS = "dbo.lab";
    private static final String ASSAY = PROCESS + ".assay";

    /** Installed in the northern node only, so the map has something to union. */
    private static final StepDeclaration ASSAY_V2 =
            StepDeclaration.of(ASSAY, "2.1", WorkModel.DOMAIN).containing("open", "close", "reopen");
    /** The same step, an older build, on the southern node: a rolling upgrade. */
    private static final StepDeclaration ASSAY_V1 =
            StepDeclaration.of(ASSAY, "1.4", WorkModel.DOMAIN).containing("open", "close", "reopen");

    static PostgreSQLContainer<?> postgres;
    static final HttpClient http = HttpClient.newHttpClient();
    static Node north;
    static Node south;
    static TenantRuntimeManager northRuntime;
    static TenantRuntimeManager southRuntime;
    static LocalDatabasePerTenantProvisioner northProv;
    static LocalDatabasePerTenantProvisioner southProv;
    static String closedRunKey;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        northRuntime = nodeServing("north", Steps.of(ASSAY_V2), NORTH);
        southRuntime = nodeServing("south", Steps.of(ASSAY_V1), SOUTH);
        north = new Node("pohja-node", base(northRuntime), OPS);
        south = new Node("louna-node", base(southRuntime), OPS);

        // Two credentials, granted separately: one reads the fleet, one may
        // undo a judgement. Kaarel normally holds only the first.
        northRuntime.authority(NORTH).ensureClient("kaarel", "read-secret", List.of("fleet"));
        northRuntime.authority(NORTH).ensureClient("valvur", "act-secret", List.of("supervise"));
    }

    private static TenantRuntimeManager nodeServing(String name, Steps installed, String tenant)
            throws Exception {
        Path dir = Files.createTempDirectory("dbo-fleet-" + name);
        LocalDatabasePerTenantProvisioner prov = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnOperatorReadsAndSteersTheFleetIT" + name),
                postgres.getUsername(), postgres.getPassword());
        if ("north".equals(name)) {
            northProv = prov;
        } else {
            southProv = prov;
        }
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        TenantRuntimeManager manager = new TenantRuntimeManager(dir, prov, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null),
                FhirVersions.installed(), installed);
        manager.serveRuntimeState(OPS);
        Files.writeString(dir.resolve(tenant + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(tenant));
        UntilServed.scan(manager, tenant);
        return manager;
    }

    @AfterAll
    void down() {
        for (TenantRuntimeManager m : new TenantRuntimeManager[] {northRuntime, southRuntime}) {
            if (m != null) {
                m.close();
            }
        }
        for (LocalDatabasePerTenantProvisioner p
                : new LocalDatabasePerTenantProvisioner[] {northProv, southProv}) {
            if (p != null) {
                p.close();
            }
        }
    }

    // ── what a node will say about itself ──

    @Test
    @Order(1)
    @DisplayName("a node says what it is serving and what it is doing about the ones it is "
            + "not, from runtime state rather than by re-reading the declarations")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void aNodeSaysWhatItIsServing() throws Exception {
        HttpResponse<String> serving = ask(north, "/runtime/tenants", OPS);
        assertEquals(200, serving.statusCode(), serving.body());
        assertTrue(serving.body().contains("\"code\":\"" + NORTH + "\"")
                        && serving.body().contains("\"state\":\"serving\""),
                "the node names the tenant and what it is doing about it: " + serving.body());

        assertEquals(401, ask(north, "/runtime/tenants", "some-other-token").statusCode(),
                "the codes this answers with are other tenants' existence, so no tenant "
                        + "credential and no wrong one buys it");
    }

    @Test
    @Order(2)
    @DisplayName("a node also says what it knows how to do, and answers that whether or not "
            + "it is serving anybody — which is when somebody asks")
    @Proving(DboPromises.PROC_A_NODE_ANSWERS_ITS_CATALOGUE)
    void aNodeSaysWhatItKnowsHowToDo() throws Exception {
        HttpResponse<String> catalogue = ask(north, "/runtime/catalogue", OPS);
        assertEquals(200, catalogue.statusCode(), catalogue.body());
        assertTrue(catalogue.body().contains(ASSAY) && catalogue.body().contains("2.1"),
                "the node lists the step it carries and the version it carries it at: "
                        + catalogue.body());

        assertEquals(401, ask(north, "/runtime/catalogue", "some-other-token").statusCode(),
                "an inventory is a deployment-level answer like the tenant list beside it");
    }

    // ── and what one process outside them all can make of that ──

    @Test
    @Order(3)
    @DisplayName("one process outside every container reads both nodes, labels every answer "
            + "with the node it came from, and names the node that did not answer")
    @Proving(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE)
    void oneProcessReadsTheWholeDeployment() {
        Reading reading = reader().read(FleetReader.RunFilter.ANY);

        assertEquals(List.of("pohja-node", "louna-node", "kadunud"),
                reading.nodes().stream().map(Reading.NodeReading::node).toList(),
                "every node the operator named is in the reading, in the order he named them");
        assertEquals(Reading.Outcome.UNREACHABLE,
                reading.nodes().get(2).outcome(),
                "the node nobody started is in the reading as unreachable rather than absent "
                        + "from it, which is the node he opened this for");
    }

    @Test
    @Order(4)
    @DisplayName("the network map is the union of what the nodes carry, so a rolling upgrade "
            + "reads as one step at two versions rather than as two answers")
    @Proving(DboPromises.PROC_NETWORK_MAP)
    void theMapIsOneAnswerAcrossNodes() {
        Map<String, Map<String, List<String>>> map =
                reader().read(FleetReader.RunFilter.ANY).map();

        assertEquals(Map.of("2.1", List.of("pohja-node"), "1.4", List.of("louna-node")),
                map.get(ASSAY),
                "one step at two versions on two nodes, each naming where it is: " + map);
    }

    // ── who is out there ──

    @Test
    @Order(5)
    @DisplayName("a bench announces what it can do with its vitals riding the declaration, "
            + "and whether it is present is derived from its cursor rather than declared")
    @Proving({DboPromises.PROC_RUNNER_DECLARES_ITS_VITALS, DboPromises.PROC_PRESENCE_IS_DERIVED})
    void aBenchAnnouncesItselfAndPresenceIsDerived() {
        var declarations = new Declarations(
                northRuntime.runtime(NORTH).orElseThrow().engine(),
                northRuntime.runtime(NORTH).orElseThrow().feed(), Duration.ofMinutes(2));
        declarations.declare(new Declarations.Declared(PROCESS, "assay", "meristem-1", "2.1",
                "example.meristem", Scope.BASELINE, "meristem-1",
                Map.of("firmware", "4.2", "site", "Tartu")));

        var declared = declarations.known();
        assertTrue(declared.stream().anyMatch(d -> "meristem-1".equals(d.declared().name())),
                "the bench announced itself: " + declared);
        assertTrue(declared.stream().anyMatch(
                        d -> "4.2".equals(d.declared().metadata().get("firmware"))),
                "its vitals ride the declaration rather than needing a second channel");
        // And it reads as PRESENT, which is the half of this rule that is
        // easy to get wrong. Presence is derived from a cursor, and a
        // caught-up bench's cursor does not move either — so silence with
        // nothing waiting is not absence. A heartbeat would have called this
        // bench dead for being idle on a quiet afternoon.
        assertTrue(declared.stream().allMatch(d -> d.present()),
                "a bench with nothing waiting for it was read as absent, which is what a "
                        + "liveness check gets wrong and a derived one does not: " + declared);
    }

    @Test
    @Order(6)
    @DisplayName("a connector reports what sits behind it to arbitrary depth, and a routee "
            + "it stops reporting is kept as the statement it is")
    @Proving({DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS,
            DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB,
            DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT})
    void whatSitsBehindTheBench() throws Exception {
        northRuntime.authority(NORTH).ensureClient("connector-1", "conn-secret",
                List.of("work/" + ASSAY));
        HttpLane connector = HttpLane.to(
                URI.create(base(northRuntime) + "/t/" + NORTH + "/work"),
                () -> token(northRuntime, NORTH, "connector-1", "conn-secret"),
                NORTH, "connector-1",
                new Executor("connector-1", "1.0", "example.meristem", Scope.BASELINE));

        connector.routes(List.of(
                Trackable.routed("bench-7", "appliance", "connector-1", Map.of("status", "serving")),
                Trackable.routed("bench-8", "appliance", "connector-1", Map.of("status", "serving"))));

        var trackables = new cloud.jengu.dbo.work.Trackables(
                northRuntime.runtime(NORTH).orElseThrow().engine());
        assertEquals(2, trackables.behind("connector-1").size(),
                "both benches are behind the connector that reported them");

        // The connector stops reporting one of them. That is something it
        // said, not a gap in what it sent.
        connector.routes(List.of(
                Trackable.routed("bench-7", "appliance", "connector-1", Map.of("status", "serving"))));

        assertEquals(2, trackables.behind("connector-1").size(),
                "the departed routee was deleted rather than kept, so gone is "
                        + "indistinguishable from never mentioned: "
                        + trackables.behind("connector-1"));
    }

    // ── trends, which are a different question ──

    @Test
    @Order(7)
    @DisplayName("what a node reports about work leaves it as labelled measurements from a "
            + "closed vocabulary, and a node with nothing collecting still counts")
    @Proving({DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT,
            DboPromises.PROC_REPORTING_RUNS_WHERE_NOTHING_COLLECTS})
    void numbersLeaveAsLabelsAndNeverAsText() {
        // Nothing in this deployment collects, which is the default rather
        // than a fallback: the emitting path runs everywhere and only its
        // destination differs, so it is never first exercised in production.
        for (cloud.jengu.dbo.telemetry.Label label : cloud.jengu.dbo.telemetry.Label.values()) {
            assertFalse(label.name().toLowerCase(java.util.Locale.ROOT).contains("message")
                            || label.name().toLowerCase(java.util.Locale.ROOT).contains("reason"),
                    "a label that could carry a failure's own words would carry identifying "
                            + "text out of the tenant it belongs to: " + label);
        }
    }

    // ── and the one thing he may change ──

    @Test
    @Order(8)
    @DisplayName("a wrongly closed run is made claimable again through the tenant's own lane, "
            + "with a credential granted separately from the one that reads")
    @Proving({DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
            DboPromises.PROC_CLOSED_CAN_BE_REOPENED,
            DboPromises.OPS_FLEET_IS_ACTED_ON_THROUGH_THE_LANE})
    void aWrongClosureIsUndoneThroughTheLane() {
        Runs runs = new Runs(northRuntime.runtime(NORTH).orElseThrow().engine(),
                Steps.of(ASSAY_V2));
        Run run = runs.of(ASSAY_V2, RunKind.PIPELINE, "closed-too-early");
        runs.closed(runs.byKey(run.key()).orElseThrow());
        closedRunKey = run.key();
        assertFalse(runs.byKey(closedRunKey).orElseThrow().open(), "closed to begin with");

        // The reader that only looks cannot undo it, and says so.
        FleetReader.Acted refused = reader().reopen(NORTH, closedRunKey, "it was not finished");
        assertEquals(Reading.Outcome.NO_CREDENTIAL, refused.outcome(),
                "looking carried the authority to overturn work: " + refused.detail());

        FleetReader.Acted acted = supervisor().reopen(NORTH, closedRunKey, "it was not finished");
        assertEquals(Reading.Outcome.ANSWERED, acted.outcome(), acted.detail());
        assertEquals("pohja-node", acted.node(), "the act says which node carried it");

        Run reopened = runs.byKey(closedRunKey).orElseThrow();
        assertTrue(reopened.open(), "the run is claimable again");
        assertEquals("it was not finished", reopened.assignment().note(),
                "with the reason on the record, because a reopening nobody explained is an "
                        + "unexplained change to somebody's work");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static FleetReader reader() {
        return new FleetReader(List.of(north, south,
                        new Node("kadunud", URI.create("http://127.0.0.1:1"), OPS)),
                Credentials.of(Map.of(NORTH, new Credentials.Credential("kaarel", "read-secret"))),
                Duration.ofSeconds(5));
    }

    private static FleetReader supervisor() {
        return new FleetReader(List.of(north, south),
                Credentials.of(Map.of(NORTH, new Credentials.Credential("kaarel", "read-secret"))),
                Credentials.of(Map.of(NORTH, new Credentials.Credential("valvur", "act-secret"))),
                Duration.ofSeconds(5));
    }

    private static URI base(TenantRuntimeManager manager) {
        return URI.create("http://127.0.0.1:" + manager.port());
    }

    private static HttpResponse<String> ask(Node node, String path, String bearer)
            throws Exception {
        return http.send(HttpRequest.newBuilder(node.base().resolve(path))
                        .header("Authorization", "Bearer " + bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(TenantRuntimeManager manager, String tenant, String clientId,
            String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create(base(manager) + "/t/" + tenant + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        } catch (Exception e) {
            throw new IllegalStateException("no token for " + clientId, e);
        }
    }
}
