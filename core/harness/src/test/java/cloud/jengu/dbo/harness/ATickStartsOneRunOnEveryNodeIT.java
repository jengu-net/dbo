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

    private static final String WARD = "ward";
    private static final String WALK = "ward.rounds.walk";
    private static final String TIDY = "ward.rounds.tidy";
    private static final String COUNT = "ward.rounds.count";
    private static final String NOTE = "ward.rounds.note";
    private static final String ASSIGN = "ward.messages.assign";
    private static final String CHASE = "ward.messages.chase";
    private static final String DECLARED = """
            {"code":"ward","face":"r5","types":[
              {"name":"Basic","identity":"internal","handling":"operational"},
              {"name":"Communication","identity":"internal","handling":"operational"}],
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
        Files.writeString(dir.resolve(WARD + ".json"), DECLARED);
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
                p.close();
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
        assertTrue(status.contains("\"system\":\"dbo.tenant.system/serve/ward\""), status);
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
        assertEquals(tick.toString(), field(entryOf(steps(north, "rounds"), WALK), "lastTick"));
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
        assertTrue(now.body().contains("\"parent\":\"dbo.tenant.system/serve/ward\""),
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
        north.close();
        south.close();
        north = node(northProv, northClock);

        String walk = entryOf(steps(north, "rounds"), WALK);
        String ranFor = field(walk, "lastRun");
        assertTrue(ranFor.startsWith(WALK + "/tick/"), walk);
        Instant last = Instant.parse(ranFor.substring((WALK + "/tick/").length()));
        assertTrue(last.isAfter(Instant.now().minusSeconds(120)),
                "the minute step was not run once, for the last tick missed: " + walk);
        assertTrue(Long.parseLong(number(walk, "missed")) >= 9, walk);
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
                DECLARED.replace("\"every\":\"0 * * * * *\"", "\"every\":\"0 */2 * * * *\""));
        north.scanOnce();

        assertEquals("0 */2 * * * *", northClock.rules.get(WARD).get(WALK),
                "the new cadence never reached the clock: " + northClock.rules);
        assertTrue(standing == northClock.opened.get(WARD),
                "the ward was rebuilt to take a new cadence: its clock was opened again");
        assertTrue(entryOf(steps(north, "rounds"), WALK).contains("\"every\":\"0 */2 * * * *\""));
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
