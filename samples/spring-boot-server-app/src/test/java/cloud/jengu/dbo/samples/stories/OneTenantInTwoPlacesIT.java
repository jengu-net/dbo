package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-TWO-PLACES, walked in Rowling Land, the sample world.
 *
 * <p>The clinic is one place today and two by the end of this story. It takes
 * its canonical content from Rowling Land, the jurisdiction it sits in, and it
 * puts an appliance in the building so that a lost connection is an
 * inconvenience rather than a closed practice.
 *
 * <p>Both halves are the same idea: content that belongs somewhere else,
 * arriving because somebody declared that it should, and staying legible about
 * where it came from. Canonical content travels <b>by type</b>, because none
 * of it is about anybody. Patient data travels <b>by work</b>, arriving with a
 * task and leaving when no open run still names it.
 *
 * <p><b>The zone half</b> runs on Rowling Land's own tenant, with a clinic this story
 * declares after the zone already holds content, which is the ordinary order.
 * <b>The appliance half</b> is one tenant in two places, and the world is one
 * place. It is proven where it needs no runtime at all, as two databases and a
 * lane between them ({@code AnApplianceCarriesPatientDataByWorkIT} in the
 * harness), and this story leans on it.
 */
@AUserStory
class OneTenantInTwoPlacesIT {

    private static final String ZONE = "rl";

    private final StoryNames names = StoryNames.of(DboStories.TWO_PLACES);

    @Autowired
    DboTestContext dbo;

    // ── the zone half ──
    private String clinic;
    private String severity;

    @BeforeAll
    void theClinicsNames() {
        clinic = names.tenant("clinic");
        severity = names.canonical("severity");
    }

    @AfterAll
    void theClinicIsWithdrawn() {
        if (clinic != null) {
            dbo.retract(clinic);
        }
    }

    // ── canonical content arrives because somebody declared it should ──

    @Test
    @Order(1)
    @DisplayName("the clinic declares what it takes from the zone, and catches up on what the "
            + "zone held before the clinic existed")
    @Proving({DboPromises.SYNC_SPEC_DECLARED, DboPromises.SYNC_DECLARED_ONLY,
            DboPromises.SYNC_FULL_HISTORY_CATCH_UP, DboPromises.ZONE_DECLARATIONS_AS_RECORDS})
    void theClinicDeclaresWhatItTakes() throws InterruptedException {
        // The zone publishes before the clinic exists, which is the ordinary
        // case: canonical content is older than the practices that use it.
        publish("CodeSystem", codeSystem(severity, "mild", "Mild"));

        // Declared now, after the zone already holds content: the clinic is
        // opened by saying it exists and what it takes.
        dbo.declare(clinic, clinicTakingCodeSystemsFrom(ZONE));
        assertTrue(dbo.until(clinic, true, Duration.ofMinutes(10)),
                "the clinic never came up: " + dbo.serving());

        Proves.that(DboPromises.SYNC_FULL_HISTORY_CATCH_UP, untilTheClinicHolds(severity),
                "content the zone held before the clinic was declared never arrived");
    }

    @Test
    @Order(2)
    @DisplayName("a type the clinic did not declare a dependency for brings nothing, however "
            + "much of it the zone holds")
    @Proving({DboPromises.SYNC_DECLARED_ONLY, DboPromises.SYNC_DIRECT_UPSTREAM_ONLY})
    void nothingUndeclaredArrives() throws InterruptedException {
        String valueSet = names.canonical("severity-values");
        publish("ValueSet", """
                {"resourceType":"ValueSet","url":"%s","status":"active"}""".formatted(valueSet));
        // Published after the value set, and of a type the clinic does take:
        // once it arrives, the stream has carried everything up to it.
        String later = names.canonical("severity-later");
        publish("CodeSystem", codeSystem(later, "severe", "Severe"));
        assertTrue(untilTheClinicHolds(later),
                "the code system published after the value set never arrived, so the absence "
                        + "below would prove nothing");

        HttpResponse<String> held = new ATenantsDoor(dbo, clinic)
                .get("/ValueSet?url=" + encoded(valueSet));
        assertEquals(200, held.statusCode(), held.body());
        Proves.that(DboPromises.SYNC_DECLARED_ONLY,
                dbo.says(held).at("entry.resource.id").isEmpty(),
                "an undeclared type arrived, so a dependency is a hint rather than a bound and "
                        + "a clinic ends up holding whatever its upstream happens to have: "
                        + held.body());
    }

