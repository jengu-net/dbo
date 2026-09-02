package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4Subscriptions;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.fhir.r5.R5Subscriptions;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.subscriptions.RestHookTransport;
import cloud.jengu.dbo.subscriptions.SubscriptionEngine;
import cloud.jengu.dbo.subscriptions.TopicSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Topic-based subscriptions — R5-native and R4-backported. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicSubscriptionsIT {

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static R5Store r5;
    static PgObjectStore r5Engine;
    static SubscriptionEngine engine5;
    static R4Store r4;
    static SubscriptionEngine engine4;
    static HttpServer server;
    static String baseEndpoint;
    static final Map<String, List<String>> received = new ConcurrentHashMap<>();

    static final String TOPIC_FILTERED = "https://dbo.test/topics/obs-filtered";
    static final String TOPIC_CREATE_ONLY = "https://dbo.test/topics/obs-create-only";
    static final String TOPIC_LIFECYCLE = "https://dbo.test/topics/obs-lifecycle";
    static final String TOPIC_R4 = "https://dbo.test/topics/r4-backport";

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("TopicSubscriptionsIT");
        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE topic_r5");
            st.execute("CREATE DATABASE topic_r4");
        }
        String baseUrl = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            received.computeIfAbsent(exchange.getRequestURI().getPath(),
                    k -> new CopyOnWriteArrayList<>()).add(body);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        baseEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        // ---- R5-native world
        PGSimpleDataSource ds5 = ds(baseUrl + "topic_r5");
        R5Personality p5 = new R5Personality(List.of(
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.internal("Subscription"),
                FhirTypeConfig.canonical("SubscriptionTopic")));
        r5Engine = new PgObjectStore(ds5, Registrations.withRuns(p5.registrations()));
        r5 = new R5Store(r5Engine, p5, "https://dbo.test/r5");
        engine5 = new SubscriptionEngine(ds5, R5Personality.DOMAIN, r5Engine,
                new PgChangeFeed(ds5, R5Personality.DOMAIN),
                List::of, R5Subscriptions.criteriaCompiler(p5), new RestHookTransport(),
                baseUrl + "topic_r5", postgres.getUsername(), postgres.getPassword())
                .withTopics(R5Subscriptions.topicSource(r5Engine, p5),
                        R5Subscriptions.composer(p5),
                        R5Subscriptions.filterCompiler(p5));

        // ---- R4 backport world
        PGSimpleDataSource ds4 = ds(baseUrl + "topic_r4");
        R4Personality p4 = new R4Personality(List.of(
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.internal("Subscription")));
        PgObjectStore r4Engine = new PgObjectStore(ds4, Registrations.withRuns(p4.registrations()));
        r4 = new R4Store(r4Engine, p4, "https://dbo.test/r4");
        List<TopicSpec> configuredTopics = List.of(new TopicSpec(TOPIC_R4, "Observation",
                Set.of(ChangeKind.CREATED), Set.of("code")));
        engine4 = new SubscriptionEngine(ds4, R4Personality.DOMAIN, r4Engine,
                new PgChangeFeed(ds4, R4Personality.DOMAIN),
                List::of, R4Subscriptions.criteriaCompiler(p4), new RestHookTransport(),
                baseUrl + "topic_r4", postgres.getUsername(), postgres.getPassword())
                .withTopics(R4Subscriptions.backportTopicSource(r4Engine, p4, configuredTopics),
                        R4Subscriptions.backportComposer(p4),
                        R4Subscriptions.filterCompiler(p4));
    }

    @AfterAll
    void down() {
        if (engine5 != null) engine5.close();
        if (engine4 != null) engine4.close();
        if (server != null) server.stop(0);
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    /**
     * Waits for a condition, driving the given engine while it waits.
     *
     * <p>A single {@code dispatchOnce} is a one-shot: if the write is not yet
     * visible in the feed when that pass runs, it finds nothing, nothing
     * re-fires, and the wait can only run out its 60 seconds. Under parallel
     * load that window opens. The real dispatcher loops until stopped,
     * so this polls the way it does. The engine is a parameter because this
     * class drives two of them and dispatching the wrong one would prove
     * nothing.
     */
    private static void await(SubscriptionEngine engine, String what, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + Eventually.PATIENCE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            engine.dispatchOnce(500);
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out: " + what);
    }

    private List<String> at(String path) {
        return received.getOrDefault(path, List.of());
    }

    private String observation(String code) {
        return """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"%s"}]}}""".formatted(code);
    }

    private void r5Topic(String url, String interactions, String canFilterBy) {
        r5.putCanonical("""
                {"resourceType":"SubscriptionTopic","status":"active","url":"%s",
                 "resourceTrigger":[{"resource":"Observation",
                   "supportedInteraction":[%s]}]%s}"""
                .formatted(url, interactions,
                        canFilterBy == null ? "" :
                        ",\"canFilterBy\":[{\"filterParameter\":\"%s\"}]".formatted(canFilterBy)));
    }

    private String r5Subscription(String topic, String path, String filterParam, String filterValue,
            String content) {
        String filterBy = filterParam == null ? "" :
                ",\"filterBy\":[{\"filterParameter\":\"%s\",\"value\":\"%s\"}]"
                        .formatted(filterParam, filterValue);
        return r5.create("""
                {"resourceType":"Subscription","status":"active",
                 "topic":"%s","reason":"topic delivery test",
                 "channelType":{"system":"http://terminology.hl7.org/CodeSystem/subscription-channel-type",
                                "code":"rest-hook"},
                 "endpoint":"%s%s","content":"%s"%s}"""
                .formatted(topic, baseEndpoint, path, content, filterBy)).id();
    }

    /** Filtered topic: matching create → event #1 bundle; non-match silent; update → event #2. */
    @Test
    @Timeout(120)
    @Proving(DboPromises.EVT_FHIR_SUBSCRIPTIONS)
    void r5FilteredTopicDeliversNumberedNotificationBundles() throws Exception {
        r5Topic(TOPIC_FILTERED, "\"create\",\"update\"", "code");
        r5Subscription(TOPIC_FILTERED, "/t1", "code", "http://loinc.org|T-1", "full-resource");

        PutResult match = r5.create(observation("T-1"));
        r5.create(observation("T-OTHER"));
        await(engine5, "event #1 on /t1", () -> at("/t1").size() == 1);

        String first = at("/t1").get(0);
        assertTrue(first.contains("subscription-notification"));
        assertTrue(first.contains("SubscriptionStatus"));
        assertTrue(first.contains("\"eventsSinceSubscriptionStart\":\"1\"")
                || first.contains("\"eventsSinceSubscriptionStart\":1"));
        assertTrue(first.contains(TOPIC_FILTERED));
        assertTrue(first.contains("T-1"), "full-resource content must embed the Observation");

        r5.update(match.id(), 1L, observation("T-1"));
        await(engine5, "event #2 on /t1", () -> at("/t1").size() == 2);
        assertTrue(at("/t1").get(1).contains("\"eventsSinceSubscriptionStart\":\"2\"")
                || at("/t1").get(1).contains("\"eventsSinceSubscriptionStart\":2"));
    }

    /** A create-only topic stays silent on updates. */
    @Test
    @Timeout(120)
    void createOnlyTopicIgnoresUpdates() throws Exception {
        r5Topic(TOPIC_CREATE_ONLY, "\"create\"", "code");
        r5Subscription(TOPIC_CREATE_ONLY, "/t2", "code", "http://loinc.org|T-2", "full-resource");

        PutResult obs = r5.create(observation("T-2"));
        await(engine5, "create notification on /t2", () -> at("/t2").size() == 1);

        r5.update(obs.id(), 1L, observation("T-2"));
        engine5.dispatchOnce(500);
        Thread.sleep(400);
        assertEquals(1, at("/t2").size(), "update must not notify a create-only topic");
    }

    /** Delete interaction notifies; id-only content carries the focus reference, no resource. */
    @Test
    @Timeout(120)
    void deleteNotifiesWithIdOnlyContent() throws Exception {
        r5Topic(TOPIC_LIFECYCLE, "\"create\",\"delete\"", null);
        r5Subscription(TOPIC_LIFECYCLE, "/t3", null, null, "id-only");

        PutResult obs = r5.create(observation("T-3"));
        await(engine5, "create on /t3", () -> at("/t3").size() == 1);
        assertFalse(at("/t3").get(0).contains("\"resourceType\":\"Observation\""),
                "id-only content must not embed the resource");
        assertTrue(at("/t3").get(0).contains("Observation/" + obs.id()));

        r5Engine.delete("Observation", obs.id(), null);
        await(engine5, "delete on /t3", () -> at("/t3").size() == 2);
        assertTrue(at("/t3").get(1).contains("Observation/" + obs.id()));
    }

    /** A filter outside the topic's canFilterBy delivers nothing (strictness at the source). */
    @Test
    @Timeout(120)
    void filterOutsideCanFilterByDeliversNothing() throws Exception {
        r5Subscription(TOPIC_FILTERED, "/t4", "status", "final", "full-resource"); // status ∉ canFilterBy
        r5.create(observation("T-1"));
        engine5.dispatchOnce(500);
        Thread.sleep(400);
        assertEquals(0, at("/t4").size(), "nonconforming subscription must deliver nothing");
    }

    /** The R4 backport: configured topic + criteria=url + filter extension → backport bundle. */
    @Test
    @Timeout(120)
    @Proving(DboPromises.EVT_FHIR_SUBSCRIPTIONS)
    void r4BackportDeliversParametersStatusBundle() throws Exception {
        r4.create("""
                {"resourceType":"Subscription","status":"active","reason":"topic delivery test",
                 "extension":[{"url":"%s","valueString":"code=http://loinc.org|R4-1"}],
                 "criteria":"%s",
                 "channel":{"type":"rest-hook","endpoint":"%s/t5",
                            "payload":"application/fhir+json"}}"""
                .formatted(R4Subscriptions.BACKPORT_FILTER_EXT, TOPIC_R4, baseEndpoint));

        r4.create(observation("R4-1"));
        r4.create(observation("R4-OTHER"));
        await(engine4, "backport notification on /t5", () -> at("/t5").size() == 1);

        String body = at("/t5").get(0);
        assertTrue(body.contains("\"history\""), "backport rides a history bundle");
        assertTrue(body.contains("event-notification"));
        assertTrue(body.contains(TOPIC_R4));
        assertTrue(body.contains("R4-1"), "full-resource payload embeds the Observation");
        assertFalse(body.contains("R4-OTHER"));
    }
}
