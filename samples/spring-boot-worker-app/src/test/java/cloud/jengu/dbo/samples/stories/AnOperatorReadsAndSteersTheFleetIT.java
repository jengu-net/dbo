package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fleet.Credentials;
import cloud.jengu.dbo.fleet.FleetReader;
import cloud.jengu.dbo.fleet.Node;
import cloud.jengu.dbo.fleet.Reading;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboTestContext;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.Trackables;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-FLEET-HEALTH, walked in Rowling Land, the sample world.
 *
 * <p>Kaarel runs the deployment. He reads it from outside every container —
 * what each node serves and knows how to do, who is present, what sits behind
 * them — and steers it through the door a participant would use.
 *
 * <p><b>One node here.</b> The sample world is one deployment, so the legs
 * about a rolling upgrade across two nodes are not walked on it; they are the
 * kind item 035 leaves to a technical story. What one node says about itself,
 * and what an operator outside it can make of that, is walked here at
 * Hogwarts, with every name this story makes carrying its prefix.
 */
@AUserStory
class AnOperatorReadsAndSteersTheFleetIT {

    private static final String HOSPITAL = "hogwarts";

    /** What the deployment gave its nodes for an operator's questions: the stories profile. */
    private static final String OPS = "stories-ops";

    private static final StoryNames NAMES = StoryNames.of(DboStories.FLEET_HEALTH);
    private static final String PROCESS = NAMES.prefix() + "-" + NAMES.run() + ".lab";
    private static final String ASSAY = PROCESS + ".assay";
    private static final StepDeclaration ASSAY_STEP =
            StepDeclaration.of(ASSAY, "2.1", WorkModel.DOMAIN)
                    .containing("open", "close", "reopen");

    /** Two credentials, granted separately: one reads the fleet, one may undo a judgement. */
    private static final String READER = NAMES.value("kaarel");
    private static final String SUPERVISOR = NAMES.value("valvur");

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    private Node node;
    private String broken;

    @BeforeAll
    void theOperatorsCredentials() {
        node = new Node("sample-node", URI.create(dbo.at(HOSPITAL)).resolve("/"), OPS);
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        authority.ensureClient(READER, "read-secret", List.of("fleet"));
        authority.ensureClient(SUPERVISOR, "act-secret", List.of("supervise"));
        broken = NAMES.tenant("broken");
    }

    @AfterAll
    void nothingIsLeftDeclared() {
        if (broken != null) {
            dbo.retract(broken);
        }
    }

    // ── what a node will say about itself ──

