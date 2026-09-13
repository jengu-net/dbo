package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.telemetry.Label;
import cloud.jengu.dbo.telemetry.Labels;
import cloud.jengu.dbo.telemetry.Telemetry;
import cloud.jengu.dbo.telemetry.otlp.OtlpTelemetry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment points the seam at its collector, and the node's numbers
 * arrive there in the published protocol — while a collector that is absent
 * or refusing costs the node nothing.
 *
 * <p>The collector here is a JDK HTTP server accepting OTLP/JSON metrics,
 * which is what an OpenTelemetry collector's HTTP receiver accepts; what is
 * asserted is the request's shape and content, not a library's opinion of
 * it. The exporter is found the way the runtime finds it, through the seam's
 * own lookup, so the wiring under test is the one that ships.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumbersLeaveTheNodeIT {

    static HttpServer collector;
    static final List<String> received = new CopyOnWriteArrayList<>();
    static final AtomicInteger answerWith = new AtomicInteger(200);
    static String endpoint;

    @BeforeAll
    void up() throws Exception {
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/metrics", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(answerWith.get(), -1);
            exchange.close();
        });
        collector.start();
        endpoint = "http://127.0.0.1:" + collector.getAddress().getPort();
    }

    @AfterAll
    void down() {
        collector.stop(0);
        System.clearProperty("dbo.telemetry.otlp.endpoint");
    }

    @Test
    @DisplayName("the seam finds the exporter, and a count, a level and a duration arrive at the "
            + "collector as a sum, a gauge and a histogram carrying the seam's labels")
    @Proving(DboPromises.OPS_NUMBERS_LEAVE_THE_NODE)
    void theNumbersArriveInTheProtocol() {
        System.setProperty("dbo.telemetry.otlp.endpoint", endpoint);
        Telemetry telemetry = Telemetry.installed();
        assertTrue(telemetry instanceof OtlpTelemetry,
                "the seam's own lookup found the exporter, not the discarding default: "
                        + telemetry.getClass());
        OtlpTelemetry otlp = (OtlpTelemetry) telemetry;
        try {
            assertTrue(otlp.exporting(), "configured by the deployment, it exports");
            Labels labels = Labels.of(Label.STEP, "dbo.lab.assay").and(Label.OUTCOME, "closed");
            otlp.counted("dbo.runs", 3, labels);
            otlp.level("dbo.claims.held", 2, Labels.none());
            otlp.observed("dbo.step.took", Duration.ofMillis(120), labels);

            assertTrue(otlp.flush(), "the collector accepted the batch");
            String batch = received.get(received.size() - 1);
            assertTrue(batch.contains("\"resourceMetrics\"") && batch.contains("\"scopeMetrics\""),
                    batch);
            assertTrue(batch.contains("\"name\":\"dbo.runs\"") && batch.contains("\"isMonotonic\":true")
                            && batch.contains("\"asInt\":\"3\""), "a count is a monotonic sum: " + batch);
            assertTrue(batch.contains("\"name\":\"dbo.claims.held\"") && batch.contains("\"gauge\""),
                    "a level is a gauge: " + batch);
            assertTrue(batch.contains("\"name\":\"dbo.step.took\"") && batch.contains("\"histogram\"")
                            && batch.contains("\"count\":\"1\"") && batch.contains("\"explicitBounds\""),
                    "a duration is a histogram: " + batch);
            assertTrue(batch.contains("\"key\":\"" + Label.STEP.wire() + "\"")
                            && batch.contains("\"stringValue\":\"dbo.lab.assay\""),
                    "labelled from the seam's own vocabulary, in its wire form: " + batch);
        } finally {
            otlp.close();
        }
    }

    @Test
    @DisplayName("a refusing collector and an absent one cost the caller nothing and are said once")
    @Proving(DboPromises.OPS_NUMBERS_LEAVE_THE_NODE)
    void aMissingCollectorLeavesTheNodeServing() {
        answerWith.set(503);
        OtlpTelemetry refused = new OtlpTelemetry(endpoint, null, "60");
        try {
            long before = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                refused.counted("dbo.busy", 1, Labels.none());
            }
            assertTrue(System.nanoTime() - before < Duration.ofSeconds(1).toNanos(),
                    "emitting never waits on the collector");
            assertFalse(refused.flush(), "the collector refused, and the flusher says so");
            assertFalse(refused.flush(), "and keeps the aggregates for the next attempt");
        } finally {
            refused.close();
            answerWith.set(200);
        }

        OtlpTelemetry nowhere = new OtlpTelemetry("http://127.0.0.1:1", null, "60");
        try {
            nowhere.counted("dbo.busy", 1, Labels.none());
            assertFalse(nowhere.flush(), "nothing is listening, and the node is still here");
        } finally {
            nowhere.close();
        }

        OtlpTelemetry unconfigured = new OtlpTelemetry(null, null, null);
        unconfigured.counted("dbo.busy", 1, Labels.none());
        assertFalse(unconfigured.exporting(), "no endpoint: counted, sent nowhere");
        assertFalse(unconfigured.flush());
    }

    @Test
    @DisplayName("the batch says which process it came from: the service a deployment named, and "
            + "an instance minted per JVM so two reporters of one name are two series")
    @Proving(DboPromises.OPS_NUMBERS_LEAVE_THE_NODE)
    void theResourceNamesTheReporter() {
        OtlpTelemetry unnamed = new OtlpTelemetry(endpoint, null, "60");
        try {
            unnamed.counted("dbo.busy", 1, Labels.none());
            assertTrue(unnamed.flush(), "the collector accepted the batch");
            String batch = received.get(received.size() - 1);
            assertTrue(batch.contains("\"key\":\"service.name\"")
                            && batch.contains("\"stringValue\":\"dbo\""),
                    "a node nobody renamed is the store's own name: " + batch);
            assertTrue(batch.contains("\"key\":\"service.instance.id\""),
                    "and it says which process it is: " + batch);
        } finally {
            unnamed.close();
        }

        // Two reporters in one JVM share its instance, because the instance
        // is the process. What must not collide is two PROCESSES, which is
        // what the per-JVM mint answers and what no assertion inside one JVM
        // can show — so what is checked here is that the id is stable within
        // a process, the half that would otherwise make every flush a new
        // series.
        OtlpTelemetry again = new OtlpTelemetry(endpoint, null, "60");
        try {
            again.counted("dbo.busy", 1, Labels.none());
            assertTrue(again.flush());
            assertEquals(instanceIn(received.get(received.size() - 2)),
                    instanceIn(received.get(received.size() - 1)),
                    "one process is one instance, however many exporters it opens");
        } finally {
            again.close();
        }

        System.setProperty("dbo.telemetry.otlp.service", "dbo-tests");
        OtlpTelemetry named = new OtlpTelemetry(endpoint, null, "60");
        try {
            named.counted("dbo.busy", 1, Labels.none());
            assertTrue(named.flush());
            assertTrue(received.get(received.size() - 1)
                            .contains("\"stringValue\":\"dbo-tests\""),
                    "a deployment that named itself is called that: "
                            + received.get(received.size() - 1));
        } finally {
            named.close();
            System.clearProperty("dbo.telemetry.otlp.service");
        }
    }

    /** The instance id out of a batch, which is what two batches are compared on. */
    private static String instanceIn(String batch) {
        String at = "\"key\":\"service.instance.id\",\"value\":{\"stringValue\":\"";
        int from = batch.indexOf(at);
        assertTrue(from >= 0, "the batch names its instance: " + batch);
        from += at.length();
        return batch.substring(from, batch.indexOf('"', from));
    }
}
