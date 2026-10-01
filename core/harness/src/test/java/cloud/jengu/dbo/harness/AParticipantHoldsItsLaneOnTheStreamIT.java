package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.stream.StreamLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
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
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-ON-THE-STREAM: a deployment whose participants hold their lanes over
 * its own durable substrate instead of over HTTP.
 *
 * <p>A service that connects to the substrate and to nothing else claims,
 * reads and closes work exactly as one over HTTP does, and cannot tell from
 * inside which carried it. It is woken when work appears rather than waiting
 * out its tick. And the substrate is a shared plane, so what crosses it is
 * looked for there afterwards: the manifest, readable, because routing is what
 * it is for, and never a document, an identifying value or a credential. A
 * payload too large for a message travels beside it, by reference, in the same
 * sealed form it would have had inside it.
 *
 * <p><b>Not on Rowling Land, and the substrate is why.</b> The stream door is
 * opened by giving the runtime a substrate before a tenant is served, and each
 * tenant's door is a durable-workflow instance of its own. Rowling Land given
 * a substrate ran every story about two and a half times slower, because a
 * world of twenty tenants is twenty such instances. So this deployment is the
 * story's own: one runtime, one substrate, and one tenant whose doors every
 * leg walks through.
 *
 * <p><b>The legs share the plane, and the order is chosen for it.</b> Every
 * leg leaves rows on the substrate, so a leg that reads the whole plane reads
 * what the legs before it left too. For the claims that nothing readable is
 * there, that is stronger rather than weaker, and each leg looks for a marker
 * or a run key only it wrote. Two claims are about one leg's own traffic and
 * are ordered so that nothing else has happened yet: the large payload goes
 * first, because its leg asserts that some of the door's answers were set
 * aside and some were not, and an earlier leg's small answers would satisfy
 * the second half on their own. The wake-up goes before any run of its step
 * exists, because it holds that a runner on the stream finds nothing on its
 * first cycle, and a run left claimable by an earlier leg would be performed
 * there and say nothing about a wake-up.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AParticipantHoldsItsLaneOnTheStreamIT {

    private static final String TENANT = "streamhost";

    private static final String ASSAY_STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY =
            StepDeclaration.of(ASSAY_STEP, "1.0", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen");

    private static final String IMAGE_STEP = "dbo.lab.image";
    private static final StepDeclaration IMAGE =
            StepDeclaration.of(IMAGE_STEP, "1.0", WorkModel.DOMAIN)
                    .taking("scan", "https://meristem.example/shape/scan");

    /** Nobody writes this by accident, so finding it anywhere is evidence. */
    private static final String SPILLED_MARKER = "spilled-marker-4f2a-never-written-by-accident";
    /** Nobody writes this by accident either, and it is a different one. */
    private static final String PLAINTEXT_MARKER = "plaintext-marker-7c1e-never-written-by-accident";
    private static final String IDENTIFYING = "37001010021";

    /**
     * Several times over the threshold, and built from one repeated run of
     * text so the document is large for the reason an imaging payload is:
     * there is a lot of it.
     *
     * <p>It costs about two seconds, as long as the assertion on it compares
     * bytes rather than pulling the text back out with a regex.
     */
    private static final int BIG = 400 * 1024;

    /**
     * What no row of the substrate's own tables may reach. Above the verbs —
     * a poll's answer, a manifest, a run — and well below the payload, so it
     * separates the two without being a second copy of the spill's own
     * threshold, which it would then only ever agree with.
     */
    private static final int TOO_BIG_FOR_THE_PLANE = 48 * 1024;

    /**
     * Long enough that the poll cannot be what woke the runner.
     *
     * <p>The whole proof is the gap between this and {@link #WAKE_UP_PATIENCE}:
     * a runner that waited out its tick would miss the deadline by an order of
     * magnitude, so a pass cannot be a slow machine getting lucky.
     */
    private static final Duration NEVER_POLLED_IN_TIME = Duration.ofMinutes(10);
    /** How long a wake-up is given to cross the substrate and be acted on. */
    private static final Duration WAKE_UP_PATIENCE = Duration.ofSeconds(30);

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static PGSimpleDataSource substrate;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static ObjectStore engine;
    static Runs runs;
    /** The analyser's keys: it holds the assay over HTTP and over the stream. */
    static KeyPair sealing;
    static KeyPair signing;
    /** The imager's keys: it holds the imaging step, over the stream only. */
    static KeyPair imagerSealing;
    static KeyPair imagerSigning;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-on-the-stream");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AParticipantHoldsItsLaneOnTheStreamIT"),
                postgres.getUsername(), postgres.getPassword());
        substrate = new PGSimpleDataSource();
        substrate.setUrl(SharedPostgres.urlFor("AParticipantHoldsItsLaneOnTheStreamIT_substrate"));
        substrate.setUser(postgres.getUsername());
        substrate.setPassword(postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // Before the tenant is served, so the door opens with it: a door that
        // arrived afterwards would have nothing to publish a wake-up on.
        manager.substrate(substrate);
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);

        manager.authority(TENANT).ensureClient("courier", "courier-secret",
                List.of("work/" + ASSAY_STEP));
        sealing = KeyWrap.newParticipantKeyPair();
        signing = SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("analyser", "analyser-secret",
                List.of("work/" + ASSAY_STEP), ParticipantKey.of(sealing.getPublic()),
                SigningKey.of(signing.getPublic()));
        imagerSealing = KeyWrap.newParticipantKeyPair();
        imagerSigning = SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("imager", "imager-secret",
                List.of("work/" + IMAGE_STEP), ParticipantKey.of(imagerSealing.getPublic()),
                SigningKey.of(imagerSigning.getPublic()));

        courier().introduce(ASSAY);
        HttpLane.to(laneUri, () -> token("imager", "imager-secret"), TENANT, "imager",
                executor("imager")).introduce(IMAGE);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    // ------------------------------------------- a large payload travels beside

    /*
     * An answer on this wire is an event on the door's workflow, which is a
     * row in the substrate's own tables. That is right for a verb's answer and
     * wrong for a run's inputs: the substrate re-reads a workflow's inputs when
     * it recovers one, keeps them under its retention rather than the
     * tenant's, and Postgres will TOAST a large value into a side store with
     * none of a blob store's lifecycle. So above a threshold the bytes wait
     * beside the door and the message carries their key.
     *
     * That the large payload arrives and opens is most of it — but a spill
     * that never happened would pass that on its own, so the plane is read to
     * show the bytes were not in the message and the note that replaced them
     * was. And a spill that happened to everything would pass both, so a
     * small answer is driven through the same door and must not have spilled:
     * otherwise the threshold is decorative and every poll pays for a table.
     */
    @Test
    @Order(1)
    @DisplayName("a payload too large for the message arrives whole, having travelled beside "
            + "it, and a small one does not pay for that")
    @Proving({DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE, DboPromises.WF_TWO_PLANES})
    void theLargeOneSpillsAndTheSmallOneDoesNot() throws Exception {
        String big = engine.put(PutRequest.create("Basic", document(SPILLED_MARKER, BIG))).id();
        String small = engine.put(PutRequest.create("Basic", document(SPILLED_MARKER, 64))).id();
        Run heavy = runs.of(IMAGE, RunKind.PIPELINE, "carries-a-large-scan",
                Map.of("scan", "Basic/" + big));
        Run light = runs.of(IMAGE, RunKind.PIPELINE, "carries-a-small-scan",
                Map.of("scan", "Basic/" + small));

        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "imager",
                executor("imager"), imagerSealing.getPrivate(), imagerSigning.getPrivate())) {
            Run held = lane.claim(heavy, Duration.ofMinutes(5)).orElseThrow();
            byte[] arrived = lane.inputs(held).get("scan").get(0).payload();
            // Against the record itself, byte for byte. Not by pulling the
            // text back out with a regex: a greedy match over a payload-sized
            // string backtracks, which costs time quadratic in the document
            // and says nothing about the store.
            assertArrayEquals(engine.get("Basic", big).orElseThrow().payload(), arrived,
                    "the large document did not arrive as the record holds it, so the spill "
                            + "lost, truncated or altered what the message was too small to "
                            + "carry");
            lane.closed(held);

            Run also = lane.claim(light, Duration.ofMinutes(5)).orElseThrow();
            assertTrue(new String(lane.inputs(also).get("scan").get(0).payload(), StandardCharsets.UTF_8)
                    .contains(SPILLED_MARKER), "the small document did not arrive");
            lane.closed(also);
        }

        // It travelled beside the message rather than in it. Read from the
        // plane, because that is the only place the difference is visible:
        // from the runner's side the two runs were identical, which is the
        // property, and is also why this cannot be asserted from up there.
        // This is the first leg on the stream, so every event read here is
        // one of this leg's answers.
        List<String> events = rowsOf("dbos", "workflow_events");
        assertFalse(events.isEmpty(), "the door answered through events, or this looks in the "
                + "wrong place and everything below it is vacuous");
        assertTrue(events.stream().anyMatch(row -> row.contains("spilled")),
                "no answer was set aside, so a payload of " + BIG + " bytes went through the "
                        + "substrate's own tables — where it is re-read on every recovery, "
                        + "kept under a retention nobody here set, and TOASTed into a side "
                        + "store with no lifecycle at all");

        // And not to everything: a poll, a claim and a checkpoint are
        // hundreds of bytes and must not each buy a row in a table.
        assertTrue(events.stream().anyMatch(row -> !row.contains("spilled")),
                "every answer spilled, so the threshold is decorative");
    }

    /*
     * The assertion the whole mechanism is held to. What is spilled is what
     * would have travelled — the same sealed carrier form, moved and not
     * transformed — so a spilled copy is reached by an erasure exactly as an
     * in-message one is, its identifying elements being under the person's
     * key inside the seal. Stored any other way, erasure would have a hole
     * precisely where the large payloads are, which is where it would matter
     * most.
     */
    @Test
    @Order(2)
    @DisplayName("and what was set aside is the sealed carrier form, so an erasure reaches it "
            + "exactly as it reaches a copy in the message")
    @Proving(DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE)
    void whatSpillsIsWhatWouldHaveTravelled() throws Exception {
        String big = engine.put(PutRequest.create("Basic", document(SPILLED_MARKER, BIG))).id();
        Run run = runs.of(IMAGE, RunKind.PIPELINE, "left-uncollected",
                Map.of("scan", "Basic/" + big));

        // Asked for and deliberately never collected, so the bytes are still
        // sitting where they were put and can be looked at.
        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "imager",
                executor("imager"), imagerSealing.getPrivate(), imagerSigning.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            lane.sealed(held);
            lane.closed(held);
        }

        // Every row of the substrate, the whole plane — because a spill is a
        // new table on that plane and a new table is a new place for
        // something readable to land.
        List<String> plane = everyRowOfTheSubstrate();
        assertTrue(plane.stream().anyMatch(row -> row.contains(run.key())),
                "the scan reached the plane the work crossed: a manifest names its run, "
                        + "readable, and none was found — so nothing below is evidence");
        assertEquals(List.of(), plane.stream().filter(row -> row.contains(SPILLED_MARKER)
                        || row.contains("Bearer ") || row.contains("imager-secret")).toList(),
                "the document, or a credential, readable on the shared plane — and if it is "
                        + "in the spill then what was set aside was never the sealed form, so "
                        + "an erasure would not reach it and the hole is exactly where the "
                        + "large payloads are");

        // And the size, which the scan above cannot see. Sealed bytes carry
        // no marker wherever they sit, so every assertion so far would pass
        // with the payload still in the substrate's own tables — which is
        // precisely the thing the spill exists to stop. One table is allowed
        // to be large: the one that was built to hold it, with a lifecycle.
        assertEquals(List.of(), oversizedRowsOutsideTheSpill(),
                "the substrate's own tables hold a payload-sized row, so the bytes are back "
                        + "where they are re-read on every recovery and kept under a "
                        + "retention nobody here set — spilling the event is not enough if a "
                        + "step's recorded result carries them instead");
    }

    // ------------------------------------------- the stream says there is work

    /*
     * This is the leg the wake-up needs and the carrier legs cannot give. They
     * prove a runner cannot tell which carrier it holds — which stays true
     * whether or not a wake-up is ever delivered, because the poll underneath
     * does the work either way. So a wake-up that quietly stopped arriving
     * would pass every other test in this repository and cost only latency
     * nobody measured: a mechanism failing in silence.
     *
     * The separation is therefore arithmetic rather than assertion-by-timing.
     * The runner's poll interval is set far longer than the patience this leg
     * has. If the work is done inside that patience, the only thing that can
     * have caused it is the wake-up — and if the wake-up is gone, the leg fails
     * by waiting rather than by being flaky.
     *
     * The trigger goes through the tenant's own machinery on purpose. A run
     * written by a Runs this test built itself would announce to nobody: the
     * seam belongs to the runtime, and wiring it is exactly what could be left
     * undone. So a courier claims a run over its own lane and then releases
     * it, which is the store making a run claimable the way it really does.
     *
     * It relies on nothing else making a run of its step claimable inside its
     * window, which is why it comes before any other leg has made one: the
     * legs before it worked the imaging step and closed what they claimed.
     */
    @Test
    @Order(3)
    @Proving(DboPromises.PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES)
    @DisplayName("a run becoming claimable reaches a runner on the stream without it waiting "
            + "out its tick")
    void theStreamCarriesTheWakeUp() throws Exception {
        CountDownLatch performed = new CountDownLatch(1);
        StepService service = new StepService() {
            @Override
            public String step() {
                return ASSAY_STEP;
            }

            @Override
            public Outcome perform(Work work) {
                performed.countDown();
                return Outcome.done(Map.of("assayed", 1L));
            }
        };

        Run waiting = runFor("woken-once");
        // Held before the runner starts, so its first cycle finds nothing to
        // take and it goes to sleep. Without this the run would be picked up
        // by the cycle every runner performs on starting, and the leg would
        // pass whether or not a wake-up exists — which is the failure mode a
        // timing test is most likely to have.
        HttpLane courierLane = courier();
        assertTrue(courierLane.claim(waiting, Duration.ofMinutes(10)).isPresent(),
                "the courier could not hold the run, so the runner would find it on its first "
                        + "cycle and this test would prove nothing");

        try (StepRunner runner = new StepRunner(Duration.ofMinutes(10), NEVER_POLLED_IN_TIME);
                StreamLane stream = StreamLane.holding(substrate, TENANT, "analyser",
                        executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            assertTrue(stream.wakeups().isPresent(),
                    "the stream lane offers no wake-ups, so nothing below can be true of it");
            runner.register(service);
            runner.attach(stream);
            runner.start();

            // Asleep: the first cycle is done and the next is ten minutes
            // away. Asserted by the run still being held rather than by the
            // clock, so a slow machine waits longer instead of failing.
            Thread.sleep(2_000);
            assertEquals(1, performed.getCount(),
                    "the runner performed work that was claimed by somebody else");

            // The store making a run claimable, through its own machinery.
            courierLane.released(waiting, "handing it to whoever is listening");

            assertTrue(performed.await(WAKE_UP_PATIENCE.toSeconds(), TimeUnit.SECONDS),
                    "a run became claimable and the runner slept through it: the wake-up did "
                            + "not cross the stream, and the only thing left to notice it is a "
                            + "poll " + NEVER_POLLED_IN_TIME.toMinutes() + " minutes away. "
                            + "Nothing is lost when this happens, which is why it needs a leg "
                            + "of its own — the work would still be done, eventually, by the "
                            + "fallback nobody was measuring");
        }
    }

    // ------------------------------------------- a lane nobody can tell apart

    /*
     * The same runner, the same work, the same outcome over HTTP and over the
     * store's own stream, and no way to tell from inside which carried it. The
     * stream is the one a shared fleet holds: the host connects to the durable
     * substrate it already runs on and every tenant's door is a workflow
     * there, so a verb is a message and its answer an event, and no tenant
     * accepts a callback. Driven through a real tenant runtime with a real
     * token, because a carrier that was built and never mounted is this
     * repository's characteristic failure.
     */
    @Test
    @Order(4)
    @DisplayName("the same runner and service do the same work to the same outcome over HTTP "
            + "and over the stream — sealed on both, signed on the stream — and every verb "
            + "lands on the tenant's own store")
    @Proving({DboPromises.PROC_A_LANE_OVER_THE_STREAM,
            DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS})
    void theRunnerCannotTellWhichCarriedIt() throws Exception {
        Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        StepService service = new StepService() {
            @Override
            public String step() {
                return ASSAY_STEP;
            }

            @Override
            public Outcome perform(Work work) {
                Map<String, String> inputs = new java.util.TreeMap<>();
                work.inputs().forEach((slot, objects) -> inputs.put(slot,
                        new String(objects.get(0).payload(), StandardCharsets.UTF_8)));
                seen.put(work.run().key(), inputs);
                return Outcome.done(Map.of("assayed", 1L));
            }
        };

        Run overHttp = runFor("over-http");
        Run overStream = runFor("over-stream");
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50));
                StreamLane stream = StreamLane.holding(substrate, TENANT, "analyser",
                        executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            runner.register(service);
            runner.attach(HttpLane.holding(laneUri, () -> token("analyser", "analyser-secret"),
                    TENANT, "analyser-over-http", executor("analyser"), sealing.getPrivate(),
                    signing.getPrivate()));
            runner.attach(stream);
            long deadline = System.nanoTime() + Eventually.PATIENCE.toNanos();
            while (System.nanoTime() < deadline && (runs.byKey(overHttp.key()).orElseThrow().open()
                    || runs.byKey(overStream.key()).orElseThrow().open())) {
                runner.cycle();
                Thread.sleep(50);
            }
        }

        Run http = runs.byKey(overHttp.key()).orElseThrow();
        Run stream = runs.byKey(overStream.key()).orElseThrow();
        assertEquals(Holder.NOBODY, http.holder(), "over HTTP: " + http);
        assertEquals(Holder.NOBODY, stream.holder(), "over the stream: " + stream);
        assertEquals(http.tally(), stream.tally(), "the same outcome");
        assertEquals(seen.get(overHttp.key()), seen.get(overStream.key()),
                "the same work arrived whole, whichever carried it: " + seen);
        assertTrue(entries(WorkModel.TYPE, stream.id()).stream()
                        .anyMatch(e -> e.contains("\"code\":\"travel\"")),
                "the hop over the stream is on the task's trail like any other");
    }

    @Test
    @Order(5)
    @DisplayName("full duplex on one channel: sealed work goes out on the stream, and the "
            + "signed opening and the result come home on it")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void workOutAndEventsHomeOnOneChannel() throws Exception {
        Run run = runFor("duplex");
        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "analyser",
                executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            assertEquals(1, lane.inputs(held).size(), "opened here, with the key held here");
            lane.closed(held);
        }
        Run closed = runs.byKey(run.key()).orElseThrow();
        assertEquals(Holder.NOBODY, closed.holder(), "closed on the head the opening left");
        List<String> chain = entries(WorkModel.TYPE, closed.id());
        String specimen = run.inputs().get("specimen").one().substring("Basic/".length());
        assertTrue(chain.stream().anyMatch(e -> e.contains("\"code\":\"travel\"")), chain.toString());
        assertTrue(entries("Basic", specimen).stream()
                        .anyMatch(e -> e.contains("\"code\":\"access\"")
                                && e.contains("\"by\":\"analyser\"")),
                "the opening came home on the same channel and landed on the document");
    }

    // ------------------------------------------- nothing readable on the plane

    /*
     * A platform-plane row carrying a payload is indistinguishable from one
     * carrying a work id unless somebody searches the plane for something they
     * know was in the payload. So this drives a run whose document carries a
     * marker nobody would write by accident, and a person-like value beside
     * it, over the stream — the carrier that crosses the shared plane — and
     * then reads every table of the substrate for either. By now the plane
     * also holds every earlier leg's traffic, which makes the search wider and
     * not weaker; the run key and the marker are this leg's alone.
     */
    @Test
    @Order(6)
    @DisplayName("a run crosses the substrate with a marked document: the manifest is found "
            + "there and the marker, the identifying value and any token are not")
    @Proving({DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE, DboPromises.WF_TWO_PLANES})
    void theSubstrateHoldsTheManifestAndNothingReadable() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                ("{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + PLAINTEXT_MARKER + "\"},"
                        + "\"identifier\":[{\"value\":\"" + IDENTIFYING + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "crosses-the-plane",
                Map.of("specimen", "Basic/" + specimen));

        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "analyser",
                executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            assertTrue(new String(lane.inputs(held).get("specimen").get(0).payload(), StandardCharsets.UTF_8)
                    .contains(PLAINTEXT_MARKER), "the analyser read the document, on its side");
            lane.closed(held);
        }
        assertEquals(Holder.NOBODY, runs.byKey(run.key()).orElseThrow().holder());

        // Now look. Every row of every table in the substrate, as text.
        List<String> rows = everyRowOfTheSubstrate();
        assertTrue(rows.stream().anyMatch(r -> r.contains(run.key())),
                "the scan reached the plane the work crossed: the manifest names the run, "
                        + "readable, and it was not found — so nothing below is evidence");
        assertTrue(rows.stream().anyMatch(r -> r.contains("Basic/" + specimen)),
                "and the manifest's references are there too, because routing is what they "
                        + "are for");
        List<String> leaks = rows.stream().filter(r -> r.contains(PLAINTEXT_MARKER)
                || r.contains(IDENTIFYING) || r.contains("Bearer ")
                || r.contains("analyser-secret")).toList();
        assertEquals(List.of(), leaks,
                "resource content, or a credential, readable in the shared plane");
    }

    /*
     * Last, because the run it claims is refused a verb and never closed: it
     * stays held by the analyser for its lease, and a leg after it that ran an
     * analyser over the assay would have it in the way.
     */
    @Test
    @Order(7)
    @DisplayName("the clear verb has no answer on the stream, whatever the asker holds")
    @Proving(DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE)
    void inputsInTheClearAreRefusedOnTheStream() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "asks-clear",
                Map.of("specimen", "Basic/" + specimen));
        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "analyser",
                executor("analyser"), sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            // The lane never asks in the clear for a keyed participant; the
            // door refuses the verb itself when asked directly.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> lane.askedInTheClear(held));
            assertTrue(refused.getMessage().contains("inputs travel sealed"), refused.getMessage());
        }
        // Across every leg's traffic, not only this one's: no document of any
        // of them landed readable, the refused ask included.
        assertFalse(everyRowOfTheSubstrate().stream()
                .anyMatch(r -> r.contains("\"resourceType\":\"Basic\"")),
                "nothing readable landed even for the refused ask");
    }

    // ------------------------------------------------------------ fixtures

    private static HttpLane courier() {
        return HttpLane.holding(laneUri, () -> token("courier", "courier-secret"), TENANT,
                "courier", executor("courier"), null, null);
    }

    private static Run runFor(String key) {
        String specimen = engine.put(PutRequest.create("Basic",
                // The same document for every run, so what arrived can be
                // compared across carriers rather than only counted.
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"specimen\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return runs.of(ASSAY, RunKind.PIPELINE, key, Map.of("specimen", "Basic/" + specimen));
    }

    /** A Basic whose text is {@code size} characters, carrying the marker. */
    private static byte[] document(String marker, int size) {
        StringBuilder text = new StringBuilder(size);
        while (text.length() < size) {
            text.append(marker).append('-');
        }
        text.setLength(size);
        return ("{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + text + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> entries(String targetType, String targetId) {
        List<String> out = new ArrayList<>();
        engine.select(Criteria.of("AuditEntry")).forEach(o -> {
            String e = new String(o.payload(), StandardCharsets.UTF_8);
            if (e.contains("\"targetType\":\"" + targetType + "\"")
                    && e.contains("\"targetId\":\"" + targetId + "\"")) {
                out.add(e);
            }
        });
        return out;
    }

    /**
     * Every table of the substrate whose largest row is payload-sized, except
     * the spill's own — reported with that size, because "something is too
     * big somewhere" is not a finding anybody can act on.
     */
    private static List<String> oversizedRowsOutsideTheSpill() throws Exception {
        List<String> over = new ArrayList<>();
        try (Connection c = substrate.getConnection(); Statement tables = c.createStatement();
                ResultSet t = tables.executeQuery("select table_schema, table_name from "
                        + "information_schema.tables where table_schema not in "
                        + "('pg_catalog', 'information_schema')")) {
            List<String> names = new ArrayList<>();
            while (t.next()) {
                if (!"dbo_lane_spill".equals(t.getString(2))) {
                    names.add("\"" + t.getString(1) + "\".\"" + t.getString(2) + "\"");
                }
            }
            assertFalse(names.isEmpty(), "the substrate has tables to look in");
            for (String name : names) {
                try (Statement widest = c.createStatement();
                        ResultSet r = widest.executeQuery("select coalesce(max(length("
                                + "row_to_json(x)::text)), 0) from " + name + " x")) {
                    if (r.next() && r.getInt(1) > TOO_BIG_FOR_THE_PLANE) {
                        over.add(name + " holds a row of " + r.getInt(1) + " bytes");
                    }
                }
            }
        }
        return over;
    }

    private static List<String> rowsOf(String schema, String table) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = substrate.getConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select row_to_json(x)::text from \"" + schema
                        + "\".\"" + table + "\" x")) {
            while (r.next()) {
                rows.add(r.getString(1));
            }
        }
        return rows;
    }

    /** Every row of every table in the substrate's schemas, rendered as text. */
    private static List<String> everyRowOfTheSubstrate() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = substrate.getConnection(); Statement tables = c.createStatement();
                ResultSet t = tables.executeQuery("select table_schema, table_name from "
                        + "information_schema.tables where table_schema not in "
                        + "('pg_catalog', 'information_schema')")) {
            List<String> names = new ArrayList<>();
            while (t.next()) {
                names.add("\"" + t.getString(1) + "\".\"" + t.getString(2) + "\"");
            }
            assertFalse(names.isEmpty(), "the substrate has tables to look in");
            for (String name : names) {
                try (Statement rowsOf = c.createStatement();
                        ResultSet r = rowsOf.executeQuery("select row_to_json(x)::text from "
                                + name + " x")) {
                    while (r.next()) {
                        rows.add(name + " " + r.getString(1));
                    }
                }
            }
        }
        return rows;
    }

    private static Executor executor(String name) {
        return new Executor(name, "1.0", "cloud.jengu.test", Scope.BASELINE);
    }

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + manager.port()
                                            + "/t/" + TENANT + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return Extracted.tokenIn(body);
        } catch (Exception unreachable) {
            throw new IllegalStateException("no token for " + clientId, unreachable);
        }
    }
}
