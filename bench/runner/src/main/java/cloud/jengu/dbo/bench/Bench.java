package cloud.jengu.dbo.bench;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import cloud.jengu.dbo.telemetry.Label;
import cloud.jengu.dbo.telemetry.Labels;
import cloud.jengu.dbo.telemetry.Telemetry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dbo benchmark, run on the appliance it is measuring.
 *
 * <p>Everything here goes through the <b>production serving path</b>: a real
 * {@link PgObjectStore} under a real {@link R4Store} behind a real
 * {@link FhirHttpServer}, driven over HTTP on loopback. A runner that called
 * the engine directly would produce better numbers and answer a question
 * nobody asked — the number that matters is the one a client sees.
 *
 * <p>Loopback rather than the network on purpose. A driver on another machine
 * measures the LAN, and a LAN is not the thing under test.
 *
 * <p>What is measured, and why each earns its place, is in
 * {@code bench/README.md}. The short version: writes, the token lookup that is
 * 88 of 206 measured production call sites, and feed lag across every tenant
 * at once — the last being the one that is genuinely differentiated, because N
 * tenant databases on one instance is exactly the shape the commit fence
 * exists for.
 */
public final class Bench {

    private static final String EID = "https://ee.ee/eid";

    // A warm-up that is discarded. The first writes of a JVM measure the JIT
    // and an empty page cache, and reporting them as steady state is the
    // oldest way to publish a slower number than the truth.
    private static final Duration WARMUP = Duration.ofSeconds(15);

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private Bench(Config config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        System.out.println(config.describe());
        new Bench(config).run();
    }

    private void run() throws Exception {
        Thermal thermal = new Thermal();
        List<Tenant> tenants = new ArrayList<>();
        Instant startedAt = Instant.now();
        boolean complete = false;

        try {
            System.out.println("==> provisioning " + config.tenants + " tenants");
            for (int i = 0; i < config.tenants; i++) {
                tenants.add(Tenant.create(config, "bench" + i));
            }

            thermal.start();

            System.out.println("==> warm-up (" + WARMUP.toSeconds() + "s, discarded)");
            drive(tenants, WARMUP, new Latency(), new Latency(), new Failures());

            System.out.println("==> writes and lookups (" + config.duration.toSeconds() + "s)");
            Latency write = new Latency();
            Latency lookup = new Latency();
            Failures failures = new Failures();
            FeedLag lag = new FeedLag(tenants);
            lag.start();
            drive(tenants, config.duration, write, lookup, failures);
            lag.stop();

            thermal.stop();
            complete = true;

            String json = result(startedAt, thermal, write, lookup, lag, failures, true);
            Files.writeString(config.out, json);
            System.out.println("==> wrote " + config.out);
            System.out.println(summary(write, lookup, lag, thermal, failures));
        } finally {
            if (!complete) {
                thermal.stop();
            }
            for (Tenant t : tenants) {
                t.close();
            }
        }
    }

