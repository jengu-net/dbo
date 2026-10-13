package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.StepScheduler;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ward's steps on a clock, on two nodes over one database, told of each
 * tick by a scheduler the test turns by hand — a hosting framework's own, as
 * far as the store can tell.
 *
 * <p>A world of its own because the deployment is configured differently from
 * the shared one: it brings its own scheduler, and that is the seam this
 * proves. Nobody waits out a tick; the durable layer's own clock firing a tick
 * once is {@link TheStoresClockFiresATickOnceIT}'s.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATickStartsOneRunOnEveryNodeIT {

    /**
     * A ward of this run's own: a tenant's database is named by its code, so a
     * ward named the same on every run would be the last run's ward, ticks
     * and all, wherever the database server outlived it.
     */
    private static final String WARD = "ward-" + Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private static final String WALK = "ward.rounds.walk";
    private static final String TIDY = "ward.rounds.tidy";
    private static final String COUNT = "ward.rounds.count";
    private static final String NOTE = "ward.rounds.note";
    private static final String ASSIGN = "ward.messages.assign";
    private static final String CHASE = "ward.messages.chase";
    private static final String WRITE = "ward.messages.write";
    private static final String FULFIL = "ward.orders.fulfil";
    private static final String HANDLE = "ward.orders.handle";
    private static final String READ = "ward.messages.read";
    private static final String DECLARED = """
            {"code":"%s","face":"r5","types":[
              {"name":"Basic","identity":"internal","handling":"operational"},
              {"name":"Communication","identity":"internal","handling":"operational"},
              {"name":"CommunicationRequest","identity":"internal","handling":"operational"}],
             "steps":[
              {"code":"ward.rounds.walk",
               "starts":{"every":"0 * * * * *","missed":"once","keep":"P1D","where":"origin"}},
              {"code":"ward.rounds.tidy",
               "starts":{"every":"0 0 * * * *","missed":"skip","keep":"P1D","where":"origin"}},
              {"code":"ward.rounds.count",
               "starts":{"every":"0 0 * * * *","missed":"skip","keep":"P1D","where":"origin"}},
              {"code":"ward.rounds.note",
               "starts":{"every":"0 0 * * * *","missed":"skip","keep":"PT1S","where":"origin"}},
              {"code":"ward.messages.assign","slots":{"message":"Communication"},
               "starts":{"change":"Communication?status=on-hold","keep":"P1D",
                         "where":"origin"}},
              {"code":"ward.orders.fulfil","slots":{"order":"Reference(CommunicationRequest)"},
               "lasts":true,
               "starts":{"change":"CommunicationRequest?status=active","keep":"P1D",
                         "where":"origin"}},
              {"code":"ward.orders.handle","slots":{"order":"Reference(CommunicationRequest)"}},
              {"code":"ward.messages.write","slots":{"about":"Reference(Communication)"},
               "writes":["Communication"]},
              {"code":"ward.messages.read","slots":{"message":"Reference(Communication)"}},
              {"code":"ward.messages.chase","slots":{"message":"Communication"},
               "starts":{"due":"Communication?status=in-progress","at":"sent",
                         "after":"PT3S","keep":"P1D","where":"origin"}}]}
            """;

    private final HttpClient http = HttpClient.newHttpClient();
    private final byte[] kek = new byte[32];
    private PostgreSQLContainer<?> postgres;
    private LocalDatabasePerTenantProvisioner northProv;
    private LocalDatabasePerTenantProvisioner southProv;
    private TenantRuntimeManager north;
    private TenantRuntimeManager south;
    private final HandCrankedScheduler northClock = new HandCrankedScheduler();
    private final Map<TenantRuntimeManager, Path> declaredIn = new ConcurrentHashMap<>();
    private final HandCrankedScheduler southClock = new HandCrankedScheduler();

    /** Ten minutes back, on a minute: the tick both nodes are told of. */
    private final Instant tick = Instant.now().truncatedTo(ChronoUnit.MINUTES)
            .minus(10, ChronoUnit.MINUTES);
    /** Three hours back, on the hour: the hourly step's last tick before nodes go down. */
    private final Instant hourly = Instant.now().truncatedTo(ChronoUnit.HOURS)
            .minus(3, ChronoUnit.HOURS);

    @BeforeAll
    void twoNodesOverOneWard() throws Exception {
        new java.security.SecureRandom().nextBytes(kek);
        postgres = SharedPostgres.get();
        northProv = provisioner();
        southProv = provisioner();
        north = node(northProv, northClock);
        south = node(southProv, southClock);
        north.authority(WARD).ensureClient("rounds", "rounds-secret", List.of("work"));
        north.authority(WARD).ensureClient("desk", "desk-secret",
                List.of("system/*.read", "system/*.write"));
        north.authority(WARD).ensureClient("matron", "matron-secret",
                List.of("work", "supervise/" + TIDY));
    }

    private LocalDatabasePerTenantProvisioner provisioner() {
        return new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATickStartsOneRunOnEveryNodeIT"),
                postgres.getUsername(), postgres.getPassword());
    }

    private TenantRuntimeManager node(LocalDatabasePerTenantProvisioner prov,
            HandCrankedScheduler clock)
            throws Exception {
        Path dir = Files.createTempDirectory("dbo-ward");
        TenantRuntimeManager manager = new TenantRuntimeManager(dir, prov, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null), FhirVersions.installed())
                .stepScheduler(clock);
        Files.writeString(dir.resolve(WARD + ".json"), DECLARED.formatted(WARD));
        UntilServed.scan(manager, WARD);
        declaredIn.put(manager, dir);
        return manager;
    }

    @AfterAll
    void down() {
        for (TenantRuntimeManager m : new TenantRuntimeManager[] {north, south}) {
            if (m != null) {
                m.close();
            }
        }
        for (LocalDatabasePerTenantProvisioner p
                : new LocalDatabasePerTenantProvisioner[] {northProv, southProv}) {
            if (p != null) {
                SuiteDatabases.retire(p);
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("each node's clock is given the ward's calendar steps, and the steps read at "
            + "their door")
    @Proving({DboPromises.PROC_A_HOSTS_SCHEDULER_IS_TRUSTED_WITH_TIMING_ALONE,
            DboPromises.PROC_SELF_STARTING_STEPS_ARE_READ_AND_STEERED_AT_THEIR_DOOR})
    void theClockIsGivenTheCalendarSteps() throws Exception {
        Map<String, String> rules = Map.of(WALK, "0 * * * * *", TIDY, "0 0 * * * *",
                COUNT, "0 0 * * * *", NOTE, "0 0 * * * *");
        assertEquals(rules, northClock.rules.get(WARD));
        assertEquals(rules, southClock.rules.get(WARD));

        String status = steps(north, "rounds");
        assertTrue(status.contains("\"system\":\"dbo.tenant.system/serve/" + WARD + "\""), status);
        assertTrue(status.contains("\"every\":\"0 * * * * *\""), status);
        assertTrue(status.contains("\"paused\":false"), status);
    }

    @Test
    @Order(2)
    @DisplayName("a tick told to both nodes starts one run, keyed by its instant, under the "
            + "ward's system run")
    @Proving(DboPromises.PROC_A_STARTED_RUN_IS_ONE_PER_OCCASION)
    void aTickStartsOneRun() throws Exception {
        northClock.opened.get(WARD).due(WALK, tick);
        southClock.opened.get(WARD).due(WALK, tick);

        String expected = WALK + "/tick/" + tick;
        assertEquals(expected, field(entryOf(steps(north, "rounds"), WALK), "lastRun"));
        assertEquals(expected, field(entryOf(steps(south, "rounds"), WALK), "lastRun"),
                "the other node started a run of its own for the same tick");
        // At or after it: a minute that turned while the second node came up was
        // reckoned by that node as missed, since this clock is turned by hand
        // and told the first node of nothing.
        assertTrue(!Instant.parse(field(entryOf(steps(north, "rounds"), WALK), "lastTick"))
                .isBefore(tick), "the tick was not taken");
    }

    @Test
    @Order(3)
    @DisplayName("run now starts at once, as the asker's and under the system run, and the next "
            + "tick is still the one that was due")
    @Proving(DboPromises.PROC_SELF_STARTING_STEPS_ARE_READ_AND_STEERED_AT_THEIR_DOOR)
    void runNowStartsAtOnce() throws Exception {
        String before = field(entryOf(steps(north, "rounds"), WALK), "next");
        HttpResponse<String> now = post(north, WALK + "/now", "{}", "rounds");
        assertEquals(201, now.statusCode(), now.body());
        assertTrue(now.body().contains("\"key\":\"" + WALK + "/now/"), now.body());
        assertTrue(now.body().contains("\"parent\":\"dbo.tenant.system/serve/" + WARD + "\""),
                now.body());
        String entry = entryOf(steps(north, "rounds"), WALK);
        assertTrue(field(entry, "lastRun").startsWith(WALK + "/now/"), entry);
        String after = field(entry, "next");
        assertTrue(after.compareTo(before) >= 0 && Instant.parse(after)
                        .isBefore(Instant.now().plusSeconds(61)),
                "run now moved the next tick: " + before + " then " + after);
    }

    @Test
    @Order(4)
    @DisplayName("pausing needs the supervisory scope; paused, a tick starts nothing, and the "
            + "pause and the resume are acts under the system run")
    @Proving(DboPromises.PROC_SELF_STARTING_STEPS_ARE_READ_AND_STEERED_AT_THEIR_DOOR)
    void pauseStartsNothingUntilResume() throws Exception {
        HttpResponse<String> worker = post(north, TIDY + "/pause",
                "{\"because\":\"ward closed\"}", "rounds");
        assertEquals(403, worker.statusCode(), worker.body());
        HttpResponse<String> another = post(north, WALK + "/pause",
                "{\"because\":\"ward closed\"}", "matron");
        assertEquals(403, another.statusCode(),
                "supervising one step paused another: " + another.body());

        HttpResponse<String> paused = post(north, TIDY + "/pause",
                "{\"because\":\"ward closed for cleaning\"}", "matron");
        assertEquals(200, paused.statusCode(), paused.body());
        assertTrue(paused.body().contains("\"paused\":true"), paused.body());
        cloud.jengu.dbo.work.RunInitiator.Answer act = north.runInitiator()
                .answer(WARD, field(paused.body(), "id"));
        assertEquals(200, act.status(), act.body());
        assertTrue(act.body().contains("ward closed for cleaning"), act.body());

        Instant later = hourly.plus(1, ChronoUnit.HOURS);
        northClock.opened.get(WARD).due(TIDY, later);
        String entry = entryOf(steps(north, "rounds"), TIDY);
        assertEquals("null", raw(entry, "lastRun"), "a paused step started a run: " + entry);
        assertTrue(entry.contains("\"paused\":true"), entry);

        HttpResponse<String> resumed = post(north, TIDY + "/resume",
                "{\"because\":\"ward open\"}", "matron");
        assertEquals(200, resumed.statusCode(), resumed.body());
        assertNotEquals(field(paused.body(), "act"), field(resumed.body(), "act"));
        Instant again = later.plus(1, ChronoUnit.HOURS);
        southClock.opened.get(WARD).due(TIDY, again);
        assertEquals(TIDY + "/tick/" + again,
                field(entryOf(steps(north, "rounds"), TIDY), "lastRun"));
    }

    @Test
    @Order(5)
    @DisplayName("a message changed to what the step's search finds starts one run of it, and "
            + "one the search does not find starts none")
    @Proving(DboPromises.PROC_A_STEP_STARTS_ON_A_MATCHING_CHANGE)
    void aMatchingChangeStartsOneRun() throws Exception {
        write("{\"resourceType\":\"Communication\",\"status\":\"preparation\"}");
        String held = write("{\"resourceType\":\"Communication\",\"status\":\"on-hold\"}");

        String expected = ASSIGN + "/change/" + held + "/1";
        String entry = until(ASSIGN, expected);
        assertEquals(expected, field(entry, "lastRun"), entry);
        assertEquals("1", number(entry, "started"),
                "a message the search does not find started a run too: " + entry);
    }

    @Test
    @Order(6)
    @DisplayName("a message whose moment has passed starts a run at once, one whose moment is "
            + "coming starts one when it comes, and one answered before then starts none")
    @Proving(DboPromises.PROC_A_STEP_STARTS_AT_A_MOMENT_ITS_RECORD_NAMES)
    void aMomentStartsOneRunWhenItComes() throws Exception {
        Instant now = Instant.now();
        // Written before the overdue one, so the feed hands it over first: by
        // the time the overdue one has run, this one is matched and asleep.
        Instant answeredDue = now.plusSeconds(3).plusSeconds(3);
        String answered = write(message(now.plusSeconds(3)));
        String overdue = write(message(now.minus(1, ChronoUnit.HOURS)));
        until(CHASE, CHASE + "/due/" + overdue);
        assertTrue(Instant.now().isBefore(answeredDue.minusSeconds(1)),
                "the overdue message took until the answered one was due to run, so this "
                        + "could not answer it while it waited");
        HttpResponse<String> done = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD
                                        + "/fhir/Communication/" + answered))
                        .header("Authorization", "Bearer " + token(north, "desk"))
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"resourceType\":"
                                + "\"Communication\",\"id\":\"" + answered + "\","
                                + "\"status\":\"completed\",\"sent\":\""
                                + now.plusSeconds(3).truncatedTo(ChronoUnit.SECONDS) + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, done.statusCode(), done.body());
        // Due after the answered one, so by the time it runs the answered one
        // has woken and looked again.
        String later = write(message(now.plusSeconds(6)));

        String entry = until(CHASE, CHASE + "/due/" + later);
        assertEquals("2", number(entry, "started"),
                "the overdue and the later message are two runs, and the answered one is "
                        + "none: " + entry);
        assertTrue(!overdue.equals(later), entry);
    }

    @Test
    @Order(7)
    @DisplayName("ticks missed while every node was down are what each step declares on return: "
            + "one run for the last of them, or none, and both say how many")
    @Proving({DboPromises.PROC_A_MISSED_TICK_IS_WHAT_ITS_STEP_SAYS,
            DboPromises.PROC_A_STARTED_RUN_IS_KEPT_AS_LONG_AS_ITS_STEP_SAYS})
    void missedTicksAreWhatTheStepSays() throws Exception {
        // Two runs of the step kept a second: one over, one still open.
        String over = field(post(north, NOTE + "/now", "{}", "rounds").body(), "run");
        String open = field(post(north, NOTE + "/now", "{}", "rounds").body(), "run");
        HttpResponse<String> closed = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD + "/run/"
                                        + over + "/done"))
                        .header("Authorization", "Bearer " + token(north, "rounds"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, closed.statusCode(), closed.body());

        // The hourly step's last tick, three hours back, before every node goes;
        // the minute step's was `tick`, ten minutes back.
        northClock.opened.get(WARD).due(COUNT, hourly);
        String counted = field(entryOf(steps(north, "rounds"), COUNT), "lastRun");
        Instant walkedLast = Instant.parse(field(entryOf(steps(north, "rounds"), WALK),
                "lastTick"));
        north.close();
        south.close();
        north = node(northProv, northClock);

        String walk = entryOf(steps(north, "rounds"), WALK);
        String ranFor = field(walk, "lastRun");
        assertTrue(ranFor.startsWith(WALK + "/tick/"), walk);
        Instant last = Instant.parse(ranFor.substring((WALK + "/tick/").length()));
        assertTrue(last.isAfter(Instant.now().minusSeconds(120)),
                "the minute step was not run once, for the last tick missed: " + walk);
        long minutesAway = java.time.Duration.between(walkedLast, Instant.now()).toMinutes();
        assertTrue(Long.parseLong(number(walk, "missed")) >= Math.max(1, minutesAway - 1),
                "fewer ticks missed than minutes went by since the last one: " + walk);
        assertEquals("0", number(walk, "skipped"), walk);

        String count = entryOf(steps(north, "rounds"), COUNT);
        assertEquals(counted, field(count, "lastRun"),
                "a step that skips missed ticks ran: " + count);
        assertEquals("3", number(count, "missed"), count);
        assertEquals("3", number(count, "skipped"), count);

        assertEquals(404, north.runInitiator().answer(WARD, over).status(),
                "a run over and older than its step keeps it is still held");
        assertEquals(200, north.runInitiator().answer(WARD, open).status(),
                "a run still open was let go with the ones that are over");
        String note = entryOf(steps(north, "rounds"), NOTE);
        assertEquals("1", number(note, "removed"), note);
    }

    @Test
    @Order(8)
    @DisplayName("a cadence redeclared is taken where the ward stands: the same clock is given "
            + "the new rule, and nothing is rebuilt")
    @Proving(DboPromises.PROC_A_CADENCE_CHANGES_WHERE_THE_TENANT_STANDS)
    void aCadenceChangesWhereTheWardStands() throws Exception {
        StepScheduler.Tick standing = northClock.opened.get(WARD);
        Files.writeString(declaredIn.get(north).resolve(WARD + ".json"),
                DECLARED.formatted(WARD).replace("\"every\":\"0 * * * * *\"", "\"every\":\"0 */2 * * * *\""));
        north.scanOnce();

        assertEquals("0 */2 * * * *", northClock.rules.get(WARD).get(WALK),
                "the new cadence never reached the clock: " + northClock.rules);
        assertTrue(standing == northClock.opened.get(WARD),
                "the ward was rebuilt to take a new cadence: its clock was opened again");
        assertTrue(entryOf(steps(north, "rounds"), WALK).contains("\"every\":\"0 */2 * * * *\""));
    }

    @Test
    @Order(9)
    @DisplayName("an application starts a run as part of another, which reads as that run's "
            + "part; one naming a run the ward does not hold is refused by name")
    @Proving(DboPromises.PROC_A_RUN_IS_STARTED_AS_A_PART_OF_ANOTHER)
    void anApplicationStartsAPart() throws Exception {
        String about = "Communication/" + write("{\"resourceType\":\"Communication\","
                + "\"status\":\"preparation\"}");
        var initiator = north.runInitiator();
        var asked = initiator.starting(WARD, WRITE, Map.of("about",
                cloud.jengu.dbo.work.RunInitiator.Slot.reference(about)), "asked-for");
        assertEquals(201, asked.status(), asked.body());
        var part = initiator.starting(WARD, READ, Map.of("message",
                        cloud.jengu.dbo.work.RunInitiator.Slot.reference(about)), "read-for",
                null, asked.key());
        assertEquals(201, part.status(), part.body());
        assertEquals(List.of(part.key()), partsOf(asked.run()),
                "the run started for another is not its part");

        var stray = initiator.starting(WARD, READ, Map.of("message",
                        cloud.jengu.dbo.work.RunInitiator.Slot.reference(about)), "stray",
                null, WRITE + "/no-such-run");
        assertEquals(422, stray.status(), stray.body());
        assertTrue(stray.body().contains("no-such-run"), stray.body());
    }

    @Test
    @Order(10)
    @DisplayName("a result starts its parts once its records are written, and a run started by "
            + "a change that result wrote is its part too; a part the ward would not start "
            + "refuses the whole result, records and all")
    @Proving({DboPromises.PROC_A_RUN_IS_STARTED_AS_A_PART_OF_ANOTHER,
            DboPromises.PROC_A_RUN_STARTED_BY_A_CHANGE_IS_A_PART_OF_THE_RUN_THAT_WROTE_IT})
    void aResultsPartsAndWhatItsChangeStarted() throws Exception {
        String about = "Communication/" + write("{\"resourceType\":\"Communication\","
                + "\"status\":\"preparation\"}");
        var initiator = north.runInitiator();
        var writing = initiator.starting(WARD, WRITE, Map.of("about",
                cloud.jengu.dbo.work.RunInitiator.Slot.reference(about)), "writes-a-hold");
        assertEquals(201, writing.status(), writing.body());

        cloud.jengu.dbo.runner.Lane lane = performing();
        cloud.jengu.dbo.work.Run held = lane.claim(runOf(writing.key()),
                java.time.Duration.ofMinutes(5)).orElseThrow();
        cloud.jengu.dbo.work.Run ended = lane.committed(held, null,
                List.of(cloud.jengu.dbo.runner.Outcome.Write.create(
                        "{\"resourceType\":\"Communication\",\"status\":\"on-hold\"}")),
                List.of(new cloud.jengu.dbo.runner.Outcome.Part(READ, Map.of("message",
                        cloud.jengu.dbo.work.RunInitiator.Slot.reference(about)), "read-it",
                        null)));
        assertEquals(null, ended.refused(), "the result was refused: " + ended);
        assertEquals("completed", ended.status().wire(), ended.toString());

        // Its part at once; the run its change started once the feed is read.
        String part = READ + "/read-it";
        long giveUp = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        List<String> parts = partsOf(ended.id());
        while (parts.stream().noneMatch(key -> key.startsWith(ASSIGN + "/change/"))
                && System.nanoTime() < giveUp) {
            Thread.sleep(250);
            parts = partsOf(ended.id());
        }
        assertTrue(parts.contains(part), "the result's part is not its part: " + parts);
        assertTrue(parts.stream().anyMatch(key -> key.startsWith(ASSIGN + "/change/")),
                "the run the result's change started is not its part: " + parts);

        // A part the ward would not start: the result is refused whole.
        var refusing = initiator.starting(WARD, WRITE, Map.of("about",
                cloud.jengu.dbo.work.RunInitiator.Slot.reference(about)), "starts-a-stranger");
        long before = count("Communication?status=entered-in-error");
        cloud.jengu.dbo.work.Run refusedRun = lane.committed(
                lane.claim(runOf(refusing.key()), java.time.Duration.ofMinutes(5)).orElseThrow(),
                null, List.of(cloud.jengu.dbo.runner.Outcome.Write.create(
                        "{\"resourceType\":\"Communication\",\"status\":\"entered-in-error\"}")),
                List.of(new cloud.jengu.dbo.runner.Outcome.Part("ward.nobody.declared",
                        Map.of(), null, null)));
        assertTrue(refusedRun.refused() != null
                        && refusedRun.refused().contains("ward.nobody.declared"),
                "a part the ward does not offer was not refused by name: " + refusedRun);
        assertEquals(before, count("Communication?status=entered-in-error"),
                "a refused result's records were written");
        assertEquals(List.of(), partsOf(refusedRun.id()), "a refused result started a part");
    }

    @Test
    @Order(11)
    @DisplayName("an order opens work that lasts: received, found again when the order is "
            + "written again, accepted, in progress once a part starts, never claimed, moved "
            + "by its parts' results to a stage and to its end, and read as FHIR's fulfilment "
            + "Task")
    @Proving({DboPromises.PROC_WORK_THAT_LASTS_IS_OPENED_FOR_ITS_REQUEST,
            DboPromises.PROC_WORK_THAT_LASTS_IS_MOVED_BY_ITS_PARTS,
            DboPromises.PROC_WORK_THAT_LASTS_READS_AS_A_FULFILMENT_TASK})
    void anOrderIsWorkThatLasts() throws Exception {
        String order = "CommunicationRequest/" + writeAs("CommunicationRequest",
                "{\"resourceType\":\"CommunicationRequest\",\"status\":\"active\","
                        + "\"intent\":\"order\"}");
        String key = FULFIL + "/" + order;
        var lasting = until(key);
        assertEquals("received", lasting.status().wire(), lasting.toString());
        assertTrue(lasting.lasting(), lasting.toString());

        // The order changed and still active: the same work, not a second.
        update(order, "{\"resourceType\":\"CommunicationRequest\",\"id\":\""
                + order.substring(order.indexOf('/') + 1) + "\",\"status\":\"active\","
                + "\"intent\":\"order\",\"priority\":\"urgent\"}");
        Thread.sleep(3_000);
        try (var asked = cloud.jengu.dbo.asking.Asking.at(north.runtime(WARD).orElseThrow()
                .engine()).work().ofStep("fulfil").stream()) {
            assertEquals(1, asked.filter(run -> run.key().startsWith(FULFIL + "/" + order))
                    .count(), "the order written again opened its work again");
        }

        assertEquals(200, say(lasting.id(), "accept", "{\"because\":\"we take it\"}")
                .statusCode());
        assertEquals("accepted", runOf(key).status().wire());
        String open = task(lasting.id());
        assertTrue(open.contains("\"status\":\"accepted\"")
                        && !open.contains("requestedPerformer") && !open.contains("performerType"),
                "work that lasts offered itself to performers while open: " + open);

        // Nobody takes it, not even a participant holding its own step.
        assertTrue(performing(FULFIL, "fulfil").claim(runOf(key),
                        java.time.Duration.ofMinutes(5)).isEmpty(),
                "work that lasts was claimed like a task");
        cloud.jengu.dbo.runner.Lane handling = performing(HANDLE, "handle");

        var initiator = north.runInitiator();
        var measuring = initiator.starting(WARD, HANDLE, Map.of("order",
                cloud.jengu.dbo.work.RunInitiator.Slot.reference(order)), "measure", null, key);
        assertEquals(201, measuring.status(), measuring.body());
        assertEquals("in-progress", runOf(key).status().wire(),
                "a part started and the work did not say it was in progress");

        cloud.jengu.dbo.work.Run held = handling.claim(runOf(measuring.key()),
                java.time.Duration.ofMinutes(5)).orElseThrow();
        assertEquals(key, handling.partOf(held).orElseThrow().key(),
                "the part cannot read the work it is a part of");
        handling.committed(held, null, List.of(), List.of(),
                new cloud.jengu.dbo.runner.Outcome.Said("measured", null, "values in"));
        assertEquals("measured", runOf(key).milestone().name());

        var releasing = initiator.starting(WARD, HANDLE, Map.of("order",
                cloud.jengu.dbo.work.RunInitiator.Slot.reference(order)), "release", null, key);
        handling.committed(handling.claim(runOf(releasing.key()),
                        java.time.Duration.ofMinutes(5)).orElseThrow(), null, List.of(), List.of(),
                new cloud.jengu.dbo.runner.Outcome.Said(null, "completed", "verified, released"));
        var ended = runOf(key);
        assertEquals("completed", ended.status().wire(), ended.toString());
        assertEquals("verified, released", ended.statusReason(), ended.toString());

        String fulfilment = task(lasting.id());
        assertTrue(fulfilment.contains("\"status\":\"completed\""), fulfilment);
        assertTrue(fulfilment.contains("\"reference\":\"" + order + "\""), fulfilment);
        assertTrue(fulfilment.contains("\"instantiatesCanonical\":\"urn:dbo:step:" + FULFIL
                + "\""), fulfilment);
        assertTrue(fulfilment.contains("\"code\":\"measured\""), fulfilment);
    }

    @Test
    @Order(12)
    @DisplayName("work that lasts is rejected only with a reason, said why, and once over is "
            + "neither accepted nor moved by a part's result")
    @Proving(DboPromises.PROC_WORK_THAT_LASTS_IS_MOVED_BY_ITS_PARTS)
    void rejectedWorkSaysWhy() throws Exception {
        String order = "CommunicationRequest/" + writeAs("CommunicationRequest",
                "{\"resourceType\":\"CommunicationRequest\",\"status\":\"active\","
                        + "\"intent\":\"order\"}");
        String key = FULFIL + "/" + order;
        var lasting = until(key);
        assertEquals(400, say(lasting.id(), "reject", "{}").statusCode(),
                "rejected without a reason");
        assertEquals(200, say(lasting.id(), "reject",
                "{\"because\":\"the specimen was haemolysed\"}").statusCode());
        var rejected = runOf(key);
        assertEquals("rejected", rejected.status().wire());
        assertEquals("the specimen was haemolysed", rejected.statusReason());
        assertEquals(409, say(lasting.id(), "accept", "{\"because\":\"after all\"}")
                .statusCode(), "rejected work was accepted");

        var late = north.runInitiator().starting(WARD, HANDLE, Map.of("order",
                cloud.jengu.dbo.work.RunInitiator.Slot.reference(order)), "late", null, key);
        cloud.jengu.dbo.runner.Lane handling = performing(HANDLE, "handle");
        var refused = handling.committed(handling.claim(runOf(late.key()),
                        java.time.Duration.ofMinutes(5)).orElseThrow(), null, List.of(), List.of(),
                new cloud.jengu.dbo.runner.Outcome.Said("measured", null, "too late"));
        assertTrue(refused.refused() != null && refused.refused().contains("over"),
                "a part's result moved work that was over: " + refused);
        assertEquals(null, runOf(key).milestone(), "rejected work reached a stage");
    }

    private String task(String id) throws Exception {
        HttpResponse<String> task = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD + "/fhir/Task/"
                                        + id))
                        .header("Authorization", "Bearer " + token(north, "desk")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, task.statusCode(), task.body());
        return task.body();
    }

    private cloud.jengu.dbo.work.Run until(String key) throws InterruptedException {
        var runs = new cloud.jengu.dbo.work.Runs(north.runtime(WARD).orElseThrow().engine());
        long giveUp = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        var found = runs.byKey(key);
        while (found.isEmpty() && System.nanoTime() < giveUp) {
            Thread.sleep(250);
            found = runs.byKey(key);
        }
        return found.orElseThrow(() -> new AssertionError("no work was opened as " + key));
    }

    private HttpResponse<String> say(String id, String act, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + north.port()
                                + "/t/" + WARD + "/run/" + id + "/" + act))
                        .header("Authorization", "Bearer " + token(north, "rounds"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String writeAs(String type, String document) throws Exception {
        HttpResponse<String> written = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD + "/fhir/" + type))
                        .header("Authorization", "Bearer " + token(north, "desk"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(document)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, written.statusCode(), written.body());
        return field(written.body(), "id");
    }

    private void update(String reference, String document) throws Exception {
        HttpResponse<String> written = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD + "/fhir/"
                                        + reference))
                        .header("Authorization", "Bearer " + token(north, "desk"))
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString(document)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, written.statusCode(), written.body());
    }

    /** The ward's own lane for the writing step, as a participant holding it. */
    private cloud.jengu.dbo.runner.Lane performing() {
        return performing(WRITE, "write");
    }

    private cloud.jengu.dbo.runner.Lane performing(String stepCode, String step) {
        cloud.jengu.dbo.work.Executor writer = new cloud.jengu.dbo.work.Executor("writer", "1",
                "example.ward", cloud.jengu.dbo.work.Scope.BASELINE);
        cloud.jengu.dbo.runner.Lane lane = north.fleetLane(WARD, stepCode, writer).orElseThrow();
        lane.declare(new cloud.jengu.dbo.work.Declarations.Declared(
                stepCode.substring(0, stepCode.lastIndexOf('.')), step,
                writer.name(), writer.version(), writer.provider(), writer.scope(),
                writer.name()));
        return lane;
    }

    private cloud.jengu.dbo.work.Run runOf(String key) {
        return new cloud.jengu.dbo.work.Runs(north.runtime(WARD).orElseThrow().engine())
                .byKey(key).orElseThrow();
    }

    /** The keys of a run's parts, as an application embedding the store asks for them. */
    private List<String> partsOf(String id) {
        try (var asked = cloud.jengu.dbo.asking.Asking.at(north.runtime(WARD).orElseThrow()
                .engine()).work().childrenOf(id).stream()) {
            return asked.map(cloud.jengu.dbo.asking.Ongoing::key).sorted().toList();
        }
    }

    private long count(String search) throws Exception {
        HttpResponse<String> counted = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD + "/fhir/"
                                        + search + "&_summary=count"))
                        .header("Authorization", "Bearer " + token(north, "desk")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, counted.statusCode(), counted.body());
        return Long.parseLong(number(counted.body(), "total"));
    }

    // ── the door ──

    private static String message(Instant sent) {
        return "{\"resourceType\":\"Communication\",\"status\":\"in-progress\","
                + "\"sent\":\"" + sent.truncatedTo(ChronoUnit.SECONDS) + "\"}";
    }

    /** A record written at the ward's records door, by the desk; its id. */
    private String write(String document) throws Exception {
        HttpResponse<String> written = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + north.port() + "/t/" + WARD
                                        + "/fhir/Communication"))
                        .header("Authorization", "Bearer " + token(north, "desk"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(document)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, written.statusCode(), written.body());
        return field(written.body(), "id");
    }

    /** A step's entry once its last run is the one named, or as it stands after a while. */
    private String until(String step, String lastRun) throws Exception {
        long giveUp = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        String entry = entryOf(steps(north, "rounds"), step);
        while (!entry.contains("\"lastRun\":\"" + lastRun + "\"")
                && System.nanoTime() < giveUp) {
            Thread.sleep(250);
            entry = entryOf(steps(north, "rounds"), step);
        }
        return entry;
    }

    private String steps(TenantRuntimeManager node, String client) throws Exception {
        HttpResponse<String> read = http.send(HttpRequest.newBuilder(URI.create(base(node)))
                        .header("Authorization", "Bearer " + token(node, client)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        return read.body();
    }

    private HttpResponse<String> post(TenantRuntimeManager node, String path, String body,
            String client) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(node) + "/" + path))
                        .header("Authorization", "Bearer " + token(node, client))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String token(TenantRuntimeManager node, String client) throws Exception {
        String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + node.port() + "/t/" + WARD + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=" + client
                                        + "&client_secret=" + client + "-secret")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return field(body, "access_token");
    }

    private static String base(TenantRuntimeManager node) {
        return "http://127.0.0.1:" + node.port() + "/t/" + WARD + "/steps";
    }

    /** One step's entry out of the door's answer. */
    private static String entryOf(String status, String step) {
        int at = status.indexOf("{\"step\":\"" + step + "\"");
        assertTrue(at >= 0, step + " is not among " + status);
        int end = status.indexOf("\"removed\":", at);
        return status.substring(at, status.indexOf('}', end) + 1);
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }

    private static String number(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }

    private static String raw(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(null|\"[^\"]*\")").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }
}