    @Test
    @Order(1)
    @DisplayName("a node says what it is serving and what it is doing about the ones it is "
            + "not, from runtime state rather than by re-reading the declarations")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void aNodeSaysWhatItIsServing() throws InterruptedException {
        HttpResponse<String> serving = ask("/runtime/tenants", OPS);
        assertEquals(200, serving.statusCode(), serving.body());
        assertEquals("serving", stateReportedFor(serving.body(), HOSPITAL),
                "the node does not name a tenant it serves: " + serving.body());

        assertEquals(401, ask("/runtime/tenants", "some-other-token").statusCode(),
                "the codes this answers with are other tenants' existence, so no tenant "
                        + "credential and no wrong one buys it");

        // The tenant Kaarel actually opened this for: declared, and not
        // serving. A list of the ones that worked answers "which tenants are
        // fine" while looking like it answered "which tenants exist".
        dbo.declare(broken, """
                {"code":"%s","face":"seitsmes","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(broken));
        String state = null;
        for (int i = 0; i < 120 && !"failed".equals(state); i++) {
            Thread.sleep(500);
            state = stateReportedFor(ask("/runtime/tenants", OPS).body(), broken);
        }
        Proves.that(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES, "failed".equals(state),
                "a tenant that was declared and did not come up has to say so rather than "
                        + "be missing, and it says " + state);

        // And a tenant nobody declares any more stops being a state at all,
        // because a retraction reported as a failure makes every removal look
        // like a fault.
        dbo.retract(broken);
        boolean gone = false;
        for (int i = 0; i < 120 && !gone; i++) {
            Thread.sleep(500);
            gone = stateReportedFor(ask("/runtime/tenants", OPS).body(), broken) == null;
        }
        Proves.that(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES, gone,
                "a retracted tenant is still being reported on");
    }

    @Test
    @Order(2)
    @DisplayName("a node also says what it knows how to do, and answers that whether or not "
            + "it is serving anybody — which is when somebody asks")
    @Proving(DboPromises.PROC_A_NODE_ANSWERS_ITS_CATALOGUE)
    void aNodeSaysWhatItKnowsHowToDo() {
        HttpResponse<String> catalogue = ask("/runtime/catalogue", OPS);
        Proves.that(DboPromises.PROC_A_NODE_ANSWERS_ITS_CATALOGUE,
                catalogue.statusCode() == 200,
                "the node does not answer for what it carries: " + catalogue.statusCode() + " "
                        + catalogue.body());
        assertEquals(401, ask("/runtime/catalogue", "some-other-token").statusCode(),
                "an inventory is a deployment-level answer like the tenant list beside it");
    }

    // ── and what one process outside them all can make of that ──

    @Test
    @Order(3)
    @DisplayName("one process outside every container reads the deployment, labels every "
            + "answer with the node it came from, and names the node that did not answer")
    @Proving(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE)
    void oneProcessReadsTheWholeDeployment() {
        Reading reading = reader().read(FleetReader.RunFilter.ANY);

        assertEquals(List.of("sample-node", "kadunud"),
                reading.nodes().stream().map(Reading.NodeReading::node).toList(),
                "every node the operator named is in the reading, in the order he named them");
        Proves.that(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE,
                reading.nodes().get(1).outcome() == Reading.Outcome.UNREACHABLE,
                "the node nobody started is not in the reading as unreachable, so it reads as "
                        + "absent, and it is the node he opened this for");
    }

    // ── who is out there ──

    @Test
    @Order(4)
    @DisplayName("a bench announces what it can do with its vitals riding the declaration, "
            + "and whether it is present is derived from its cursor rather than declared")
    @Proving({DboPromises.PROC_RUNNER_DECLARES_ITS_VITALS, DboPromises.PROC_PRESENCE_IS_DERIVED})
    void aBenchAnnouncesItselfAndPresenceIsDerived() {
        String bench = NAMES.value("meristem-1");
        var declarations = new Declarations(tenants.store(HOSPITAL).orElseThrow(),
                tenants.changes(HOSPITAL).orElseThrow(), Duration.ofMinutes(2));
        declarations.declare(new Declarations.Declared(PROCESS, "assay", bench, "2.1",
                "example.meristem", Scope.BASELINE, bench,
                Map.of("firmware", "4.2", "site", "Tartu")));

        var mine = declarations.known().stream()
                .filter(d -> bench.equals(d.declared().name())).toList();
        assertFalse(mine.isEmpty(), "the bench announced itself and is not known");
        Proves.that(DboPromises.PROC_RUNNER_DECLARES_ITS_VITALS,
                "4.2".equals(mine.get(0).declared().metadata().get("firmware")),
                "its vitals do not ride the declaration: " + mine);
        // Present, which is the half of this rule that is easy to get wrong.
        // A caught-up bench's cursor does not move either, so silence with
        // nothing waiting is not absence.
        Proves.that(DboPromises.PROC_PRESENCE_IS_DERIVED, mine.get(0).present(),
                "a bench with nothing waiting for it was read as absent, which is what a "
                        + "liveness check gets wrong and a derived one does not: " + mine);
    }

    @Test
    @Order(5)
    @DisplayName("a connector reports what sits behind it to arbitrary depth, and a routee "
            + "it stops reporting is kept as the statement it is")
    @Proving({DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS,
            DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB,
            DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT})
    void whatSitsBehindTheBench() {
        String connector = NAMES.value("connector-1");
        String seventh = NAMES.value("bench-7");
        String eighth = NAMES.value("bench-8");
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        authority.ensureClient(connector, "conn-secret", List.of("work/" + ASSAY));
        HttpLane lane = HttpLane.to(URI.create(dbo.at(HOSPITAL) + "/work"),
                () -> token(connector, "conn-secret"), HOSPITAL, connector,
                new Executor(connector, "1.0", "example.meristem", Scope.BASELINE));

        lane.routes(List.of(
                Trackable.routed(seventh, "appliance", connector, Map.of("status", "serving")),
                Trackable.routed(eighth, "appliance", connector, Map.of("status", "serving"))));

        var trackables = new Trackables(tenants.store(HOSPITAL).orElseThrow());
        Proves.that(DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS,
                trackables.behind(connector).size() == 2,
                "both benches are not behind the connector that reported them: "
                        + trackables.behind(connector));
        Instant lastSeen = trackables.byId(eighth).orElseThrow().attested().at();

        // The connector stops reporting one of them. That is something it
        // said, not a gap in what it sent.
        lane.routes(List.of(
                Trackable.routed(seventh, "appliance", connector, Map.of("status", "serving"))));

        var departed = trackables.byId(eighth).orElseThrow(
                () -> new AssertionError("the departed routee was discarded, so gone reads "
                        + "exactly like a connector that stopped talking"));
        Proves.that(DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT, !departed.reported(),
                "the report left it out, and the record does not say so");
        assertNotNull(departed.unreported(), "with the moment the silence began");
        assertEquals(connector, departed.attested().observedBy(), "and who last saw it");
        assertEquals(lastSeen, departed.attested().at(),
                "the last attestation is kept rather than refreshed: the point is when it "
                        + "was last seen, not when somebody noticed it was not");
        Proves.that(DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB,
                trackables.subtree(connector).stream()
                        .anyMatch(t -> eighth.equals(t.id()) && !t.reported()),
                "the tree does not answer with the departed routee: "
                        + trackables.subtree(connector));

        // And it comes back clean rather than carrying its absence forward.
        lane.routes(List.of(
                Trackable.routed(seventh, "appliance", connector, Map.of("status", "serving")),
                Trackable.routed(eighth, "appliance", connector, Map.of("status", "serving"))));
        var returned = trackables.byId(eighth).orElseThrow();
        assertTrue(returned.reported(), "a routee that came back is reported again");
        assertNull(returned.unreported(), "and carries no trace of having been away");
    }

    // ── trends, which are a different question ──

    @Test
    @Order(6)
    @DisplayName("what a node reports about work leaves it as labelled measurements from a "
            + "closed vocabulary")
    @Proving(DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT)
    void numbersLeaveAsLabelsAndNeverAsText() {
        for (cloud.jengu.dbo.telemetry.Label label : cloud.jengu.dbo.telemetry.Label.values()) {
            String name = label.name().toLowerCase(Locale.ROOT);
            Proves.that(DboPromises.PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT,
                    !name.contains("message") && !name.contains("reason"),
                    "a label that could carry a failure's own words would carry identifying "
                            + "text out of the tenant it belongs to: " + label);
        }
    }

    // ── and the one thing he may change ──

    @Test
    @Order(7)
    @DisplayName("a wrongly closed run is made claimable again through the tenant's own lane, "
            + "with a credential granted separately from the one that reads")
    @Proving({DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
            DboPromises.PROC_CLOSED_CAN_BE_REOPENED,
            DboPromises.OPS_FLEET_IS_ACTED_ON_THROUGH_THE_LANE})
    void aWrongClosureIsUndoneThroughTheLane() {
        Runs runs = new Runs(tenants.store(HOSPITAL).orElseThrow(), Steps.of(ASSAY_STEP));
        Run run = runs.of(ASSAY_STEP, RunKind.PIPELINE, NAMES.value("closed-too-early"));
        runs.closed(runs.byKey(run.key()).orElseThrow());
        assertFalse(runs.byKey(run.key()).orElseThrow().open(), "closed to begin with");

        // The reader that only looks cannot undo it, and says so.
        FleetReader.Acted refused = reader().reopen(HOSPITAL, run.key(), "it was not finished");
        Proves.that(DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
                refused.outcome() == Reading.Outcome.NO_CREDENTIAL,
                "looking carried the authority to overturn work: " + refused.detail());

        FleetReader.Acted acted = supervisor().reopen(HOSPITAL, run.key(), "it was not finished");
        Proves.that(DboPromises.OPS_FLEET_IS_ACTED_ON_THROUGH_THE_LANE,
                acted.outcome() == Reading.Outcome.ANSWERED && "sample-node".equals(acted.node()),
                "the act was not carried by the node's lane: " + acted.outcome() + " "
                        + acted.detail());

        Run reopened = runs.byKey(run.key()).orElseThrow();
        Proves.that(DboPromises.PROC_CLOSED_CAN_BE_REOPENED,
                reopened.open() && "it was not finished".equals(reopened.assignment().note()),
                "the run is not claimable again with the reason on the record: " + reopened);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private FleetReader reader() {
        return new FleetReader(List.of(node,
                        new Node("kadunud", URI.create("http://127.0.0.1:1"), OPS)),
                Credentials.of(Map.of(HOSPITAL, new Credentials.Credential(READER, "read-secret"))),
                Duration.ofSeconds(5));
    }

    private FleetReader supervisor() {
        return new FleetReader(List.of(node),
                Credentials.of(Map.of(HOSPITAL, new Credentials.Credential(READER, "read-secret"))),
                Credentials.of(Map.of(HOSPITAL,
                        new Credentials.Credential(SUPERVISOR, "act-secret"))),
                Duration.ofSeconds(5));
    }

    private HttpResponse<String> ask(String path, String bearer) {
        return dbo.get(node.base().resolve(path).toString(), bearer);
    }

    /** What a body reports for one tenant, read as a record rather than matched as text. */
    private static String stateReportedFor(String body, String code) {
        Object read = cloud.jengu.dbo.core.wire.RecordWire.read(body);
        Object rows = ((Map<?, ?>) read).get("tenants");
        for (Object row : (List<?>) rows) {
            Map<?, ?> fields = (Map<?, ?>) row;
            if (code.equals(fields.get("code"))) {
                return String.valueOf(fields.get("state"));
            }
        }
        return null;
    }

    private String token(String clientId, String secret) {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> issued = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(HOSPITAL) + "/oidc/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), null);
        return dbo.says(issued).one("access_token").orElseThrow(
                () -> new IllegalStateException("no token for " + clientId + ": "
                        + issued.body()));
    }
}