    /**
     * Drives every tenant concurrently for a fixed wall clock.
     *
     * <p>One virtual thread per tenant, because ten tenants writing at once is
     * the shape an edge box actually sees — a single-threaded loop would
     * measure latency under no contention, which is a number that never
     * happens in production.
     */
    private void drive(List<Tenant> tenants, Duration duration, Latency write, Latency lookup,
            Failures failures) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(tenants.size());
        // Reported as they happen, not summarised at the end, because the
        // question this bench is for is WHEN throughput fell — and the
        // answer only means something beside the machine's own temperature
        // and the database's own counters, on one timeline.
        Telemetry telemetry = Telemetry.installed();
        for (Tenant tenant : tenants) {
            Labels labels = Labels.of(Label.TENANT, tenant.code);
            Thread.ofVirtual().name("bench-" + tenant.code).start(() -> {
                try {
                    while (!stop.get()) {
                        // Unique for the whole run. A counter that restarts
                        // each phase re-presents the warm-up's identity-bearing
                        // identifiers, and no-implicit-merge refuses them —
                        // correctly. The 409s were the store being right.
                        String value = tenant.code + "-" + tenant.nextSequence();
                        long t0 = System.nanoTime();
                        int status = tenant.create(patient(value));
                        long elapsed = System.nanoTime() - t0;
                        if (status == 201) {
                            write.record(elapsed);
                            telemetry.observed("dbo.bench.write",
                                    Duration.ofNanos(elapsed), labels);
                        } else {
                            // Counted, never ignored. A run that drops its
                            // failures publishes the latency of the requests
                            // that happened to work, which is not a number
                            // about the system.
                            failures.record(status);
                        }
                        // The token lookup is weighted the way reality is: the
                        // search inventory found identifier= at 88 of 206
                        // production call sites, more than everything else
                        // combined. Benchmarking a synthetic mix would measure
                        // a workload nobody runs.
                        long t1 = System.nanoTime();
                        int lookupStatus = tenant.lookup(value);
                        long lookupElapsed = System.nanoTime() - t1;
                        if (lookupStatus == 200) {
                            lookup.record(lookupElapsed);
                            telemetry.observed("dbo.bench.lookup",
                                    Duration.ofNanos(lookupElapsed), labels);
                        } else {
                            failures.record(lookupStatus);
                        }
                    }
                } catch (Exception e) {
                    failures.record(-1);
                    System.err.println(tenant.code + ": " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.sleep(duration.toMillis());
        stop.set(true);
        done.await();
    }

    /**
     * Consumer lag across every tenant's feed while the writers run.
     *
     * <p>The differentiated measurement. Lag here is the wall clock between an
     * object being committed and a named consumer seeing it, sampled per
     * tenant — and with N tenant databases on one instance it is precisely the
     * case the commit fence's per-database fast path was built for. A slow
     * tenant here is not a slow tenant: it is one tenant's write transaction
     * delaying somebody else's feed, which is the failure this design claims
     * it does not have.
     */
    private static final class FeedLag {

        private final List<Tenant> tenants;
        private final Latency lag = new Latency();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Thread> readers = new ArrayList<>();

        FeedLag(List<Tenant> tenants) {
            this.tenants = tenants;
        }

        void start() {
            // Catch the consumer up to the head FIRST, recording nothing. A
            // consumer that starts at the beginning spends its first seconds
            // reading the warm-up, and every one of those items is genuinely
            // old — so the samples would describe a backlog drain and get
            // reported as steady-state lag.
            for (Tenant tenant : tenants) {
                ChangeFeed feed = tenant.feed();
                FeedChunk<FeedItem> chunk;
                do {
                    chunk = feed.readFor("bench", 512);
                    if (chunk.nextCursor() != null) {
                        feed.ack("bench", chunk.nextCursor());
                    }
                } while (!chunk.items().isEmpty());
            }
            for (Tenant tenant : tenants) {
                Thread t = Thread.ofVirtual().name("feed-" + tenant.code).start(() -> {
                    ChangeFeed feed = tenant.feed();
                    String consumer = "bench";
                    while (running.get()) {
                        try {
                            FeedChunk<FeedItem> chunk = feed.readFor(consumer, 128);
                            Instant now = Instant.now();
                            for (FeedItem item : chunk.items()) {
                                lag.record(Duration.between(item.committedAt(), now).toNanos());
                            }
                            if (chunk.nextCursor() != null) {
                                feed.ack(consumer, chunk.nextCursor());
                            }
                            if (chunk.items().isEmpty()) {
                                Thread.sleep(20);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception e) {
                            System.err.println("feed " + tenant.code + ": " + e);
                            return;
                        }
                    }
                });
                readers.add(t);
            }
        }

        void stop() {
            running.set(false);
            for (Thread t : readers) {
                try {
                    t.join(Duration.ofSeconds(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        String json() {
            return Json.object(
                    Json.field("p50", round(lag.millis(0.5))),
                    Json.field("p99", round(lag.millis(0.99))),
                    Json.field("max", round(lag.maxMillis())),
                    Json.field("samples", lag.count()));
        }

        private static double round(double v) {
            return Math.round(v * 1000.0) / 1000.0;
        }
    }

    // ------------------------------------------------------------ one tenant

    /** A tenant: its own database, its own store, its own port. */
    private static final class Tenant implements AutoCloseable {

        private final String code;
        private final java.util.concurrent.atomic.AtomicLong sequence =
                new java.util.concurrent.atomic.AtomicLong();
        private final HikariDataSource dataSource;
        private final FhirHttpServer server;
        private final String base;
        private final HttpClient http;

        private Tenant(String code, HikariDataSource dataSource,
                FhirHttpServer server, String base) {
            this.code = code;
            this.dataSource = dataSource;
            this.server = server;
            this.base = base;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }

        static Tenant create(Config config, String code) throws Exception {
            // A database per tenant, which is the isolation the store claims;
            // benchmarking a shared schema would measure a product dbo is not.
            try (Connection admin = DriverManager.getConnection(
                    config.jdbcUrl, config.user, config.password);
                    Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS bench_" + code);
                s.execute("CREATE DATABASE bench_" + code);
            }
            // Pooled exactly as the production provisioner pools: without a
            // pool, every engine call opens a TCP connection and authenticates,
            // and the benchmark reports the cost of that instead of the cost of
            // the write.
            HikariConfig hikari = new HikariConfig();
            hikari.setDriverClassName("org.postgresql.Driver");
            hikari.setJdbcUrl(config.jdbcUrl.substring(0, config.jdbcUrl.lastIndexOf('/') + 1)
                    + "bench_" + code);
            hikari.setUsername(config.user);
            hikari.setPassword(config.password);
            hikari.setMaximumPoolSize(8);
            hikari.setPoolName("bench-" + code);
            HikariDataSource ds = new HikariDataSource(hikari);

            int port = freePort();
            String base = "http://127.0.0.1:" + port + "/fhir";
            R4Personality personality = new R4Personality(List.of(
                    FhirTypeConfig.identifier("Patient", EID),
                    FhirTypeConfig.internal("Observation")));
            R4Store store = new R4Store(
                    new PgObjectStore(ds, personality.registrations()), personality, base);
            FhirHttpServer server = new FhirHttpServer(store, null, "127.0.0.1", port, "/fhir");
            return new Tenant(code, ds, server, base);
        }

        long nextSequence() {
            return sequence.getAndIncrement();
        }

        int create(String body) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + "/Patient"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/fhir+json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        int lookup(String identifier) throws Exception {
            String url = base + "/Patient?identifier="
                    + java.net.URLEncoder.encode(EID + "|" + identifier, StandardCharsets.UTF_8);
            return http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        ChangeFeed feed() {
            return new PgChangeFeed(dataSource, R4Personality.DOMAIN);
        }

        @Override
        public void close() {
            server.close();
            dataSource.close();
        }
    }

    // ---------------------------------------------------------------- output

    private String result(Instant startedAt, Thermal thermal, Latency write,
            Latency lookup, FeedLag lag, Failures failures, boolean completed) {
        // A run with failures in it is not a valid run. The numbers stay in the
        // file — they are evidence — but nothing may quote them as a result.
        boolean valid = completed && thermal.clean() && failures.none();
        return Json.object(
                Json.field("startedAt", startedAt.toString()),
                Json.field("dboVersion", config.dboVersion),
                Json.raw("machine", machine()),
                Json.raw("thermal", thermal.json()),
                Json.field("valid", valid),
                Json.field("tenants", config.tenants),
                Json.raw("failures", failures.json()),
                Json.raw("measurements", Json.object(
                        Json.raw("write", write.json()),
                        Json.raw("tokenLookup", lookup.json()),
                        Json.raw("feedLagMs", lag.json()))));
    }

    /**
     * The machine, recorded with the numbers.
     *
     * <p>A figure without its hardware is unattributable, and a Pi 4 and a Pi
     * 5 differ by two to three times — comparing across them silently is how a
     * benchmark starts lying.
     */
    private String machine() {
        return Json.object(
                Json.field("model", readOr("/proc/device-tree/model", "unknown")),
                Json.field("memoryKb", memoryKb()),
                Json.field("kernel", System.getProperty("os.name") + " "
                        + System.getProperty("os.version")),
                Json.field("profile", config.profile),
                Json.field("postgres", postgresVersion()),
                Json.field("jdk", System.getProperty("java.vendor") + " "
                        + System.getProperty("java.version")));
    }

    private String postgresVersion() {
        try (Connection c = DriverManager.getConnection(
                        config.jdbcUrl, config.user, config.password);
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SHOW server_version")) {
            return r.next() ? r.getString(1) : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static long memoryKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception ignored) {
            // not Linux; the field stays zero rather than guessing
        }
        return 0;
    }

    private static String readOr(String path, String fallback) {
        try {
            return Files.readString(Path.of(path)).replace("\0", "").trim();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String summary(Latency write, Latency lookup, FeedLag lag, Thermal thermal,
            Failures failures) {
        StringBuilder b = new StringBuilder();
        b.append(String.format("%n    write        %,d ops  %.1f/s  p50 %.2fms  p99 %.2fms%n",
                write.count(), write.perSecond(), write.millis(0.5), write.millis(0.99)));
        b.append(String.format("    tokenLookup  %,d ops  %.1f/s  p50 %.2fms  p99 %.2fms%n",
                lookup.count(), lookup.perSecond(), lookup.millis(0.5), lookup.millis(0.99)));
        b.append("    feedLag      ").append(lag.json()).append('\n');
        if (!failures.none()) {
            b.append("%n    INVALID: %d requests did not succeed %s.%n"
                    .formatted(failures.total(), failures.byStatus()));
            b.append("    A latency figure over the requests that happened to work%n"
                    .formatted());
            b.append("    is not a figure about the system.%n".formatted());
        }
        if (!thermal.clean()) {
            b.append("%n    INVALID: ".formatted());
            b.append(thermal.available()
                    ? "the machine throttled or browned out during this run.%n"
                            .formatted() + "    The numbers above describe cooling, not code.%n"
                            .formatted()
                    : "this is not the reference hardware — no vcgencmd, so%n".formatted()
                            + "    nothing can say whether it throttled. Fine for developing%n"
                            .formatted() + "    the runner, not fine for quoting a figure.%n"
                            .formatted());
        }
        if ("ram".equals(config.profile)) {
            b.append("%n    NOTE: the ram profile keeps PGDATA on tmpfs. fsync is free here,%n"
                    .formatted());
            b.append("    so the write figure is a CPU ceiling and not a storage figure.%n"
                    .formatted());
        }
        return b.toString();
    }

    private static String patient(String identifier) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Bench","given":["Sample"]}],
                 "birthDate":"1970-01-01"}""".formatted(EID, identifier);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ----------------------------------------------------------------- input

    private record Config(String jdbcUrl, String user, String password, String profile,
            int tenants, Duration duration, Path out, String dboVersion) {

        static Config parse(String[] args) {
            Map<String, String> a = new java.util.HashMap<>();
            for (int i = 0; i < args.length - 1; i += 2) {
                a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
            }
            String profile = a.getOrDefault("profile", "ram");
            if (!profile.equals("ram") && !profile.equals("nvme")) {
                throw new IllegalArgumentException("--profile must be ram or nvme");
            }
            return new Config(
                    a.getOrDefault("jdbc-url", "jdbc:postgresql://127.0.0.1:5432/postgres"),
                    a.getOrDefault("user", "postgres"),
                    a.getOrDefault("password", "postgres"),
                    profile,
                    Integer.parseInt(a.getOrDefault("tenants", "10")),
                    Duration.ofSeconds(Long.parseLong(a.getOrDefault("duration", "120"))),
                    Path.of(a.getOrDefault("out", "/tmp/dbo-bench/result.json")),
                    a.getOrDefault("dbo-version", "unknown"));
        }

        String describe() {
            return "dbo bench: profile=" + profile + " tenants=" + tenants
                    + " duration=" + duration.toSeconds() + "s out=" + out;
        }
    }
}
