package cloud.jengu.dbo.telemetry.otlp;

import cloud.jengu.dbo.telemetry.Label;
import cloud.jengu.dbo.telemetry.Labels;
import cloud.jengu.dbo.telemetry.Telemetry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The seam's numbers, carried to a collector as OTLP metrics over HTTP.
 *
 * <p><b>Reporting is not a dependency of serving.</b> Every verb here updates
 * an in-memory aggregate and returns; a flusher on its own thread renders
 * what accumulated and posts it on an interval. A collector that is absent,
 * slow or refusing costs the caller nothing: the post fails on the flusher,
 * the failure is logged once per change of state rather than per interval,
 * and the aggregates keep counting for the next attempt. Nothing is queued
 * per event, so there is nothing to overflow.
 *
 * <p><b>Configured by the deployment.</b> The endpoint is read from the
 * framework's properties when this bundle runs in one, from the system
 * properties otherwise, and last from the environment variables the
 * protocol's own specification names. No endpoint means this exporter is
 * installed and idle — the numbers are counted and go nowhere, which is
 * what a node without a collector has always done.
 *
 * <p><b>The shape.</b> A count is a cumulative monotonic sum, a level is a
 * gauge, an observation is a cumulative histogram in seconds over fixed
 * bounds — the three the seam speaks, in the protocol's words for them. The
 * label vocabulary is the seam's: attribute keys are exactly the closed set
 * {@link Label} names, in their wire form.
 */
public final class OtlpTelemetry implements Telemetry {

    static final String ENDPOINT = "dbo.telemetry.otlp.endpoint";
    static final String HEADERS = "dbo.telemetry.otlp.headers";
    static final String INTERVAL = "dbo.telemetry.otlp.interval.seconds";
    static final String SERVICE = "dbo.telemetry.otlp.service";
    static final String INSTANCE = "dbo.telemetry.otlp.instance";

    /**
     * What this process calls itself when nothing said otherwise.
     *
     * <p>One store, one name, and a deployment that runs more than one kind
     * of node says so rather than being guessed at.
     */
    static final String DEFAULT_SERVICE = "dbo";

