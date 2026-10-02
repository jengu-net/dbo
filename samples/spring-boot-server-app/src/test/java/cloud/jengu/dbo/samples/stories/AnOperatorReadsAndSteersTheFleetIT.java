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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        if (redeclared != null) {
            dbo.retract(redeclared);
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
        String why = String.valueOf(rowFor(broken).get("why"));
        Proves.that(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES, why.contains("seitsmes"),
                "the node says the tenant failed and not why, so the operator who asked is "
                        + "sent to a log they cannot read from here: " + why);
        Proves.that(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES,
                "".equals(rowFor(HOSPITAL).get("why")),
                "a tenant that serves has something to explain: " + rowFor(HOSPITAL));

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

    // ── and a tenant declared differently from how it serves is noticed ──

    @Test
    @Order(8)
    @DisplayName("a tenant serving what was declared is nobody's question, and the node says "
            + "so rather than leaving the field out")
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void aTenantServingWhatWasDeclaredSaysSo() throws InterruptedException {
        redeclared = NAMES.tenant("redeclared");
        dbo.declare(redeclared, redeclaredSpec("r4", "Observation"));
        assertTrue(dbo.until(redeclared, true, Duration.ofMinutes(10)),
                "the clinic never came up: " + dbo.serving());
        beforeTheRebuild = dbo.write(redeclared, "Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"before the rebuild"}}""").idOrFail();
        Proves.that(DboPromises.TEN_A_REDECLARATION_IS_NOTICED,
                "".equals(rowFor(redeclared).get("declaredDifferently")),
                "a tenant serving what was declared does not say so: " + rowFor(redeclared));
    }

    @Test
    @Order(9)
    @DisplayName("declaring a type the tenant does not have yet rebuilds it in place: it keeps "
            + "serving, serves the new type, and keeps what it held")
    @Proving(DboPromises.TEN_A_CHANGE_IS_NOT_A_RETRACTION)
    void aNewTypeIsARebuildNotARetraction() throws InterruptedException {
        dbo.declare(redeclared, redeclaredSpec("r4", "Observation", "Condition"));
        ATenantsDoor door = new ATenantsDoor(dbo, redeclared);
        long giveUp = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        // Serving again is the tenant's store being published, which follows
        // the surface answering: both are waited for.
        while ((door.get("/Condition?_summary=count").statusCode() != 200
                || !dbo.serving().contains(redeclared)) && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
        }
        boolean serving = dbo.serving().contains(redeclared);
        HttpResponse<String> condition = door.get("/Condition?_summary=count");
        HttpResponse<String> kept = door.get("/Observation/" + beforeTheRebuild);
        Proves.that(DboPromises.TEN_A_CHANGE_IS_NOT_A_RETRACTION,
                serving && condition.statusCode() == 200 && kept.statusCode() == 200,
                "a change to what the tenant serves took it down, did not serve the new "
                        + "type, or lost what it held: serving=" + serving + " condition="
                        + condition.statusCode() + " " + condition.body() + " kept="
                        + kept.statusCode() + " " + kept.body());
    }

    @Test
    @Order(10)
    @DisplayName("a face cannot change under a serving tenant, and the node says so by name "
            + "while the tenant keeps serving what it was built from")
    @Proving({DboPromises.TEN_A_REDECLARATION_IS_NOTICED,
            DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES})
    void aFaceChangeIsRefusedByName() throws InterruptedException {
        dbo.declare(redeclared, redeclaredSpec("r5", "Observation", "Condition"));
        String said = "";
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        // What the node says about the rebuild before it is replaced by what it
        // says about this declaration, so the wait is for the face.
        while (!said.contains("face") && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            said = String.valueOf(rowFor(redeclared).getOrDefault("declaredDifferently", ""));
        }
        Proves.that(DboPromises.TEN_A_REDECLARATION_IS_NOTICED,
                said.startsWith("cannot be applied to a serving tenant") && said.contains("face")
                        && dbo.serving().contains(redeclared),
                "a face change under a serving tenant was not refused by name over the node's "
                        + "own surface, or the tenant stopped serving: " + said);
    }

    @Test
    @Order(11)
    @DisplayName("declaring the tenant back the way it serves is the difference going away")
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void declaringItBackClearsIt() throws InterruptedException {
        dbo.declare(redeclared, redeclaredSpec("r4", "Observation", "Condition"));
        String said = "unread";
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!said.isEmpty() && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            said = String.valueOf(rowFor(redeclared).getOrDefault("declaredDifferently", "?"));
        }
        Proves.that(DboPromises.TEN_A_REDECLARATION_IS_NOTICED, said.isEmpty(),
                "declaring the tenant back did not clear the difference: " + said);
    }

    @Test
    @Order(12)
    @DisplayName("the node says what the database made of a tenant's writes beside the "
            + "toolchain, so the case for switching can be read from outside")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void whatTheDatabaseMadeOfTheWritesIsReadable() {
        String body = ask("/runtime/tenants", OPS).body();
        Proves.that(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES,
                body.contains("answeredBesideTheToolchain") && java.util.List.of("compared",
                        "agreed", "onlyTheToolchain", "onlyTheDatabase", "notHeld", "failed")
                        .stream().allMatch(body::contains),
                "the tally counted on every write cannot be read from outside, or does not tell "
                        + "agreement from having answered nothing: " + body);
    }

    // ── and what the deployment was told is a record it keeps ──

    @Test
    @Order(13)
    @DisplayName("what the deployment was told to serve is a record in the managing tenant's "
            + "store, the declaration as somebody wrote it, replaced when it changes")
    @Proving(DboPromises.TEN_A_DECLARATION_IS_A_RECORD)
    void aDeclarationIsARecordAndAChangeReplacesIt() throws InterruptedException {
        // The clinic is declared as it now serves, with Observation and Condition.
        Proves.that(DboPromises.TEN_A_DECLARATION_IS_A_RECORD,
                untilRecorded(redeclared, held -> held.equals(
                        redeclaredSpec("r4", "Observation", "Condition"))),
                "the managing tenant does not hold the declaration as it was written: "
                        + declarationsOf(redeclared));
        dbo.declare(redeclared, redeclaredSpec("r4", "Observation", "Condition", "Specimen"));
        Proves.that(DboPromises.TEN_A_DECLARATION_IS_A_RECORD,
                untilRecorded(redeclared, held -> held.contains("Specimen"))
                        && declarationsOf(redeclared).size() == 1,
                "a changed declaration did not replace the one on record: "
                        + declarationsOf(redeclared));
    }

    @Test
    @Order(14)
    @DisplayName("a declaration that will not parse is a card naming it for a person, and "
            + "the declarations beside it are recorded regardless")
    @Proving({DboPromises.TEN_A_DECLARATION_IS_A_RECORD,
            DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP})
    void anUnreadableDeclarationIsACardForAPerson() throws InterruptedException {
        String unreadable = NAMES.tenant("unreadable");
        dbo.declare(unreadable, "not a tenant spec at all");
        try {
            boolean carded = false;
            long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (!carded && System.nanoTime() < giveUp) {
                Thread.sleep(1000);
                // The card names the declaration as the source holds it: its file.
                carded = cardsOfTheDeploymentsPass().stream()
                        .anyMatch(reference -> reference.startsWith(unreadable));
            }
            Proves.that(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP, carded,
                    "an unreadable declaration left no card naming it for somebody to fix: "
                            + cardsOfTheDeploymentsPass());
            Proves.that(DboPromises.TEN_A_DECLARATION_IS_A_RECORD,
                    declarationsOf(redeclared).size() == 1,
                    "the readable declaration beside it was taken off the record");
        } finally {
            dbo.retract(unreadable);
        }
    }

    @Test
    @Order(15)
    @DisplayName("a source that cannot be read retracts nothing: the records stand and the "
            + "tenants keep serving until it can be read again")
    @Proving({DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED,
            DboPromises.TEN_SERVED_FROM_WHAT_WAS_APPLIED})
    void anUnreadableSourceRetractsNothing() throws InterruptedException {
        dbo.world().becomesUnreadable();
        try {
            // Several of the deployment's own passes, each of which reads the
            // source and finds it unreadable.
            Thread.sleep(8000);
            Proves.that(DboPromises.TEN_SERVED_FROM_WHAT_WAS_APPLIED,
                    dbo.serving().contains(redeclared) && declarationsOf(redeclared).size() == 1,
                    "a source that could not be read took a live tenant or its record down");
        } finally {
            dbo.world().becomesReadable();
        }
    }

    @Test
    @Order(16)
    @DisplayName("a declaration nobody makes any more leaves the record, and the tenant it "
            + "named stops being served")
    @Proving(DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED)
    void aWithdrawnDeclarationLeavesTheRecord() throws InterruptedException {
        dbo.retract(redeclared);
        boolean gone = dbo.until(redeclared, false, Duration.ofMinutes(1));
        long giveUp = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (!declarationsOf(redeclared).isEmpty() && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
        }
        Proves.that(DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED,
                gone && declarationsOf(redeclared).isEmpty(),
                "a withdrawn declaration is still on record, or its tenant is still served");
    }

    @Test
    @Order(17)
    @DisplayName("applying can be asked for by whoever was granted it, answered with what the "
            + "pass did, and is refused to a credential without that grant")
    @Proving(DboPromises.TEN_APPLYING_IS_ASKED_FOR_AND_RECORDED)
    void applyingIsAskedForByWhoeverWasGrantedIt() {
        var authority = tenants.authority(MANAGEMENT).orElseThrow();
        String operator = NAMES.value("an-operator");
        authority.ensureClient(operator, "operator-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
        String writer = NAMES.value("a-writer");
        authority.ensureClient(writer, "writer-secret", List.of("system/*.write"));

        assertEquals(401, askToApply(null).statusCode(), "an unauthenticated ask was answered");
        HttpResponse<String> applied = askToApply(managementToken(operator, "operator-secret"));
        HttpResponse<String> refused = askToApply(managementToken(writer, "writer-secret"));
        Proves.that(DboPromises.TEN_APPLYING_IS_ASKED_FOR_AND_RECORDED,
                applied.statusCode() == 200 && applied.body().contains("\"applied\"")
                        && refused.statusCode() == 403
                        && refused.body().contains(cloud.jengu.dbo.auth.Scopes.CONFIGURATION),
                "asking to apply was not answered for the grant, or not refused without it: "
                        + applied.statusCode() + " " + applied.body() + " / "
                        + refused.statusCode() + " " + refused.body());
    }

    @Test
    @Order(18)
    @DisplayName("a directory that cannot be read is not a directory declaring nothing")
    @Proving(DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE)
    void aDirectoryThatCannotBeReadRefuses() {
        Proves.that(DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE,
                assertThrows(RuntimeException.class, () -> new cloud.jengu.dbo.sync
                        .DirectoryConfigSource(java.nio.file.Path.of(NAMES.value("nowhere")),
                                cloud.jengu.dbo.tenant.TenantDeclarationModel.TYPE, ".json")
                        .fetch()) != null,
                "a directory that does not exist read as one declaring nothing");
    }

    // ── and a tenant that failed halfway keeps saying the same thing ──

    @Test
    @Order(19)
    @DisplayName("a tenant whose bring-up fails halfway says why, and says it the same way on "
            + "every later pass rather than reporting the wreckage of the first")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void aBringUpThatFailedHalfWaySaysWhyEveryTime() throws InterruptedException {
        // Parseable, and unservable only once the tenant is being built: the
        // hospital is serving, so nothing is waited for, and it is not a face
        // root, which is found out after the tenant's surfaces are mounted and
        // its streams wired. A zone nothing serves was the vehicle once; that
        // is a wait now, with nothing built, and says so as one.
        String halted = NAMES.tenant("halted");
        dbo.declare(halted, """
                {"code":"%s","face":"r5","dependencies":[
                  {"name":"%s","face":true,
                   "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(halted, HOSPITAL));
        try {
            String first = "";
            long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (!first.contains("not a face root") && System.nanoTime() < giveUp) {
                Thread.sleep(1000);
                first = String.valueOf(rowFor(halted).get("why"));
            }
            assertTrue(first.contains("not a face root"), "the node does not say why: " + first);
            // Several of the node's own passes, each of which retries it.
            Thread.sleep(8000);
            String later = String.valueOf(rowFor(halted).get("why"));
            Proves.that(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES,
                    later.equals(first) && "failed".equals(rowFor(halted).get("state")),
                    "a retry met its own leftovers instead of the reason: " + first + " / "
                            + later);
        } finally {
            dbo.retract(halted);
        }
    }

    // ── configuration is handed to a tenant through its own door ──

    private static final String BENCHES = "urn:benches";
    private static final String ORGS = "urn:orgs";
    private String configured;
    private String loader;
    private String vocabulary;

    @Test
    @Order(20)
    @DisplayName("a declared vocabulary is applied as one pass and answers in its native form, "
            + "and the same declaration again writes nothing, however its keys are ordered")
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS, DboPromises.TERM_NATIVE_FORM,
            DboPromises.TERM_EVERY_TENANT_ANSWERS, DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP})
    void aDeclaredVocabularyIsAppliedAsOnePass() {
        configured = NAMES.tenant("configured");
        vocabulary = NAMES.canonical("CodeSystem/declared");
        dbo.declare(configured, """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"Device","identity":"identifier","systems":["%s"],
                   "handling":"projected-config"},
                  {"name":"ParticipantDeclaration","identity":"identifier",
                   "systems":["urn:participants"],"handling":"operational","definition":"none"},
                  {"name":"Organization","identity":"identifier","systems":["%s"],
                   "handling":"operational"}]}""".formatted(configured, BENCHES, ORGS));
        assertTrue(dbo.until(configured, true, Duration.ofMinutes(10)), "not configured");
        loader = NAMES.value("loader");
        tenants.authority(configured).orElseThrow().ensureClient(loader, "loader-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));

        String once = hand("{\"correlation\":\"commit:one\",\"declarations\":["
                + codeSystem("1", "Alpha") + "]}");
        Proves.that(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
                once.contains("\"applied\":1"), "the declared set was not applied: " + once);
        Proves.that(DboPromises.TERM_EVERY_TENANT_ANSWERS,
                configuredGet("/CodeSystem/$lookup?system=" + encode(vocabulary) + "&code=a")
                        .contains("Alpha"), "the tenant does not answer from what it took");
        Proves.that(DboPromises.TERM_NATIVE_FORM,
                configuredGet("/CodeSystem?url=" + encode(vocabulary)).contains("not-present"),
                "the code system is not served in its native form");

        String draft = hand("{\"declarations\":[" + valueSet("draft") + "]}");
        assertTrue(draft.contains("\"applied\":1"), draft);
        long before = versionOf("ValueSet", vocabulary + "/vs");
        String again = hand("{\"declarations\":[" + valueSet("draft") + "]}");
        Proves.that(DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP,
                again.contains("\"unchanged\":1") && again.contains("\"applied\":0")
                        && before == versionOf("ValueSet", vocabulary + "/vs"),
                "the same declaration again wrote something: " + again);
        assertTrue(hand("{\"declarations\":[" + valueSet("active") + "]}")
                .contains("\"applied\":1"), "a changed declaration did not land");
        String reordered = hand("{\"declarations\":[{\"type\":\"ValueSet\","
                + "\"name\":\"terminology/declared-vs.json\",\"payload\":{\"status\":\"active\","
                + "\"compose\":{\"include\":[{\"system\":\"" + vocabulary + "\"}]},"
                + "\"version\":\"1\",\"url\":\"" + vocabulary + "/vs\","
                + "\"resourceType\":\"ValueSet\"}}]}");
        Proves.that(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
                reordered.contains("\"unchanged\":1"),
                "a reordered declaration was taken for a different one: " + reordered);
        String corrected = hand("{\"declarations\":[" + codeSystem("1", "Alpha corrected")
                + "]}");
        Proves.that(DboPromises.TERM_NATIVE_FORM,
                corrected.contains("\"applied\":1")
                        && configuredGet("/CodeSystem/$lookup?system=" + encode(vocabulary)
                        + "&code=a").contains("Alpha corrected"),
                "a correction without a version bump did not land: " + corrected);
    }

    @Test
    @Order(21)
    @DisplayName("a read the source says it agreed with is a read, and only a complete read "
            + "may withdraw what it no longer names")
    @Proving({DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE,
            DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED})
    void aCompleteReadMayWithdraw() {
        hand("{\"marker\":\"commit:settled\",\"declarations\":[" + valueSet("active") + "]}");
        Proves.that(DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE,
                hand("{\"marker\":\"commit:settled\",\"declarations\":[" + valueSet("active")
                        + "]}").contains("\"read\":0"),
                "a read the scope already agreed with was applied again");
        assertTrue(hand("{\"marker\":\"commit:has-bench\",\"complete\":true,"
                + "\"declarations\":[" + bench("bench-7") + "]}").contains("\"applied\":1"));
        Proves.that(DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED,
                hand("{\"marker\":\"commit:bench-gone\",\"complete\":true,"
                        + "\"declarations\":[" + bench("bench-8") + "]}")
                        .contains("\"withdrawn\":1"),
                "a complete read did not withdraw what it no longer names");
        HttpResponse<String> unmarked = handResponse("{\"complete\":true,\"declarations\":["
                + valueSet("active") + "]}");
        Proves.that(DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED,
                unmarked.statusCode() == 400 && unmarked.body().contains("marker"),
                "completeness without a read it is about was taken: " + unmarked.body());
    }

    @Test
    @Order(22)
    @DisplayName("the answer carries the pass's cards, a refusal says what this tenant does "
            + "serve, and what is advertised is what is served")
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    void theAnswerCarriesThePass() {
        String broken = hand("{\"declarations\":[{\"type\":\"ValueSet\","
                + "\"name\":\"zone/vocab/broken-status.json\",\"payload\":{"
                + "\"resourceType\":\"ValueSet\",\"url\":\"" + vocabulary
                + "/vs-broken\",\"status\":\"unicorn\"}}]}");
        Proves.that(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
                broken.contains("\"skipped\":1") && broken.contains("broken-status.json")
                        && broken.contains("\"run\""),
                "the answer does not carry the pass's card: " + broken);
        String clean = hand("{\"declarations\":[" + codeSystem("2", "Beta") + "]}");
        assertTrue(clean.contains("\"skipped\":0") && clean.contains("\"cards\":[]"), clean);
        String refused = configuredGet("/ActivityDefinition");
        java.util.regex.Matcher serves = java.util.regex.Pattern
                .compile("this tenant serves (\\d+) resource types").matcher(refused);
        Proves.that(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
                refused.contains("ActivityDefinition is not one of them") && serves.find()
                        && Integer.parseInt(serves.group(1)) >= 3 && refused.contains("/metadata"),
                "a refusal does not say what the tenant serves: " + refused);
        String metadata = configuredGet("/metadata");
        assertTrue(metadata.contains("\"type\":\"Device\"")
                && !metadata.contains("\"type\":\"ActivityDefinition\""), metadata);
    }

    @Test
    @Order(23)
    @DisplayName("a declaration naming another by what it is called is composed after it, and "
            + "one naming nobody is a card naming who")
    @Proving(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT)
    void aDeclarationNamesItsReferent() {
        String applied = hand("{\"declarations\":[" + org("ward", "department") + ","
                + org("department", "root") + "," + org("root", null) + "]}");
        assertTrue(applied.contains("\"applied\":3") && applied.contains("\"cards\":[]"),
                applied);
        String department = configuredGet("/Organization?identifier="
                + encode(ORGS + "|department"));
        String parent = department.replaceAll(
                "(?s).*\"partOf\":\\{\"reference\":\"(Organization/[^\"]+)\".*", "$1");
        String named = configuredGet("/" + parent);
        Proves.that(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT,
                parent.startsWith("Organization/") && !parent.contains("?")
                        && named.contains("\"value\":\"root\""),
                "the referrer does not point, by id, at the record it named: " + department
                        + " -> " + named);
        String orphan = hand("{\"declarations\":[" + org("orphan", "a-parent-nobody-declared")
                + "]}");
        Proves.that(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT,
                orphan.contains("\"skipped\":1") && orphan.contains("a-parent-nobody-declared"),
                "a referent nobody declared was not refused by name: " + orphan);
        String participant = hand("{\"declarations\":[{\"type\":\"ParticipantDeclaration\","
                + "\"name\":\"main-lab\",\"payload\":{\"resourceType\":"
                + "\"ParticipantDeclaration\",\"identifier\":[{\"system\":"
                + "\"urn:participants\",\"value\":\"main-lab\"}],\"zone\":\"ee\"}}]}");
        Proves.that(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT,
                participant.contains("\"applied\":1") && configuredGet(
                        "/ParticipantDeclaration?identifier=" + encode("urn:participants|main-lab"))
                        .contains("\"zone\":\"ee\""),
                "a type with no definition could not be declared: " + participant);
    }

    @Test
    @Order(24)
    @DisplayName("a set handed over is one recorded pass, correlated with where it came from, "
            + "and a credential without the grant hands over nothing")
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    void aSetHandedOverIsOneRecordedPass() {
        // A tenant of its own, so the pass read below is this hand-over's and
        // not the last of the ones before it.
        String handed = NAMES.tenant("handed");
        dbo.declare(handed, """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(handed));
        assertTrue(dbo.until(handed, true, Duration.ofMinutes(10)), "not served");
        tenants.authority(handed).orElseThrow().ensureClient(loader, "loader-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
        StringBuilder five = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            five.append("{\"type\":\"ValueSet\",\"name\":\"value-sets/vs-").append(i)
                    .append(".json\",\"payload\":{\"resourceType\":\"ValueSet\","
                            + "\"status\":\"active\",\"url\":\"")
                    .append(NAMES.canonical("ValueSet/handed-" + i)).append("\"}},");
        }
        HttpResponse<String> handedOver = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(handed) + "/configuration"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"correlation\":\"commit:abc123\","
                        + "\"declarations\":[" + five + "{\"type\":\"ValueSet\","
                        + "\"name\":\"value-sets/broken.json\",\"payload\":{\"resourceType\":"
                        + "\"Nonesuch\"}}]}")), tokenOf(handed, loader, "loader-secret"));
        String answered = handedOver.body();
        var pass = new Runs(tenants.store(handed).orElseThrow())
                .byId(dbo.says(handedOver).one("run").orElseThrow());
        Proves.that(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
                answered.contains("\"applied\":5") && answered.contains("\"skipped\":1")
                        && pass.isPresent() && "commit:abc123".equals(pass.get().correlated().orElse(null))
                        && pass.get().needsAPerson(),
                "the set was not one recorded, correlated pass: " + answered + " pass="
                        + pass.map(run -> run.correlated() + " needsAPerson="
                        + run.needsAPerson()).orElse("absent"));
        String writer = NAMES.value("writer-not-loader");
        tenants.authority(handed).orElseThrow().ensureClient(writer, "writer-secret",
                List.of("system/*.write"));
        try {
            Proves.that(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
                    dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(handed) + "/configuration"))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"declarations\":[]}")),
                            tokenOf(handed, writer, "writer-secret")).statusCode() == 403,
                    "a credential without the grant handed configuration over");
        } finally {
            dbo.retract(handed);
        }
    }

    @Test
    @Order(25)
    @DisplayName("a change can be asked about before it is made: a rebuild, a cold change, "
            + "nothing at all and a new tenant are each said, and asking leaves no trace")
    @Proving(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING)
    void aChangeCanBeAskedAboutBeforeItIsMade() throws InterruptedException {
        asked = NAMES.tenant("asked-about");
        dbo.declare(asked, previewed(asked, "r4", "Observation"));
        assertTrue(dbo.until(asked, true, Duration.ofMinutes(10)), "not served");
        String operator = NAMES.value("an-operator");
        tenants.authority(MANAGEMENT).orElseThrow().ensureClient(operator, "operator-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
        String bearer = managementToken(operator, "operator-secret");

        String rewire = preview(bearer, previewed(asked, "r4", "Observation", "Patient"));
        Proves.that(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING,
                rewire.contains("\"kind\":\"rewire\"") && rewire.contains("types")
                        && rewire.contains("\"applied\":0")
                        && !dbo.capability(asked).serves("Patient"),
                "adding a type was not said as a rebuild, or was built: " + rewire);
        String cold = preview(bearer, previewed(asked, "r5", "Observation"));
        Proves.that(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING,
                cold.contains("\"kind\":\"cold\"") && cold.contains("face")
                        && cold.contains("retracted"),
                "a cold change was not said by name: " + cold);
        Proves.that(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING,
                preview(bearer, previewed(asked, "r4", "Observation"))
                        .contains("\"kind\":\"unchanged\""),
                "no change was not said as none");
        String fresh = NAMES.tenant("never-declared");
        Proves.that(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING,
                preview(bearer, previewed(fresh, "r4", "Observation"))
                        .contains("\"kind\":\"new\"") && !dbo.serving().contains(fresh),
                "a new tenant was not said as new, or was opened");
        HttpResponse<String> misspelt = dbo.send(HttpRequest.newBuilder(URI.create(
                        dbo.at(MANAGEMENT) + "/configuration/preveiw"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(proposal(
                        previewed(asked, "r4", "Observation", "Encounter")))), bearer);
        Proves.that(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING,
                misspelt.statusCode() == 404 && !dbo.capability(asked).serves("Encounter"),
                "a near miss of the preview applied the change");
        Proves.that(DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING,
                "".equals(String.valueOf(rowFor(asked).get("declaredDifferently"))),
                "asking left a trace the sweep took for a redeclaration: " + rowFor(asked));
    }

    /** The tenant a change was asked about, withdrawn by the leg after. */
    private String asked;

    @Test
    @Order(26)
    @DisplayName("a tenant slow to come up holds up only itself: one withdrawn meanwhile "
            + "stops being served on the next beat, and one declared meanwhile comes up")
    @Proving(DboPromises.TEN_A_SLOW_BRING_UP_HOLDS_UP_ONLY_ITSELF)
    void aSlowBringUpHoldsUpOnlyItself() throws Exception {
        // Withdrawn meanwhile: the tenant the change was asked about, serving
        // since the leg before.
        String withdrawn = asked;
        String slow = NAMES.tenant("slow-to-come-up");
        String later = NAMES.tenant("declared-meanwhile");
        assertTrue(dbo.until(withdrawn, true, Duration.ofMinutes(1)), "not served");
        // Slow the way a tenant really is when another node of the deployment
        // is writing its schema: that node holds the schema lock in the
        // tenant's database, and this one waits for it. The database is made
        // first, as a node that got there first would have made it.
        String admin = environment.getRequiredProperty("dbo.admin.jdbc-url");
        String user = environment.getRequiredProperty("dbo.admin.user");
        String password = environment.getRequiredProperty("dbo.admin.password");
        String database = "tenant_" + slow.replace('-', '_');
        try (java.sql.Connection server = java.sql.DriverManager.getConnection(admin, user, password);
                java.sql.Statement make = server.createStatement()) {
            make.execute("CREATE DATABASE " + database);
        }
        String inIt = admin.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try (java.sql.Connection otherNode = java.sql.DriverManager.getConnection(inIt, user, password)) {
            try (java.sql.Statement hold = otherNode.createStatement()) {
                hold.execute("SELECT pg_advisory_lock(" + SCHEMA_LOCK + ")");
            }
            dbo.declare(slow, typed(slow, "r4", "Observation"));
            assertTrue(waitingOnTheSchema(otherNode, Duration.ofMinutes(5)),
                    "the slow tenant's bring-up never reached its schema, so nothing here "
                            + "is slow and this proves nothing");

            dbo.retract(withdrawn);
            dbo.declare(later, typed(later, "r4", "Observation"));
            Proves.that(DboPromises.TEN_A_SLOW_BRING_UP_HOLDS_UP_ONLY_ITSELF,
                    dbo.until(withdrawn, false, Duration.ofSeconds(30)),
                    "a tenant withdrawn while another was coming up stayed served until that "
                            + "one finished: " + dbo.serving());
            Proves.that(DboPromises.TEN_A_SLOW_BRING_UP_HOLDS_UP_ONLY_ITSELF,
                    dbo.until(later, true, Duration.ofMinutes(3)),
                    "a tenant declared while another was coming up waited for it: "
                            + dbo.serving());
            assertFalse(dbo.serving().contains(slow),
                    "the slow tenant came up through a lock another node holds");

            try (java.sql.Statement release = otherNode.createStatement()) {
                release.execute("SELECT pg_advisory_unlock(" + SCHEMA_LOCK + ")");
            }
            assertTrue(dbo.until(slow, true, Duration.ofMinutes(3)),
                    "the slow tenant did not come up once the schema was free: "
                            + rowFor(slow));
        } finally {
            dbo.retract(slow);
            dbo.retract(later);
            dbo.retract(withdrawn);
        }
    }

    /** The lock a node takes in a tenant's database while it writes the schema. */
    private static final long SCHEMA_LOCK = 0x64626F5F636F7265L;

    /** Whether somebody other than {@code holder} is waiting for the schema lock it holds. */
    private static boolean waitingOnTheSchema(java.sql.Connection holder, Duration give)
            throws Exception {
        long giveUp = System.nanoTime() + give.toNanos();
        while (System.nanoTime() < giveUp) {
            try (java.sql.Statement ask = holder.createStatement();
                    java.sql.ResultSet waiting = ask.executeQuery(
                            "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                                    + "AND NOT granted AND database = (SELECT oid FROM "
                                    + "pg_database WHERE datname = current_database())")) {
                waiting.next();
                if (waiting.getLong(1) > 0) {
                    return true;
                }
            }
            Thread.sleep(500);
        }
        return false;
    }

    /** A tenant of {@code code} with the given types, and nothing else to wait for. */
    private static String typed(String code, String face, String... types) {
        StringBuilder declared = new StringBuilder();
        for (String type : types) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"internal\",\"handling\":\"operational\"}");
        }
        return "{\"code\":\"" + code + "\",\"face\":\"" + face
                + "\",\"audit\":{\"level\":\"none\"},\"types\":[" + declared + "]}";
    }

    @Autowired
    org.springframework.core.env.Environment environment;

    @org.junit.jupiter.api.AfterAll
    void theConfiguredTenantIsWithdrawn() {
        if (configured != null) {
            dbo.retract(configured);
        }
        if (asked != null) {
            dbo.retract(asked);
        }
    }

    private String hand(String body) {
        HttpResponse<String> answered = handResponse(body);
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }

    private HttpResponse<String> handResponse(String body) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(configured) + "/configuration"))
                .POST(HttpRequest.BodyPublishers.ofString(body)),
                tokenOf(configured, loader, "loader-secret"));
    }

    private String configuredGet(String path) {
        return dbo.get(dbo.at(configured) + "/fhir" + path, dbo.token(configured)).body();
    }

    private long versionOf(String type, String url) {
        return tenants.store(configured).orElseThrow().getByIdentifier(type, List.of(
                new cloud.jengu.dbo.core.api.Identifier(
                        cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url)))
                .get(0).versionId();
    }

    private String codeSystem(String version, String display) {
        return ("{\"type\":\"CodeSystem\",\"name\":\"terminology/declared.json\",\"payload\":"
                + "{\"resourceType\":\"CodeSystem\",\"url\":\"%s\",\"version\":\"%s\","
                + "\"status\":\"active\",\"content\":\"complete\",\"concept\":["
                + "{\"code\":\"a\",\"display\":\"%s\"},{\"code\":\"b\",\"display\":\"Beta\"}]}}")
                .formatted(vocabulary, version, display);
    }

    private String valueSet(String status) {
        return ("{\"type\":\"ValueSet\",\"name\":\"terminology/declared-vs.json\",\"payload\":"
                + "{\"resourceType\":\"ValueSet\",\"url\":\"%s/vs\",\"version\":\"1\","
                + "\"status\":\"%s\",\"compose\":{\"include\":[{\"system\":\"%s\"}]}}}")
                .formatted(vocabulary, status, vocabulary);
    }

    private static String bench(String code) {
        return ("{\"type\":\"Device\",\"name\":\"devices/%s.json\",\"payload\":"
                + "{\"resourceType\":\"Device\",\"status\":\"active\",\"identifier\":"
                + "[{\"system\":\"%s\",\"value\":\"%s\"}]}}").formatted(code, BENCHES, code);
    }

    private static String org(String code, String parent) {
        return ("{\"type\":\"Organization\",\"name\":\"%s\",\"payload\":{\"resourceType\":"
                + "\"Organization\",\"identifier\":[{\"system\":\"%s\",\"value\":\"%s\"}],"
                + "\"name\":\"%s\"%s}}").formatted(code, ORGS, code, code,
                parent == null ? "" : ",\"partOf\":{\"reference\":\"Organization?identifier="
                        + ORGS + "|" + parent + "\"}");
    }

    private static String previewed(String code, String face, String... types) {
        StringBuilder declared = new StringBuilder();
        for (String type : types) {
            declared.append(declared.isEmpty() ? "" : ",").append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"internal\",\"handling\":\"operational\"}");
        }
        return "{\"code\":\"" + code + "\",\"face\":\"" + face + "\",\"pdi\":false,"
                + "\"audit\":{\"level\":\"none\"},\"types\":[" + declared + "]}";
    }

    private static String proposal(String spec) {
        return "{\"declarations\":[{\"type\":\"TenantDeclaration\",\"name\":\"proposed\","
                + "\"payload\":" + spec + "}]}";
    }

    private String preview(String bearer, String spec) {
        HttpResponse<String> answered = dbo.send(HttpRequest.newBuilder(URI.create(
                        dbo.at(MANAGEMENT) + "/configuration/preview"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(proposal(spec))), bearer);
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }

    private String tokenOf(String tenant, String client, String secret) {
        HttpResponse<String> issued = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(tenant) + "/oidc/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"
                        + "&client_id=" + client + "&client_secret=" + secret)), null);
        return dbo.says(issued).one("access_token").orElseThrow(
                () -> new AssertionError("no token for " + client + ": " + issued.body()));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** The deployment's managing tenant, which records what it was told. */
    private static final String MANAGEMENT = "mom";

    /** The declarations on record for one tenant code. */
    private List<String> declarationsOf(String code) {
        return tenants.store(MANAGEMENT).orElseThrow()
                .select(cloud.jengu.dbo.core.api.Criteria.of(
                        cloud.jengu.dbo.tenant.TenantDeclarationModel.TYPE))
                .stream()
                .map(record -> new String(record.payload(), StandardCharsets.UTF_8))
                .filter(held -> held.contains("\"code\":\"" + code + "\""))
                .toList();
    }

    private boolean untilRecorded(String code, java.util.function.Predicate<String> held)
            throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            if (declarationsOf(code).stream().anyMatch(held)) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    /** What the deployment's own application pass left for a person, by name. */
    private List<String> cardsOfTheDeploymentsPass() {
        var runs = new Runs(tenants.store(MANAGEMENT).orElseThrow());
        return runs.byKey(cloud.jengu.dbo.sync.ConfigApplication.PROCESS + "/"
                        + cloud.jengu.dbo.sync.ConfigApplication.STEP + "/deployment")
                .map(pass -> runs.items(pass).stream()
                        .map(card -> card.item().reference()).toList())
                .orElse(List.of());
    }

    private HttpResponse<String> askToApply(String bearer) {
        return dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(MANAGEMENT) + "/configuration"))
                .POST(HttpRequest.BodyPublishers.noBody()), bearer);
    }

    private String managementToken(String client, String secret) {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + secret;
        HttpResponse<String> issued = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(MANAGEMENT) + "/oidc/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), null);
        return dbo.says(issued).one("access_token").orElseThrow(
                () -> new AssertionError("no token for " + client + ": " + issued.body()));
    }

    private String redeclared;
    private String beforeTheRebuild;

    private String redeclaredSpec(String face, String... types) {
        StringBuilder declared = new StringBuilder();
        for (String type : types) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"internal\",\"handling\":\"operational\"}");
        }
        return "{\"code\":\"" + redeclared + "\",\"face\":\"" + face
                + "\",\"audit\":{\"level\":\"none\"},\"types\":[" + declared + "]}";
    }

    /** The node's row for one tenant, read as a record. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rowFor(String code) {
        Object read = cloud.jengu.dbo.core.wire.RecordWire.read(
                ask("/runtime/tenants", OPS).body());
        for (Object row : (List<?>) ((Map<?, ?>) read).get("tenants")) {
            if (code.equals(((Map<?, ?>) row).get("code"))) {
                return (Map<String, Object>) row;
            }
        }
        return Map.of();
    }

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
