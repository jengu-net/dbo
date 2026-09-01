package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4Subscriptions;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.subscriptions.RestHookTransport;
import cloud.jengu.dbo.subscriptions.SubscriptionEngine;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proof matrix: durable rest-hook delivery over the change feed via DBOS. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionsIT {

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static R4Store fhir;
    static PgChangeFeed feed;
    static SubscriptionEngine engine;
    static HttpServer server;
    static String baseEndpoint;

    // per-path behaviour: attempts counter + failures to inject before success
    static final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> failuresRemaining = new ConcurrentHashMap<>();
    static PgObjectStore store;
    static final Map<String, List<String>> received = new ConcurrentHashMap<>();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("SubscriptionsIT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(jdbcUrl);
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.internal("Subscription")));
        store = new PgObjectStore(pg, Registrations.withRuns(personality.registrations()));
        fhir = new R4Store(store, personality, "https://dbo.test/fhir");
        feed = new PgChangeFeed(pg, R4Personality.DOMAIN);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            attempts.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int remaining = failuresRemaining.getOrDefault(path, new AtomicInteger()).get();
            if (remaining > 0) {
                failuresRemaining.get(path).decrementAndGet();
                exchange.sendResponseHeaders(503, -1);
            } else {
                received.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(body);
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        baseEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        engine = new SubscriptionEngine(pg, R4Personality.DOMAIN, store, feed,
                R4Subscriptions.source(store, personality),
                R4Subscriptions.criteriaCompiler(personality),
                new RestHookTransport(),
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    void down() {
        if (engine != null) engine.close();
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------- fixtures

    private String subscription(String criteria, String path) {
        return fhirCreateSubscription("""
                {"resourceType":"Subscription","status":"active",
                 "reason":"delivery test","criteria":"%s",
                 "channel":{"type":"rest-hook","endpoint":"%s%s",
                            "payload":"application/fhir+json"}}"""
                .formatted(criteria, baseEndpoint, path));
    }

    private String fhirCreateSubscription(String json) {
        return fhir.create(json).id();
    }

    private String observation(String code) {
        return """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"%s"}]}}""".formatted(code);
    }

    /**
     * Waits for a condition, driving the dispatcher while it waits.
     *
     * <p>A single {@code dispatchOnce} is a one-shot. If the write is not yet
     * visible in the feed when that pass runs, it finds nothing, nothing
     * re-fires, and the wait can only run out the clock — which is what made
     * this class fail under parallel load. The real dispatcher does not
     * have that shape: {@link SubscriptionEngine#start} loops until stopped.
     * So these tests poll the way it does, and stop depending on one pass
     * happening to land after the write became visible.
     */
    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            engine.dispatchOnce(500);
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    // ------------------------------------------------------------ scenarios

    /** A matching create triggers exactly one rest-hook POST carrying the resource. */
    @Test
    @Timeout(120)
    void matchingChangeDeliversExactlyOnce() throws Exception {
        subscription("Observation?code=http://loinc.org|SUB-1", "/hook1");
        fhir.create(observation("SUB-1"));
        fhir.create(observation("OTHER-9"));

        await("one delivery on /hook1", () -> received.getOrDefault("/hook1", List.of()).size() == 1);
        assertTrue(received.get("/hook1").get(0).contains("SUB-1"));

        // quiet feed → no further deliveries
        engine.dispatchOnce(500);
        Thread.sleep(300);
        assertEquals(1, received.get("/hook1").size());
    }

    /** REQ-DBO-EVT-DURABLE-DELIVERY: endpoint failing twice recovers → delivered exactly once. */
    @Test
    @Timeout(120)
    @Proving(DboPromises.EVT_DURABLE_DELIVERY)
    void deliveryRetriesUntilTheEndpointRecovers() throws Exception {
        failuresRemaining.put("/hook2", new AtomicInteger(2));
        subscription("Observation?code=http://loinc.org|SUB-2", "/hook2");
        fhir.create(observation("SUB-2"));

        await("delivery after retries on /hook2",
                () -> received.getOrDefault("/hook2", List.of()).size() == 1);
        assertTrue(attempts.get("/hook2").get() >= 3, "expected at least 3 attempts");
        assertEquals(1, received.get("/hook2").size());
    }

    /** Crash-before-ack simulation: re-dispatch does not double-deliver (workflow-id dedupe). */
    @Test
    @Timeout(120)
    @Proving({DboPromises.EVT_DURABLE_DELIVERY, DboPromises.FEED_ONE_PRIMITIVE,
            DboPromises.FEED_PUSH_ACK_RESUME, DboPromises.WF_POSTGRES_SUBSTRATE})
    void redispatchAfterCrashDoesNotDoubleDeliver() throws Exception {
        String cursorBefore = feed.cursorOf("subscriptions.dispatch");
        subscription("Observation?code=http://loinc.org|SUB-3", "/hook3");
        fhir.create(observation("SUB-3"));

        await("first delivery on /hook3", () -> received.getOrDefault("/hook3", List.of()).size() == 1);

        // dispatcher "crashed" after enqueue but before ack: rewind and redo
        feed.resetConsumer("subscriptions.dispatch", cursorBefore);
        engine.dispatchOnce(500);
        Thread.sleep(500);
        assertEquals(1, received.get("/hook3").size(),
                "workflow-id dedupe must absorb feed redelivery");
    }

    /** Exhausted retries dead-letter visibly; other subscriptions are unaffected. */
    @Test
    @Timeout(120)
    @Proving(DboPromises.EVT_DURABLE_DELIVERY)
    void permanentFailureDeadLettersWithoutBlockingOthers() throws Exception {
        failuresRemaining.put("/hook4-broken", new AtomicInteger(Integer.MAX_VALUE));
        String brokenSub = subscription("Observation?code=http://loinc.org|SUB-4", "/hook4-broken");
        subscription("Observation?code=http://loinc.org|SUB-4", "/hook4-ok");
        fhir.create(observation("SUB-4"));

        await("healthy sibling delivered", () -> received.getOrDefault("/hook4-ok", List.of()).size() == 1);
        await("dead letter recorded", () -> engine.deadLetters().stream()
                .anyMatch(d -> d.subscriptionId().equals(brokenSub)));
        assertEquals(4, attempts.get("/hook4-broken").get(), "maxAttempts exhausted");

        // And it is a RECORD, not a private row — a card in front of a
        // person, in the tenant's own store, with the endpoint it could not
        // reach on it. The old dead-letter table could be read by nothing but
        // the engine that wrote it.
        List<Run> waiting = new Runs(store).holding(Holder.PERSON);
        assertTrue(waiting.stream().anyMatch(run ->
                        run.process().equals("dbo.subscriptions.delivery")
                                && run.item() != null
                                && run.item().reference().equals(baseEndpoint + "/hook4-broken")),
                "an exhausted delivery is a run needing a person: " + waiting);
    }

    /** REQ-DBO-EVT-IN-PROCESS-SURFACE: a local listener sees the same matched events. */
    @Test
    @Timeout(120)
    @Proving(DboPromises.EVT_IN_PROCESS_SURFACE)
    void inProcessListenerSeesTheSameTopics() throws Exception {
        List<String> local = new CopyOnWriteArrayList<>();
        engine.addLocalListener((sub, payload) -> {
            if (payload.contains("SUB-5")) {
                local.add(payload);
            }
        });
        subscription("Observation?code=http://loinc.org|SUB-5", "/hook5");
        fhir.create(observation("SUB-5"));

        await("local listener callback", () -> local.size() == 1);
        await("rest-hook sibling delivery", () -> received.getOrDefault("/hook5", List.of()).size() == 1);
        assertTrue(local.get(0).contains("SUB-5"));
    }
}