    /**
     * This process, distinctly from any other reporting the same name.
     *
     * <p>Minted per JVM rather than left absent, and that is the opposite of
     * what a trace context gets, for a reason worth keeping straight: a trace
     * context belongs to a chain somebody else started, so inventing one
     * fabricates a relationship. An instance id names THIS process and
     * nothing else, so there is no one else's fact to get wrong — and the
     * protocol asks for a fresh one per process for exactly this case.
     *
     * <p>Without it every reporter sharing a name is one series, and a count
     * is cumulative: two processes counting from zero against one identity
     * read as a single counter that keeps falling over. Several test JVMs on
     * one machine are the ordinary way to meet that.
     */
    private static final String PROCESS_INSTANCE = java.util.UUID.randomUUID().toString();
    /** Seconds; the classic latency ladder, which is what a request or a step takes. */
    static final double[] BOUNDS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};

    private static volatile Function<String, String> framework;
    private static final Set<OtlpTelemetry> OPEN = new CopyOnWriteArraySet<>();

    /** Where a deployment's configuration comes from, when the bundle has a framework. */
    static void configuredBy(Function<String, String> properties) {
        framework = properties;
    }

    static void closeAll() {
        OPEN.forEach(OtlpTelemetry::close);
    }

    private final Map<String, Sum> sums = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final long startedNanos = System.currentTimeMillis() * 1_000_000L;
    private final URI endpoint;
    private final String service;
    private final String instance;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reachable = new AtomicBoolean(true);
    private volatile Thread flusher;

    /** The ServiceLoader constructor: configured by whatever the deployment set. */
    public OtlpTelemetry() {
        this(property(ENDPOINT, "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"),
                property(HEADERS, "OTEL_EXPORTER_OTLP_HEADERS", null),
                property(INTERVAL, "OTEL_METRIC_EXPORT_INTERVAL", null));
    }

    /** Configured explicitly: what an embedder or a proof constructs, beside the ServiceLoader one. */
    public OtlpTelemetry(String endpoint, String headers, String interval) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? null
                : URI.create(endpoint.endsWith("/v1/metrics") || endpoint.contains("/v1/metrics")
                        ? endpoint : endpoint.replaceAll("/+$", "") + "/v1/metrics");
        this.headers = parseHeaders(headers);
        // Read here rather than taken as parameters, so the explicit
        // constructor a proof or an embedder uses is identified the same way
        // the ServiceLoader one is, and neither has to remember to pass it.
        String named = property(SERVICE, "OTEL_SERVICE_NAME", null);
        this.service = named == null || named.isBlank() ? DEFAULT_SERVICE : named.trim();
        String said = property(INSTANCE, "OTEL_SERVICE_INSTANCE_ID", null);
        this.instance = said == null || said.isBlank() ? PROCESS_INSTANCE : said.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        if (this.endpoint != null) {
            long seconds = 10;
            try {
                if (interval != null && !interval.isBlank()) {
                    seconds = Math.max(1, Long.parseLong(interval.trim()));
                }
            } catch (NumberFormatException ignored) {
                // The default stands; a mistyped interval is not a reason to
                // stop reporting.
            }
            final long every = seconds;
            OPEN.add(this);
            flusher = Thread.ofVirtual().name("dbo-telemetry-otlp").start(() -> {
                while (!closed.get()) {
                    try {
                        Thread.sleep(Duration.ofSeconds(every));
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    flush();
                }
            });
        }
    }

    /** Whether a deployment pointed this at anything. */
    public boolean exporting() {
        return endpoint != null;
    }

    @Override
    public void counted(String name, long delta, Labels labels) {
        sums.computeIfAbsent(key(name, labels), k -> new Sum(name, labels)).value.addAndGet(delta);
    }

    @Override
    public void observed(String name, Duration took, Labels labels) {
        histograms.computeIfAbsent(key(name, labels), k -> new Histogram(name, labels))
                .record(took.toNanos() / 1_000_000_000.0);
    }

    @Override
    public void level(String name, long value, Labels labels) {
        gauges.computeIfAbsent(key(name, labels), k -> new Gauge(name, labels)).value.set(value);
    }

    /** Renders what accumulated and posts it. Public so a proof can flush on demand. */
    public boolean flush() {
        if (endpoint == null) {
            return false;
        }
        String body = render();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        try {
            HttpResponse<Void> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.discarding());
            boolean accepted = response.statusCode() / 100 == 2;
            state(accepted, accepted ? null : "the collector answered " + response.statusCode());
            return accepted;
        } catch (java.io.IOException | RuntimeException unreachable) {
            state(false, "the collector did not answer: " + unreachable.getMessage());
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Once per change of state: a collector that is down for an hour is one line, not three hundred and sixty. */
    private void state(boolean ok, String why) {
        if (reachable.compareAndSet(!ok, ok)) {
            org.slf4j.LoggerFactory.getLogger("dbo.telemetry").info(ok
                    ? "telemetry exporting again: endpoint={}"
                    : "telemetry not exporting: endpoint={} reason={}", endpoint, why);
        }
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            OPEN.remove(this);
            Thread f = flusher;
            if (f != null) {
                f.interrupt();
            }
        }
    }

    // ------------------------------------------------------------ rendering

    /** The whole snapshot as one OTLP/JSON ExportMetricsServiceRequest. */
    String render() {
        long now = System.currentTimeMillis() * 1_000_000L;
        StringBuilder metrics = new StringBuilder();
        for (Sum sum : sums.values()) {
            metric(metrics, sum.name, "{\"sum\":{\"aggregationTemporality\":2,\"isMonotonic\":true,"
                    + "\"dataPoints\":[{" + attributes(sum.labels) + "\"startTimeUnixNano\":\""
                    + startedNanos + "\",\"timeUnixNano\":\"" + now + "\",\"asInt\":\""
                    + sum.value.get() + "\"}]}}");
        }
        for (Gauge gauge : gauges.values()) {
            metric(metrics, gauge.name, "{\"gauge\":{\"dataPoints\":[{" + attributes(gauge.labels)
                    + "\"timeUnixNano\":\"" + now + "\",\"asInt\":\"" + gauge.value.get() + "\"}]}}");
        }
        for (Histogram histogram : histograms.values()) {
            metric(metrics, histogram.name, "{\"histogram\":{\"aggregationTemporality\":2,"
                    + "\"dataPoints\":[{" + attributes(histogram.labels) + "\"startTimeUnixNano\":\""
                    + startedNanos + "\",\"timeUnixNano\":\"" + now + "\"," + histogram.render()
                    + "}]}}");
        }
        return "{\"resourceMetrics\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"service.name\",\"value\":{\"stringValue\":"
                + quoted(service) + "}},"
                + "{\"key\":\"service.instance.id\",\"value\":{\"stringValue\":"
                + quoted(instance) + "}}"
                + "]},\"scopeMetrics\":[{\"scope\":{\"name\":"
                + "\"cloud.jengu.dbo\"},\"metrics\":[" + metrics + "]}]}]}";
    }

    private static void metric(StringBuilder into, String name, String body) {
        if (!into.isEmpty()) {
            into.append(',');
        }
        into.append("{\"name\":").append(quoted(name)).append(',').append(body, 1, body.length());
    }

    private static String attributes(Labels labels) {
        StringBuilder out = new StringBuilder("\"attributes\":[");
        boolean first = true;
        for (Map.Entry<Label, String> label : labels.asMap().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"key\":").append(quoted(label.getKey().wire()))
                    .append(",\"value\":{\"stringValue\":").append(quoted(label.getValue()))
                    .append("}}");
        }
        return out.append("],").toString();
    }

    static String quoted(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String key(String name, Labels labels) {
        return name + '|' + labels;
    }

    private static Map<String, String> parseHeaders(String spec) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String pair : spec.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** Framework property, then system property, then the protocol's own environment variables. */
    private static String property(String name, String env, String fallbackEnv) {
        Function<String, String> from = framework;
        String value = from == null ? null : from.apply(name);
        if (value == null) {
            value = System.getProperty(name);
        }
        if (value == null && env != null) {
            value = System.getenv(env);
        }
        if (value == null && fallbackEnv != null) {
            value = System.getenv(fallbackEnv);
        }
        return value;
    }

    private record Sum(String name, Labels labels, AtomicLong value) {
        Sum(String name, Labels labels) {
            this(name, labels, new AtomicLong());
        }
    }

    private record Gauge(String name, Labels labels, AtomicLong value) {
        Gauge(String name, Labels labels) {
            this(name, labels, new AtomicLong());
        }
    }

    private static final class Histogram {
        final String name;
        final Labels labels;
        final long[] buckets = new long[BOUNDS.length + 1];
        long count;
        double sum;

        Histogram(String name, Labels labels) {
            this.name = name;
            this.labels = labels;
        }

        synchronized void record(double seconds) {
            int i = 0;
            while (i < BOUNDS.length && seconds > BOUNDS[i]) {
                i++;
            }
            buckets[i]++;
            count++;
            sum += seconds;
        }

        synchronized String render() {
            List<String> counts = new ArrayList<>();
            for (long b : buckets) {
                counts.add("\"" + b + "\"");
            }
            List<String> bounds = new ArrayList<>();
            for (double b : BOUNDS) {
                bounds.add(String.valueOf(b));
            }
            return "\"count\":\"" + count + "\",\"sum\":" + sum + ",\"bucketCounts\":["
                    + String.join(",", counts) + "],\"explicitBounds\":[" + String.join(",", bounds)
                    + "]";
        }
    }
}