    @Test
    @Order(3)
    @DisplayName("any type travels as a dependency at its own grain: an encounter the hospital "
            + "records arrives at St Jerome as the same record, beside the zone's code systems")
    @Proving(DboPromises.SYNC_ANY_TYPE)
    void anyTypeTravelsAtItsOwnGrain() throws InterruptedException {
        // St Jerome takes the hospital's encounters, which are about somebody,
        // as it takes Rowling Land's code systems, which are about nobody.
        var encounter = dbo.write("hogwarts", "Encounter", """
                {"resourceType":"Encounter","status":"completed",
                 "class":[{"coding":[{"code":"AMB"}]}],
                 "serviceType":[{"concept":{"text":"%s"}}]}"""
                .formatted(names.value("visit")));
        assertTrue(encounter.accepted(), encounter.body());
        String id = encounter.idOrFail();

        HttpResponse<String> copied = new ATenantsDoor(dbo, "st-jerome").get("/Encounter/" + id);
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (copied.statusCode() != 200 && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            copied = new ATenantsDoor(dbo, "st-jerome").get("/Encounter/" + id);
        }
        Proves.that(DboPromises.SYNC_ANY_TYPE,
                copied.statusCode() == 200 && copied.body().contains(names.value("visit"))
                        && copied.body().contains("\"completed\""),
                "a clinical record did not travel as a dependency, or arrived changed: "
                        + copied.statusCode() + " " + copied.body());
    }

    // ── and every record says what governs it and where it came from ──

