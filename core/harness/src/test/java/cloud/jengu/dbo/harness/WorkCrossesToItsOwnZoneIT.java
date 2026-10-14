package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunInitiator;
import cloud.jengu.dbo.work.Runs;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work crossing to and from a tenant's own zone, at two levels of a tree, and
 * an asker waiting for what its exchange declares: the records shared, how the
 * work ended, or nothing.
 *
 * <p>A world of its own because the tree is two zones deep, and the sample
 * world's one zone has no zone above it and none below; one added to it would
 * be a jurisdiction every shared tenant belongs to. Its tenants are named for
 * this run, because a tenant's database is named by its code and a database
 * server may outlive the run that made it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkCrossesToItsOwnZoneIT {

    private static final String RUN = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private static final String COUNTRY = "country-" + RUN;
    private static final String LAND = "land-" + RUN;
    private static final String HOSPITAL = "hospital-" + RUN;
    private static final String CLINIC = "clinic-" + RUN;

    private static final String NOTIFY = "reports.lab.notify";
    private static final String REGISTER = "reports.lab.register";
    private static final String SHARE = "reports.lab.share";
    private static final String BIRTHS = "reports.births.notify";
    private static final String CONFIRM = "notices.member.confirm";
    private static final String RELEASE = "clinic.lab.release";

    private static final String OBSERVATION = """
            {"resourceType":"Observation","status":"final","code":{"text":"%s"}}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private PostgreSQLContainer<?> postgres;
    private LocalDatabasePerTenantProvisioner databases;
    private TenantRuntimeManager manager;

    @BeforeAll
    void aTreeOfTwoZones() throws Exception {
        postgres = SharedPostgres.get();
        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("WorkCrossesToItsOwnZoneIT"),
                postgres.getUsername(), postgres.getPassword());
        Path dir = Files.createTempDirectory("dbo-own-zone");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        String observation = """
                {"name":"Observation","identity":"internal","handling":"operational"}""";
        Files.writeString(dir.resolve(COUNTRY + ".json"), """
                {"code":"%s","face":"r5","zoneRoot":true,
                 "exchanges":["reports.lab","reports.births","notices.member"],
                 "types":[%s],
                 "steps":[
                  {"code":"%s","slots":{"result":"Observation"},"awaits":"outcome"},
                  {"code":"%s","slots":{"result":"Observation"},"awaits":"none"},
                  {"code":"%s","slots":{"result":"Observation"}}]}"""
                .formatted(COUNTRY, observation, NOTIFY, REGISTER, SHARE));
        Files.writeString(dir.resolve(LAND + ".json"), """
                {"code":"%s","face":"r5","zoneRoot":true,"zone":"%s",
                 "exchanges":["reports.births","notices.member"],
                 "types":[%s],
                 "steps":[
                  {"code":"%s","slots":{"birth":"Observation"},"awaits":"outcome"},
                  {"code":"%s","slots":{"notice":"Observation"},"awaits":"outcome"}]}"""
                .formatted(LAND, COUNTRY, observation, BIRTHS, CONFIRM));
        Files.writeString(dir.resolve(HOSPITAL + ".json"), """
                {"code":"%s","face":"r5","zone":"%s","types":[%s]}"""
                .formatted(HOSPITAL, COUNTRY, observation));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r5","zone":"%s","types":[%s],
                 "steps":[{"code":"%s","slots":{"result":"Observation"}}]}"""
                .formatted(CLINIC, LAND, observation, RELEASE));
        UntilServed.scan(manager, COUNTRY, LAND);
        UntilServed.scan(manager, HOSPITAL, CLINIC);
        for (String tenant : List.of(COUNTRY, LAND, HOSPITAL, CLINIC)) {
            // A client that may ask for work, with no key enrolled to seal to.
            manager.authority(tenant).ensureClient("desk", "desk-secret", List.of("work"));
        }
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
    @DisplayName("a member asks its own zone, the root of the tree, on an exchange awaiting its "
            + "outcome: completed there, its run completes; failed there with a reason, its "
            + "run fails with that reason; and no reader needed a key")
    @Proving({DboPromises.ZONE_A_TENANTS_OWN_ZONE_IS_AN_END_OF_A_CROSSING,
            DboPromises.ZONE_AN_ASKER_WAITS_FOR_WHAT_ITS_EXCHANGE_DECLARES})
    void aMemberAsksItsRootZone() throws Exception {
        String done = ask(HOSPITAL, NOTIFY, "result", COUNTRY, "potassium");
        String doneAt = performed(COUNTRY, NOTIFY, done, null);
        assertEquals("completed", ended(HOSPITAL, done).status().wire());

        String refused = ask(HOSPITAL, NOTIFY, "result", COUNTRY, "sodium");
        performed(COUNTRY, NOTIFY, refused, "no such patient in the registry");
        Run failed = ended(HOSPITAL, refused);
        assertEquals("failed", failed.status().wire(), failed.toString());
        assertEquals("no such patient in the registry", failed.statusReason(), failed.toString());

        // The zone is the end it was, not a zone the work passed through.
        assertEquals(0, passedThrough(COUNTRY, crossingOf(HOSPITAL, done)),
                "the zone recorded the crossing as passing through itself");
        assertTrue(doneAt.startsWith(NOTIFY + "/crossing-"), doneAt);
    }

    @Test
    @Order(2)
    @DisplayName("a member asks its own zone under another zone, on a step only the lower "
            + "zone declares, and the zone above is not on the way")
    @Proving(DboPromises.ZONE_A_TENANTS_OWN_ZONE_IS_AN_END_OF_A_CROSSING)
    void aMemberAsksALowerZone() throws Exception {
        String asked = ask(CLINIC, BIRTHS, "birth", LAND, "a girl");
        performed(LAND, BIRTHS, asked, null);
        assertEquals("completed", ended(CLINIC, asked).status().wire());
        String crossing = crossingOf(CLINIC, asked);
        assertEquals(0, passedThrough(LAND, crossing), "the land recorded itself as passed");
        assertEquals(0, passedThrough(COUNTRY, crossing),
                "the work went above the lowest zone it needed");
    }

    @Test
    @Order(3)
    @DisplayName("a zone asks one of its members, and how the member's work ended comes back "
            + "to the zone")
    @Proving(DboPromises.ZONE_A_TENANTS_OWN_ZONE_IS_AN_END_OF_A_CROSSING)
    void aZoneAsksAMember() throws Exception {
        String asked = ask(LAND, CONFIRM, "notice", CLINIC, "registry corrected");
        performed(CLINIC, CONFIRM, asked, null);
        assertEquals("completed", ended(LAND, asked).status().wire());
    }

    @Test
    @Order(4)
    @DisplayName("on an exchange awaiting nothing, the asker's run completes once the work is "
            + "authored at the zone; and a host starts one in process, under the door's rules")
    @Proving({DboPromises.ZONE_AN_ASKER_WAITS_FOR_WHAT_ITS_EXCHANGE_DECLARES,
            DboPromises.ZONE_A_CROSSING_IS_STARTED_BY_A_HOST_OR_A_RESULT})
    void nothingAwaitedCompletesOnceAuthored() throws Exception {
        String asked = ask(HOSPITAL, REGISTER, "result", COUNTRY, "a death");
        assertEquals("completed", ended(HOSPITAL, asked).status().wire());
        assertTrue(runAt(COUNTRY, REGISTER + "/crossing-" + crossingOf(HOSPITAL, asked))
                .isPresent(), "nothing was authored at the zone");

        var hosted = manager.runInitiator().starting(HOSPITAL, REGISTER,
                Map.of("result", RunInitiator.Slot.object(OBSERVATION.formatted("a birth"))),
                "hosted-" + RUN, null, null, COUNTRY);
        assertEquals(201, hosted.status(), hosted.body());
        assertEquals("completed", ended(HOSPITAL, hosted.key()).status().wire());

        var sealedToNobody = manager.runInitiator().starting(HOSPITAL, SHARE,
                Map.of("result", RunInitiator.Slot.object(OBSERVATION.formatted("x"))),
                "nobody-" + RUN, null, null, COUNTRY);
        assertEquals(400, sealedToNobody.status(),
                "records were asked for with nobody to seal them to: " + sealedToNobody.body());
    }

    @Test
    @Order(5)
    @DisplayName("an owner failing work that awaits its records ends the asker failed, with "
            + "the reason, instead of leaving it open")
    @Proving(DboPromises.ZONE_AN_ASKER_WAITS_FOR_WHAT_ITS_EXCHANGE_DECLARES)
    void recordsRefusedEndTheAsker() throws Exception {
        String asked = ask(HOSPITAL, SHARE, "result", COUNTRY, "chloride");
        performed(COUNTRY, SHARE, asked, "nothing to share for this patient");
        Run failed = ended(HOSPITAL, asked);
        assertEquals("failed", failed.status().wire(), failed.toString());
        assertEquals("nothing to share for this patient", failed.statusReason());
    }

    @Test
    @Order(6)
    @DisplayName("a step's result starts a crossing to the tenant's zone as its part, and what "
            + "became of it is read under the run that started it")
    @Proving(DboPromises.ZONE_A_CROSSING_IS_STARTED_BY_A_HOST_OR_A_RESULT)
    void aResultStartsACrossingAsItsPart() throws Exception {
        var released = manager.runInitiator().starting(CLINIC, RELEASE,
                Map.of("result", RunInitiator.Slot.object(OBSERVATION.formatted("released"))),
                "release-" + RUN);
        assertEquals(201, released.status(), released.body());
        Lane clinic = lane(CLINIC, RELEASE);
        Run held = clinic.claim(runAt(CLINIC, released.key()).orElseThrow(),
                Duration.ofMinutes(5)).orElseThrow();
        Run closed = clinic.committed(held, null, List.of(), List.of(new Outcome.Part(BIRTHS,
                Map.of("birth", RunInitiator.Slot.object(OBSERVATION.formatted("a boy"))),
                "born-" + RUN, null, LAND)));
        assertEquals(null, closed.refused(), closed.toString());

        String part = BIRTHS + "/born-" + RUN;
        Run started = runAt(CLINIC, part).orElseThrow(() -> new AssertionError(
                "the result started no crossing"));
        assertEquals(held.key(), started.parent(), "the crossing is not the run's part");
        assertEquals(LAND, started.crossing().to());
        performed(LAND, BIRTHS, part, null);
        assertEquals("completed", ended(CLINIC, part).status().wire());

        List<String> parts;
        try (var asked = cloud.jengu.dbo.asking.Asking.at(manager.runtime(CLINIC).orElseThrow()
                .engine()).work().childrenOf(held.id()).stream()) {
            parts = asked.filter(run -> "completed".equals(run.status()))
                    .map(cloud.jengu.dbo.asking.Ongoing::key).toList();
        }
        assertEquals(List.of(part), parts,
                "the crossing's outcome is not read under the run that started it");
    }

    @Test
    @Order(7)
    @DisplayName("two crossings on one exchange, started by two results committing at once, "
            + "are both carried and both end as the zone's work did")
    @Proving(DboPromises.ZONE_EVERY_CROSSING_ASKED_IS_CARRIED)
    void twoCrossingsAtOnceAreBothCarried() throws Exception {
        allCarried(2);
    }

    @Test
    @Order(8)
    @DisplayName("forty crossings started at once, more than a page of work, are all carried")
    @Proving(DboPromises.ZONE_EVERY_CROSSING_ASKED_IS_CARRIED)
    void fortyCrossingsAtOnceAreAllCarried() throws Exception {
        allCarried(40);
    }

    /**
     * As many results of the clinic's own step, committed on as many threads
     * released together, each starting a crossing to the land as its part;
     * then every one performed there and every asker ended.
     */
    private void allCarried(int many) throws Exception {
        String batch = many + "-" + RUN;
        Lane clinic = lane(CLINIC, RELEASE);
        List<Run> held = new java.util.ArrayList<>();
        for (int i = 0; i < many; i++) {
            var released = manager.runInitiator().starting(CLINIC, RELEASE,
                    Map.of("result", RunInitiator.Slot.object(OBSERVATION.formatted("v" + i))),
                    "release-" + batch + "-" + i);
            assertEquals(201, released.status(), released.body());
            held.add(clinic.claim(runAt(CLINIC, released.key()).orElseThrow(),
                    Duration.ofMinutes(5)).orElseThrow());
        }
        var together = new java.util.concurrent.CountDownLatch(1);
        var threads = java.util.concurrent.Executors.newFixedThreadPool(many);
        List<java.util.concurrent.Future<Run>> committed = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < many; i++) {
                Run run = held.get(i);
                String key = "born-" + batch + "-" + i;
                committed.add(threads.submit(() -> {
                    together.await();
                    return clinic.committed(run, null, List.of(), List.of(new Outcome.Part(
                            BIRTHS, Map.of("birth", RunInitiator.Slot.object(
                                    OBSERVATION.formatted(key))), key, null, LAND)));
                }));
            }
            together.countDown();
            for (var each : committed) {
                assertEquals(null, each.get(2, java.util.concurrent.TimeUnit.MINUTES).refused());
            }
        } finally {
            threads.shutdownNow();
        }
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < many; i++) {
            parts.add(BIRTHS + "/born-" + batch + "-" + i);
        }
        for (String part : parts) {
            performed(LAND, BIRTHS, part, null);
        }
        for (String part : parts) {
            Run ended = ended(CLINIC, part);
            assertEquals("completed", ended.status().wire(), ended.toString());
        }
    }

    @Test
    @Order(9)
    @DisplayName("a participant refusing the work it was carried ends that run failed with its "
            + "reason, as its own act and for nobody to take again, and the asker's run fails "
            + "with the same reason; a participant failing hands the run back instead")
    @Proving({DboPromises.PROC_A_PARTICIPANT_REFUSES_ITS_RUN_WITH_A_REASON,
            DboPromises.ZONE_AN_ASKER_WAITS_FOR_WHAT_ITS_EXCHANGE_DECLARES})
    void aParticipantRefusesWithAReason() throws Exception {
        String refused = ask(HOSPITAL, NOTIFY, "result", COUNTRY, "magnesium");
        String at = answered(COUNTRY, NOTIFY, refused,
                Outcome.refusing("the registry holds no such patient"));
        Run ended = runAt(COUNTRY, at).orElseThrow();
        assertEquals("failed", ended.status().wire(), ended.toString());
        assertEquals("the registry holds no such patient", ended.refused(), ended.toString());
        assertEquals(PERFORMER.name(), ended.assignment().executor().name(),
                "the refusal is not the participant's act: " + ended);
        Run asker = ended(HOSPITAL, refused);
        assertEquals("failed", asker.status().wire(), asker.toString());
        assertEquals("the registry holds no such patient", asker.statusReason());

        String failed = ask(HOSPITAL, NOTIFY, "result", COUNTRY, "calcium");
        String failedAt = answered(COUNTRY, NOTIFY, failed,
                Outcome.failed("the registry did not answer"));
        Run handedBack = runAt(COUNTRY, failedAt).orElseThrow();
        assertTrue(handedBack.open(), "a failure ended the run: " + handedBack);
        assertEquals(null, handedBack.refused(), handedBack.toString());
        manager.scanOnce();
        assertTrue(runAt(HOSPITAL, failed).orElseThrow().open(),
                "a failure that may pass ended the asker");
    }

    // ── helpers ──

    /** Asks at a tenant's own door, with a client holding no key; the run's key. */
    private String ask(String asker, String step, String slot, String to, String what)
            throws Exception {
        HttpResponse<String> asked = http.send(HttpRequest.newBuilder(URI.create(
                                base(asker) + "/step/" + step))
                        .header("Authorization", "Bearer " + token(asker))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{\"" + slot
                                + "\":" + OBSERVATION.formatted(what) + "},\"crossing\":{\"to\":\""
                                + to + "\"}}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, asked.statusCode(), asked.body());
        return field(asked.body(), "key");
    }

    /**
     * The owner's participant taking the work the asker's run was carried to,
     * and completing it — or refusing it, saying why.
     *
     * @return the owner's run's key
     */
    private String performed(String owner, String step, String askerKey, String refusing)
            throws Exception {
        return answered(owner, step, askerKey,
                refusing == null ? Outcome.done() : Outcome.refusing(refusing));
    }

    /**
     * The same, the participant's service saying how it went, performed over
     * the owner's lane as a participant reaches it: across HTTP.
     */
    private String answered(String owner, String step, String askerKey, Outcome said)
            throws Exception {
        String asker = askerOf(askerKey);
        String key = step + "/crossing-" + crossingOf(asker, askerKey);
        Run arrived;
        try {
            arrived = until(owner, key);
        } catch (AssertionError none) {
            throw new AssertionError(none.getMessage() + "; the asker's run stands as "
                    + runAt(asker, askerKey).orElse(null), none);
        }
        if (said instanceof Outcome.Done) {
            Lane performing = lane(owner, step);
            performing.closed(performing.claim(arrived, Duration.ofMinutes(5)).orElseThrow());
            return key;
        }
        lane(owner, step);
        Lane overHttp = cloud.jengu.dbo.runner.http.HttpLane.to(URI.create(base(owner) + "/work"),
                () -> {
                    try {
                        return token(owner);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, owner, PERFORMER.name(), PERFORMER);
        Run held = overHttp.claim(arrived, Duration.ofMinutes(5)).orElseThrow();
        cloud.jengu.dbo.runner.Performing.performed(overHttp, held,
                new cloud.jengu.dbo.runner.StepService() {
                    @Override
                    public String step() {
                        return step;
                    }

                    @Override
                    public Outcome perform(cloud.jengu.dbo.runner.Work work) {
                        return said;
                    }
                }, Duration.ofMinutes(5));
        return key;
    }

    private static final Executor PERFORMER =
            new Executor("performer", "1", "example.zone", Scope.BASELINE);

    private Lane lane(String tenant, String step) {
        Lane lane = manager.fleetLane(tenant, step, PERFORMER).orElseThrow();
        lane.declare(new cloud.jengu.dbo.work.Declarations.Declared(
                step.substring(0, step.lastIndexOf('.')), step.substring(step.lastIndexOf('.') + 1),
                PERFORMER.name(), PERFORMER.version(), PERFORMER.provider(), PERFORMER.scope(),
                PERFORMER.name()));
        return lane;
    }

    /** The asker's run once it is over, the carrier having carried what came back. */
    private Run ended(String asker, String key) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Run run = runAt(asker, key).orElseThrow();
        while (run.open() && System.nanoTime() < giveUp) {
            manager.scanOnce();
            Thread.sleep(250);
            run = runAt(asker, key).orElseThrow();
        }
        return run;
    }

    private Run until(String tenant, String key) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Optional<Run> found = runAt(tenant, key);
        while (found.isEmpty() && System.nanoTime() < giveUp) {
            manager.scanOnce();
            Thread.sleep(250);
            found = runAt(tenant, key);
        }
        return found.orElseThrow(() -> new AssertionError("nothing arrived at " + tenant
                + " as " + key));
    }

    private Optional<Run> runAt(String tenant, String key) {
        return new Runs(manager.runtime(tenant).orElseThrow().engine()).byKey(key);
    }

    private String crossingOf(String asker, String key) {
        return runAt(asker, key).orElseThrow().crossing().id();
    }

    /** The tenant an asked-for run belongs to, by where it was asked: the one holding it. */
    private String askerOf(String key) {
        for (String tenant : List.of(HOSPITAL, CLINIC, LAND, COUNTRY)) {
            Optional<Run> run = runAt(tenant, key);
            if (run.isPresent() && run.get().crossing() != null
                    && run.get().crossing().askerRun() == null) {
                return tenant;
            }
        }
        throw new AssertionError("no tenant asked as " + key);
    }

    /** How many times a zone recorded a crossing as passing it. */
    private long passedThrough(String zone, String crossing) {
        return manager.runtime(zone).orElseThrow().engine().select(
                cloud.jengu.dbo.core.api.Criteria.of("AuditEntry").eq("run",
                        cloud.jengu.dbo.core.api.EnvelopeValue.of("crossing:" + crossing)))
                .size();
    }

    private String token(String tenant) throws Exception {
        String body = http.send(HttpRequest.newBuilder(URI.create(base(tenant) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=desk"
                                        + "&client_secret=desk-secret")).build(),
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