    @Test
    @Order(4)
    @DisplayName("a served record carries its handling class, the author's own security coding "
            + "survives, and the store's stamp never accumulates")
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aServedRecordSaysWhatGovernsIt() {
        ATenantsDoor clinicDoor = new ATenantsDoor(dbo, "st-jerome");
        var written = dbo.write("st-jerome", "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:st-jerome:mrn","value":"%s"}],
                 "meta":{"security":[{"system":
                   "http://terminology.hl7.org/CodeSystem/v3-Confidentiality","code":"R"}]}}"""
                .formatted(names.value("restricted")));
        assertTrue(written.accepted(), written.body());
        String served = clinicDoor.get("/Patient/" + written.idOrFail()).body();
        assertTrue(served.contains("\"system\":\"urn:dbo:handling\"")
                        && served.contains("\"code\":\"operational\"")
                        && served.contains("v3-Confidentiality"),
                "the read does not say the declared class beside the author's own coding: "
                        + served);

        // Written back as it was served, under another number: still one stamp.
        HttpResponse<String> again = clinicDoor.post("/Patient", served
                .replaceAll(",\"id\":\"[^\"]+\"", "")
                .replace(names.value("restricted"), names.value("restricted-again")));
        assertEquals(201, again.statusCode(), again.body());
        String twice = clinicDoor.get("/Patient/" + dbo.says(again).one("id").orElseThrow())
                .body();
        Proves.that(DboPromises.SYNC_LOCAL_SHADOWING,
                twice.split(java.util.regex.Pattern.quote("urn:dbo:handling"), -1).length - 1 == 1
                        && twice.contains("v3-Confidentiality"),
                "a served document written back accumulated the store's stamp, or lost the "
                        + "author's coding: " + twice);
    }

    @Test
    @Order(5)
    @DisplayName("a streamed copy says which upstream it came from and what governs it here, and "
            + "the original says nothing of the kind")
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aStreamedCopySaysItsUpstream() {
        String atTheZone = new ATenantsDoor(dbo, ZONE).get("/CodeSystem?url=" + encoded(severity))
                .body();
        assertTrue(!atTheZone.contains("urn:dbo:upstream:"),
                "the zone's own record names an upstream: " + atTheZone);
        String copied = new ATenantsDoor(dbo, clinic).get("/CodeSystem?url=" + encoded(severity))
                .body();
        Proves.that(DboPromises.SYNC_LOCAL_SHADOWING,
                copied.contains("\"source\":\"urn:dbo:upstream:" + ZONE + "\"")
                        && copied.contains("\"code\":\"mirrored\""),
                "the clinic's copy does not say which upstream streamed it, or what governs it "
                        + "here: " + copied);
    }

    @Test
    @Order(6)
    @DisplayName("the clinic's own code system shadows the zone's copy of the same canonical, "
            + "answers in its place, and says that it is standing in front of one")
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aLocalOverrideShadowsTheZonesCopy() throws InterruptedException {
        String disputed = names.canonical("disputed");
        ATenantsDoor clinicDoor = new ATenantsDoor(dbo, clinic);
        assertEquals(201, clinicDoor.post("/CodeSystem",
                codeSystem(disputed, "local-decision", "Local")).statusCode());
        publish("CodeSystem", codeSystem(disputed, "zone-decision", "Zone"));
        // The zone's copy arriving is what there is to shadow: wait for the
        // record to say it is standing in front of one.
        String local = clinicDoor.get("/CodeSystem?url=" + encoded(disputed)).body();
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!local.contains("\"code\":\"shadows\"") && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            local = clinicDoor.get("/CodeSystem?url=" + encoded(disputed)).body();
        }
        Proves.that(DboPromises.SYNC_LOCAL_SHADOWING,
                local.contains("\"system\":\"urn:dbo:sync\"")
                        && local.contains("\"code\":\"shadows\""),
                "the clinic's own record never said it stands in front of the zone's copy: "
                        + local);
        Proves.that(DboPromises.SYNC_LOCAL_SHADOWING,
                clinicDoor.get("/CodeSystem/$lookup?system=" + encoded(disputed)
                        + "&code=local-decision").statusCode() == 200
                        && clinicDoor.get("/CodeSystem/$lookup?system=" + encoded(disputed)
                                + "&code=zone-decision").statusCode() >= 400,
                "the zone's copy answered over the clinic's own");
        Proves.that(DboPromises.SYNC_LOCAL_SHADOWING,
                !clinicDoor.get("/CodeSystem?url=" + encoded(severity)).body()
                        .contains("\"code\":\"shadows\""),
                "an ordinary copy was tagged as shadowing");
    }

    @Test
    @Order(7)
    @DisplayName("the handling system a record names resolves where it is served from, and an "
            + "unknown handling is refused")
    void theHandlingSystemResolves() {
        ATenantsDoor zone = new ATenantsDoor(dbo, ZONE);
        assertEquals(200, zone.get("/CodeSystem/$lookup?system=urn:dbo:handling&code=mirrored")
                .statusCode(), "the handling system does not resolve where it is served");
        assertTrue(zone.get("/CodeSystem/$lookup?system=urn:dbo:handling&code="
                        + names.value("no-such-handling")).statusCode() >= 400,
                "an unknown handling answered, so the answer above means nothing");
    }

    // ── and what a clinic cares about is its to change ──

    @Test
    @Order(12)
    @DisplayName("an update the zone makes keeps propagating, and the clinic answers for the "
            + "new concept from its own copy")
    @Proving(DboPromises.SYNC_TERMINOLOGY_GRAIN_SURVIVES)
    void liveUpdatesKeepPropagating() throws InterruptedException {
        ATenantsDoor zone = new ATenantsDoor(dbo, ZONE);
        String id = dbo.says(zone.get("/CodeSystem?url=" + encoded(severity)))
                .at("entry.resource.id").get(0);
        HttpResponse<String> updated = zone.put("/CodeSystem/" + id, """
                {"resourceType":"CodeSystem","id":"%s","url":"%s","status":"active",
                 "content":"complete","version":"1.0",
                 "concept":[{"code":"mild","display":"Mild"},
                            {"code":"crimson","display":"Crimson"}]}""".formatted(id, severity));
        assertTrue(updated.statusCode() < 300, updated.body());
        Proves.that(DboPromises.SYNC_TERMINOLOGY_GRAIN_SURVIVES,
                untilAnswered(clinic, severity, "crimson"),
                "the update's new concept never reached the clinic's own native form");
    }

    @Test
    @Order(13)
    @DisplayName("the clinic stops caring about the zone: what it already holds stays, and what "
            + "the zone publishes afterwards does not arrive")
    @Proving(DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE)
    void theClinicStopsCaringAndKeepsWhatItHas() throws InterruptedException {
        // Waited for, not slept on: the scan that notices the change is one
        // pass of a deployment bringing other tenants up at the same time, so
        // a fixed pause measured how busy it was. The clinic is rebuilt in
        // place for a dependency it no longer has, and a rebuilt clinic
        // carries a store it did not have before.
        Object before = tenants.store(clinic).orElseThrow();
        dbo.declare(clinic, clinicTakingCodeSystemsFrom(null));
        long rebuilt = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (tenants.store(clinic).filter(now -> now != before).isEmpty()
                && System.nanoTime() < rebuilt) {
            Thread.sleep(500);
        }
        assertTrue(dbo.until(clinic, true, Duration.ofMinutes(2)),
                "changing what the clinic cares about took it down");
        assertTrue(tenants.store(clinic).filter(now -> now != before).isPresent(),
                "the clinic was never rebuilt for the dependency it no longer declares");

        afterwards = names.canonical("published-afterwards");
        publish("CodeSystem", codeSystem(afterwards, "late", "Late"));
        // St Jerome still takes the zone's code systems: once it answers, the
        // zone's streams have carried the new one wherever it was asked for.
        assertTrue(untilAnswered("st-jerome", afterwards, "late"),
                "the zone's new code system never reached a tenant that still takes it, so "
                        + "its absence below would prove nothing");

        ATenantsDoor door = new ATenantsDoor(dbo, clinic);
        Proves.that(DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE,
                dbo.says(door.get("/CodeSystem?url=" + encoded(afterwards)))
                        .at("entry.resource.id").isEmpty()
                        && door.get("/CodeSystem/$lookup?system=" + encoded(severity)
                                + "&code=mild").statusCode() == 200,
                "a dependency nobody declares any more is still delivering, or what it "
                        + "already brought was taken away with it");
    }

    @Test
    @Order(14)
    @DisplayName("the clinic comes to care about the zone again, and catches up on what it "
            + "missed, without being withdrawn and declared anew")
    @Proving(DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE)
    void theClinicCaresAgainAndCatchesUp() throws InterruptedException {
        dbo.declare(clinic, clinicTakingCodeSystemsFrom(ZONE));
        Proves.that(DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE,
                untilAnswered(clinic, afterwards, "late"),
                "the clinic did not catch up on what the zone published while it did not care");
    }

    @Test
    @Order(15)
    @DisplayName("naming an upstream that is not up is a wait, never a teardown: the clinic "
            + "keeps serving what it holds")
    @Proving(DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE)
    void namingAnUpstreamThatIsNotUpLeavesItServing() throws InterruptedException {
        dbo.declare(clinic, clinicTakingCodeSystemsFrom(names.tenant("nobody")));
        Thread.sleep(5000);
        Proves.that(DboPromises.TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE,
                dbo.serving().contains(clinic)
                        && new ATenantsDoor(dbo, clinic).get("/CodeSystem/$lookup?system="
                                + encoded(severity) + "&code=mild").statusCode() == 200,
                "the clinic stopped serving because an upstream nobody declared has not "
                        + "arrived");
    }

    @Test
    @Order(16)
    @DisplayName("withdrawing the clinic's declaration withdraws the clinic")
    @Proving(DboPromises.SYNC_SPEC_DECLARED)
    void retractingTheDeclarationWithdrawsIt() {
        dbo.retract(clinic);
        Proves.that(DboPromises.SYNC_SPEC_DECLARED,
                dbo.until(clinic, false, Duration.ofMinutes(2)),
                "a clinic nobody declares any more is still served");
    }

    // ── and the hospital's side of an appliance's lane, held over HTTP ──

    @Test
    @Order(8)
    @DisplayName("the lane the hospital opens for an appliance over HTTP is the hospital's own: "
            + "its epoch and where the far side said it had reached are facts in its store")
    @Proving(DboPromises.PROC_LANE_EPOCH)
    void theLaneIsTheHospitalsOwn() {
        var replication = hospitalsReplication(dbo.workToken(HOSPITAL));
        String edge = names.value("edge");
        var opened = replication.open(edge);
        assertTrue(opened.epoch() != null, "opening a lane minted no epoch");
        Proves.that(DboPromises.PROC_LANE_EPOCH,
                opened.epoch().equals(replication.open(edge).epoch())
                        && opened.epoch().equals(replication.lane(edge).orElseThrow().epoch()),
                "re-opening the same lane minted another epoch, so a peer's cursor would not "
                        + "survive the hospital asking again");
        replication.mark(edge, names.value("they-said-here"));
        Proves.that(DboPromises.PROC_LANE_EPOCH,
                names.value("they-said-here").equals(
                        replication.lane(edge).orElseThrow().theirMarker()),
                "where the far side said it had reached is not a fact in the hospital's store");
    }

    @Test
    @Order(9)
    @DisplayName("a batch built over HTTP carries the work the hospital actually holds under "
            + "the lane's epoch, and accepting it advances the hospital's own cursor")
    @Proving(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY)
    void aBatchCarriesTheHospitalsOwnWork() {
        var replication = hospitalsReplication(dbo.workToken(HOSPITAL));
        String bench = names.value("bench");
        String subject = dbo.write(HOSPITAL, "Observation", """
                {"resourceType":"Observation","status":"registered","code":{"text":"%s"}}"""
                .formatted(names.value("needs-a-second-read"))).idOrFail();
        cloud.jengu.dbo.work.Runs runs = new cloud.jengu.dbo.work.Runs(
                tenants.store(HOSPITAL).orElseThrow());
        var work = runs.pipeline(process(), "validate", process() + "/validate/over-http",
                List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN));
        runs.item(work, "Observation/" + subject, cloud.jengu.dbo.work.Failure.RECORD,
                "needs a second read");

        // A batch is the next chunk of the hospital's work from where this
        // lane stands, and the hospital holds every story's work: take
        // batches, accepting each, until this run's has travelled.
        java.util.function.Predicate<cloud.jengu.dbo.sync.Lanes.Batch> carriesIt =
                b -> b.items().stream().anyMatch(item -> item.work()
                        && new String(item.payload(), StandardCharsets.UTF_8)
                                .contains(work.key()));
        var batch = replication.outbound(bench, 500, java.util.Set.of(process()));
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!carriesIt.test(batch) && System.nanoTime() < giveUp) {
            replication.sent(bench, batch);
            batch = replication.outbound(bench, 500, java.util.Set.of(process()));
        }
        Proves.that(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY,
                carriesIt.test(batch)
                        && batch.epoch().equals(replication.lane(bench).orElseThrow().epoch()),
                "the run the hospital holds did not travel under the lane's epoch: "
                        + batch.items().size() + " items");
        replication.sent(bench, batch);
        Proves.that(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY,
                batch.cursor().equals(replication.lane(bench).orElseThrow().ourCursor()),
                "accepting the batch did not move the hospital's own cursor");
    }

    @Test
    @Order(10)
    @DisplayName("what an appliance sends is applied to the hospital's store, filed under the "
            + "appliance that authored it, and sending it again applies nothing")
    @Proving(DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE)
    void whatTheApplianceSendsIsApplied() {
        var replication = hospitalsReplication(dbo.workToken(HOSPITAL));
        String bench = names.value("bench");
        String key = process() + "/validate/from-the-bench";
        var fromTheBench = new cloud.jengu.dbo.sync.Lanes.Batch(
                replication.open(bench).epoch(), bench, null,
                List.of(cloud.jengu.dbo.sync.Lanes.Item.work(
                        java.util.UUID.randomUUID().toString(), 1L,
                        java.time.Instant.parse("2026-08-30T08:00:00Z"),
                        ("{\"key\":\"" + key + "\",\"process\":\"" + process()
                                + "\",\"step\":\"validate\",\"kind\":\"pipeline\","
                                + "\"holder\":\"automation\",\"domains\":[\"work\"]}")
                                .getBytes(StandardCharsets.UTF_8))));

        var applied = replication.apply(bench, fromTheBench);
        cloud.jengu.dbo.work.Runs runs = new cloud.jengu.dbo.work.Runs(
                tenants.store(HOSPITAL).orElseThrow());
        Proves.that(DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE,
                applied.refused().isEmpty() && applied.applied() == 1
                        && runs.byKey(bench + cloud.jengu.dbo.work.WorkModel.AUTHOR_SEPARATOR
                                + key).isPresent(),
                "what the appliance sent was not filed under the appliance in the hospital's "
                        + "own store: " + applied);
        assertEquals(0, replication.apply(bench, fromTheBench).applied(),
                "a re-sent batch applied twice");
    }

    @Test
    @Order(11)
    @DisplayName("replication is the hospital's own act: a credential bounded to steps is "
            + "refused by name, and one with no participation scope reaches nothing")
    @Proving(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED)
    void replicationIsTheHospitalsOwnAct() {
        var authority = tenants.authority(HOSPITAL).orElseThrow();
        String boundedClient = names.value("bench-bounded");
        authority.ensureClient(boundedClient, "secret",
                List.of("work/" + process() + ".validate"));
        IllegalStateException bounded = assertThrows(IllegalStateException.class,
                () -> hospitalsReplication(tokenOf(boundedClient)).open(names.value("edge")));
        String readerClient = names.value("reads-only");
        authority.ensureClient(readerClient, "secret", List.of("system/*.read"));
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> hospitalsReplication(tokenOf(readerClient)).open(names.value("edge")));
        Proves.that(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED,
                bounded.getMessage().contains("bounded to steps")
                        && none.getMessage().contains("participation scope"),
                "replication was not refused by name to a bounded credential, or to one with "
                        + "no participation scope: " + bounded.getMessage() + " / "
                        + none.getMessage());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static final String HOSPITAL = "hogwarts";

    /** A code system the zone published while the clinic did not care about it. */
    private String afterwards;

    /** The clinic's declaration, taking code systems from an upstream, or from none. */
    // --8<-- [start:dependency]
    private String clinicTakingCodeSystemsFrom(String upstream) {
        String dependencies = upstream == null ? ""
                : ",\"dependencies\":[{\"name\":\"" + upstream
                        + "\",\"types\":[\"CodeSystem\"]}]";
        return """
                {"code":"%s","face":"r4","audit":{"level":"none"}%s,
                 "types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"mirrored"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(clinic, dependencies);
    }
    // --8<-- [end:dependency]

    /** Waits for a tenant to answer for a code from its own copy, not merely to store it. */
    // ── and an upstream rebuilt in place keeps its dependents streaming ──

    @Test
    @Order(17)
    @DisplayName("an upstream rebuilt in place for a type it did not have keeps its dependent "
            + "streaming, because a change is not a retraction")
    @Proving({DboPromises.TEN_A_CHANGE_IS_NOT_A_RETRACTION, DboPromises.SYNC_SPEC_DECLARED})
    void aDependentKeepsStreamingWhenItsUpstreamIsRebuilt() throws InterruptedException {
        String upstream = names.tenant("upstream");
        String dependent = names.tenant("dependent");
        String colours = names.canonical("colours");
        String canonicals = """
                {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                {"name":"ValueSet","identity":"canonical","handling":"operational"}""";
        dbo.declare(upstream, """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[%s]}"""
                .formatted(upstream, canonicals));
        try {
            assertTrue(dbo.until(upstream, true, Duration.ofMinutes(10)), "no upstream");
            HttpResponse<String> written = new ATenantsDoor(dbo, upstream).post("/CodeSystem",
                    codeSystem(colours, "green", "Green"));
            assertEquals(201, written.statusCode(), written.body());
            String id = dbo.says(written).one("id").orElseThrow();
            dbo.declare(dependent, """
                    {"code":"%s","face":"r4","audit":{"level":"none"},
                     "dependencies":[{"name":"%s","types":["CodeSystem"]}],"types":[
                      {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                      {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                    .formatted(dependent, upstream));
            assertTrue(dbo.until(dependent, true, Duration.ofMinutes(10)), "no dependent");
            assertTrue(untilAnswered(dependent, colours, "green"),
                    "the dependent never caught up with what its upstream held");

            dbo.declare(upstream, """
                    {"code":"%s","face":"r4","audit":{"level":"none"},"types":[%s,
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(upstream, canonicals));
            ATenantsDoor rebuilt = new ATenantsDoor(dbo, upstream);
            long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            boolean rebuilt2 = false;
            while (!rebuilt2 && System.nanoTime() < giveUp) {
                Thread.sleep(1000);
                try {
                    rebuilt2 = dbo.capability(upstream).serves("Observation");
                } catch (IllegalStateException beingRebuilt) {
                    // Answering 404 while its surface is mounted again.
                }
            }
            HttpResponse<String> amber = rebuilt.put("/CodeSystem/" + id, """
                    {"resourceType":"CodeSystem","id":"%s","url":"%s","status":"active",
                     "content":"complete","version":"1.0",
                     "concept":[{"code":"green","display":"Green"},
                                {"code":"amber","display":"Amber"}]}""".formatted(id, colours));
            assertTrue(amber.statusCode() < 300, amber.body());
            Proves.that(DboPromises.TEN_A_CHANGE_IS_NOT_A_RETRACTION,
                    rebuilt2 && untilAnswered(dependent, colours, "amber"),
                    "the rebuilt upstream stopped feeding its dependent, so a change was a "
                            + "retraction for whoever streamed from it");
        } finally {
            dbo.retract(dependent);
            dbo.retract(upstream);
        }
    }

    private boolean untilAnswered(String tenant, String system, String code)
            throws InterruptedException {
        ATenantsDoor door = new ATenantsDoor(dbo, tenant);
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            if (door.get("/CodeSystem/$lookup?system=" + encoded(system) + "&code=" + code)
                    .statusCode() == 200) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    @Autowired
    cloud.jengu.dbo.spring.server.DboTenants tenants;

    /** The process this story's replicated work belongs to: its own, and this run's. */
    private String process() {
        return names.prefix() + "-" + names.run() + ".result";
    }

    private cloud.jengu.dbo.sync.http.HttpLanes hospitalsReplication(String token) {
        return cloud.jengu.dbo.sync.http.HttpLanes.to(
                java.net.URI.create(dbo.at(HOSPITAL) + "/replication"), () -> token, HOSPITAL);
    }

    private String tokenOf(String client) {
        var authority = tenants.authority(HOSPITAL).orElseThrow();
        if (authority.token(client, "secret", null)
                instanceof cloud.jengu.dbo.auth.TenantAuthority.TokenResult.Issued minted) {
            return minted.accessToken();
        }
        throw new IllegalStateException("the hospital would not issue " + client + " a token");
    }

    private void publish(String type, String document) {
        HttpResponse<String> published = new ATenantsDoor(dbo, ZONE).post("/" + type, document);
        assertTrue(published.statusCode() == 200 || published.statusCode() == 201,
                "the zone did not take the " + type + ": " + published.body());
    }

    /** Waits for the clinic to answer for a code system, not merely to store it. */
    private boolean untilTheClinicHolds(String system) throws InterruptedException {
        ATenantsDoor door = new ATenantsDoor(dbo, clinic);
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            HttpResponse<String> held = door.get("/CodeSystem?url=" + encoded(system));
            if (held.statusCode() == 200
                    && !dbo.says(held).at("entry.resource.id").isEmpty()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private static String codeSystem(String url, String code, String display) {
        return """
                {"resourceType":"CodeSystem","url":"%s","status":"active",
                 "content":"complete","version":"1.0",
                 "concept":[{"code":"%s","display":"%s"}]}""".formatted(url, code, display);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
