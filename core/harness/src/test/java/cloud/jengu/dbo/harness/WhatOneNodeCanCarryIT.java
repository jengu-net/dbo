package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one node carries, measured rather than assumed.
 *
 * <p>Every number this store is configured by — how many statements a tenant
 * may have in flight, how many runners a node should have — was a constant
 * chosen once, and the same constant on four slow cores as on a large
 * machine. There is no value that is right in both places, so what is needed
 * is not a better constant but the curve it should be read off.
 *
 * <p><b>Comparable, which means fixed.</b> The same corpus, the same mix of
 * operations, the same counts, every time and on every machine. What changes
 * between two runs of this is the machine and the settings — so the record it
 * writes names both, and two records are only worth comparing when the
 * machine lines they carry agree.
 *
 * <p><b>Real-shaped, which means read-heavy.</b> A record store serves far
 * more reads than writes, and a search costs more than either. The mix is
 * seven reads to two searches to one write, which is the shape a clinical
 * system actually issues rather than the shape that makes a number look
 * good.
 *
 * <p><b>What it does NOT measure is the wire.</b> The operations go through
 * the store the server calls, not through HTTP, so the number is dbo's own
 * work and not a client's. On one small box a real client is another machine
 * and its cost is not this one's to carry.
 *
 * <p>This is a measurement and not a check: it is tagged so the ordinary
 * suite does not run it, because the answer is a curve and a curve cannot
 * pass or fail.
 */
@Tag("measurement")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatOneNodeCanCarryIT {

    private static final String TENANT = "kandev";
    private static final String MRN = "https://profile.test/mrn";

    /** Enough that a search has to work, few enough that a Pi can hold it. */
    private static final int PATIENTS = 400;
    private static final int OBSERVATIONS = 800;

    /** One measurement per level, and the levels a small machine can show. */
    private static final int[] LEVELS = {1, 2, 4, 8, 16};

    /** Per level, so every level pays the same and the numbers compare. */
    private static final int OPERATIONS = 600;

    private static final Path PROFILE =
            Path.of(System.getProperty("dbo.node.profile", "../../config/one-node-profile.txt"));

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final List<String> patientIds = new ArrayList<>();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-node-profile");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("WhatOneNodeCanCarryIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT, MRN));
        UntilServed.scan(manager, TENANT);

        var store = manager.runtime(TENANT).orElseThrow().store();
        for (int i = 0; i < PATIENTS; i++) {
            patientIds.add(store.create("""
                    {"resourceType":"Patient",
                     "identifier":[{"system":"%s","value":"%d"}],
                     "name":[{"family":"Kandev%d","given":["Mari"]}],
                     "gender":"female","birthDate":"19%02d-03-04"}"""
                    .formatted(MRN, i, i, 50 + (i % 50))).id());
        }
        for (int i = 0; i < OBSERVATIONS; i++) {
            store.create("""
                    {"resourceType":"Observation","status":"final",
                     "code":{"coding":[{"system":"http://loinc.org","code":"1234-5"}]},
                     "subject":{"reference":"Patient/%s"},
                     "effectiveDateTime":"2024-01-%02dT10:00:00Z",
                     "valueQuantity":{"value":%d,"unit":"kg"}}"""
                    .formatted(patientIds.get(i % PATIENTS), 1 + (i % 28), 40 + (i % 60)));
        }
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

    @Test
    @Timeout(3600)
    @DisplayName("what one node carries, per level of concurrency, written down")
    void whatThisNodeCarries() throws Exception {
        List<Level> levels = new ArrayList<>();
        for (int concurrency : LEVELS) {
            levels.add(measure(concurrency));
        }

        StringBuilder said = new StringBuilder(PREAMBLE);
        said.append(machineLine()).append('\n');
        said.append("corpus patients=").append(PATIENTS)
                .append(" observations=").append(OBSERVATIONS)
                .append(" mix=7read:2search:1write operations=").append(OPERATIONS).append('\n');
        said.append("connections perTenant=")
                .append(System.getProperty("dbo.connections.per.tenant", "default")).append('\n');
        for (Level level : levels) {
            said.append(level.said()).append('\n');
        }
        said.append(suggestion(levels)).append('\n');

        Path where = PROFILE.toAbsolutePath().normalize();
        System.out.println(said);
        assertTrue(levels.stream().anyMatch(l -> l.throughput() > 0),
                "no level completed an operation, so there is nothing to read off");

        // What changed since last time, which is the number a person coming
        // back to this actually wants. A measurement on its own says what a
        // machine does; two say what the code did.
        String recorded = Files.exists(where) ? Files.readString(where) : null;
        System.out.println(against(recorded, machineLine(), levels));

        if (recorded == null || Boolean.getBoolean("dbo.node.profile.record")) {
            Files.writeString(where, said.toString());
            System.out.println("node profile recorded: " + where);
            return;
        }
        heldAgainst(recorded, machineLine(), levels);
    }

    /** The machine, said the same way every time, because it gates comparison. */
    private static String machineLine() {
        return "machine cores=" + Runtime.getRuntime().availableProcessors()
                + " heap=" + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "m"
                + " os=" + System.getProperty("os.name")
                + " arch=" + System.getProperty("os.arch");
    }

    /**
     * This measurement beside the last one, level by level.
     *
     * <p>Only when the machine agrees. Two numbers from two machines are two
     * facts about hardware and comparing them would report a laptop as a
     * regression — so a different machine line is said out loud and nothing
     * is subtracted.
     */
    private static String against(String recorded, String machine, List<Level> levels) {
        if (recorded == null) {
            return "against the record: nothing recorded yet, so this run becomes the record";
        }
        String was = recorded.lines().filter(line -> line.startsWith("machine ")).findFirst()
                .orElse("machine unknown");
        if (!was.equals(machine)) {
            return "against the record: NOT COMPARED — recorded on '" + was
                    + "' and measured on '" + machine + "'";
        }
        Map<Integer, double[]> before = recordedLevels(recorded);
        StringBuilder moved = new StringBuilder("against the record, same machine:\n");
        for (Level level : levels) {
            double[] then = before.get(level.concurrency());
            if (then == null) {
                moved.append("  concurrency=").append(level.concurrency())
                        .append(" NEW perSecond=").append(Math.round(level.throughput()))
                        .append('\n');
                continue;
            }
            moved.append("  concurrency=%d perSecond %.0f -> %.0f (%+.0f%%)  p95ms %.0f -> %d%n"
                    .formatted(level.concurrency(), then[0], level.throughput(),
                            then[0] == 0 ? 0 : (level.throughput() - then[0]) / then[0] * 100,
                            then[1], level.at(95)));
        }
        return moved.toString().stripTrailing();
    }

    /**
     * A drop worth stopping for, and only that.
     *
     * <p>A quarter, because this runs on whatever the machine is also doing
     * and a tenth is weather. What it guards is the shape of the curve
     * rather than any one number: the level the suggestion was read off
     * carrying a quarter less than it did is the store doing less work for
     * the same machine, which is the thing worth hearing about.
     */
    private static void heldAgainst(String recorded, String machine, List<Level> levels) {
        String was = recorded.lines().filter(line -> line.startsWith("machine ")).findFirst()
                .orElse("");
        if (!was.equals(machine)) {
            return;
        }
        Map<Integer, double[]> before = recordedLevels(recorded);
        List<String> worse = new ArrayList<>();
        for (Level level : levels) {
            double[] then = before.get(level.concurrency());
            if (then != null && then[0] > 0 && level.throughput() < then[0] * 0.75) {
                worse.add("concurrency=%d carried %.0f/s and now carries %.0f/s"
                        .formatted(level.concurrency(), then[0], level.throughput()));
            }
        }
        assertTrue(worse.isEmpty(),
                "this node carries materially less than the record says, on the same machine: "
                        + String.join("; ", worse)
                        + ". Re-record with -PdboNodeProfileRecord=true only when meant.");
    }

    /** The recorded levels, as concurrency to {perSecond, p95ms}. */
    private static Map<Integer, double[]> recordedLevels(String recorded) {
        Map<Integer, double[]> out = new java.util.LinkedHashMap<>();
        for (String line : recorded.lines().toList()) {
            if (!line.startsWith("level concurrency=")) {
                continue;
            }
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            for (String part : line.split(" ")) {
                int is = part.indexOf('=');
                if (is > 0) {
                    fields.put(part.substring(0, is), part.substring(is + 1));
                }
            }
            try {
                out.put(Integer.parseInt(fields.get("concurrency")),
                        new double[] {Double.parseDouble(fields.get("perSecond")),
                                Double.parseDouble(fields.get("p95ms"))});
            } catch (RuntimeException unreadable) {
                // a line from an older shape of this file says nothing here
            }
        }
        return out;
    }

    // ------------------------------------------------------------ measuring

    /** One level's answer: how much went through, and what it cost each time. */
    private record Level(int concurrency, long[] nanos, long elapsedNanos, int failures) {

        double throughput() {
            return elapsedNanos == 0 ? 0
                    : nanos.length / (elapsedNanos / 1_000_000_000.0);
        }

        long at(double percentile) {
            long[] sorted = nanos.clone();
            java.util.Arrays.sort(sorted);
            if (sorted.length == 0) {
                return 0;
            }
            int index = (int) Math.min(sorted.length - 1L,
                    Math.round(percentile / 100.0 * (sorted.length - 1)));
            return sorted[index] / 1_000_000;
        }

        String said() {
            return "level concurrency=%d ops=%d perSecond=%.1f p50ms=%d p95ms=%d p99ms=%d "
                    .formatted(concurrency, nanos.length, throughput(),
                            at(50), at(95), at(99))
                    + "failed=" + failures;
        }
    }

    private Level measure(int concurrency) throws Exception {
        var store = manager.runtime(TENANT).orElseThrow().store();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            // Warmed first, and not counted: the first pass through a path
            // pays for its plans and its classes, which is a fact about
            // starting rather than about serving.
            run(pool, store, concurrency, Math.min(OPERATIONS, 120));
            long began = System.nanoTime();
            Taken taken = run(pool, store, concurrency, OPERATIONS);
            return new Level(concurrency, taken.nanos(), System.nanoTime() - began,
                    taken.failures());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private record Taken(long[] nanos, int failures) {}

    private Taken run(ExecutorService pool, cloud.jengu.dbo.fhir.common.FhirStoreFacade store,
            int concurrency, int operations) throws Exception {
        List<Callable<long[]>> work = new ArrayList<>();
        int each = Math.max(1, operations / concurrency);
        for (int worker = 0; worker < concurrency; worker++) {
            work.add(() -> {
                long[] mine = new long[each];
                for (int i = 0; i < each; i++) {
                    long at = System.nanoTime();
                    one(store);
                    mine[i] = System.nanoTime() - at;
                }
                return mine;
            });
        }
        List<Future<long[]>> answers = pool.invokeAll(work);
        List<Long> all = new ArrayList<>();
        int failures = 0;
        for (Future<long[]> answer : answers) {
            try {
                for (long one : answer.get()) {
                    all.add(one);
                }
            } catch (Exception failed) {
                failures++;
            }
        }
        long[] nanos = new long[all.size()];
        for (int i = 0; i < nanos.length; i++) {
            nanos[i] = all.get(i);
        }
        return new Taken(nanos, failures);
    }

    /** One operation of the mix: seven reads, two searches, one write. */
    private void one(cloud.jengu.dbo.fhir.common.FhirStoreFacade store) {
        int roll = ThreadLocalRandom.current().nextInt(10);
        if (roll < 7) {
            store.read("Patient",
                    patientIds.get(ThreadLocalRandom.current().nextInt(patientIds.size())));
        } else if (roll < 9) {
            store.search("Observation",
                    Map.of("subject", "Patient/" + patientIds.get(
                            ThreadLocalRandom.current().nextInt(patientIds.size())),
                            "_count", "20"),
                    null);
        } else {
            store.create("""
                    {"resourceType":"Observation","status":"final",
                     "code":{"coding":[{"system":"http://loinc.org","code":"9999-9"}]},
                     "subject":{"reference":"Patient/%s"},
                     "effectiveDateTime":"2024-06-01T10:00:00Z",
                     "valueQuantity":{"value":%d,"unit":"kg"}}"""
                    .formatted(patientIds.get(
                                    ThreadLocalRandom.current().nextInt(patientIds.size())),
                            ThreadLocalRandom.current().nextInt(40, 100)));
        }
    }

    // ---------------------------------------------------------- the reading

    /**
     * Where the curve stops paying, said as a setting.
     *
     * <p>The knee is the last level whose tail has not doubled against the
     * quietest one AND which still carried more per second than the level
     * before it. Past that point a node is not doing more work, it is doing
     * the same work while somebody waits — which is the thing a queue hides.
     */
    private static String suggestion(List<Level> levels) {
        Level quietest = levels.get(0);
        Level best = quietest;
        for (Level level : levels) {
            boolean tailHeld = level.at(95) <= Math.max(2 * quietest.at(95), quietest.at(95) + 20);
            if (tailHeld && level.throughput() > best.throughput()) {
                best = level;
            }
        }
        return "suggested concurrency=" + best.concurrency()
                + " connectionsPerTenant=" + best.concurrency()
                + " becauseThroughputPerSecond=" + Math.round(best.throughput())
                + " atP95ms=" + best.at(95)
                + " againstQuietestP95ms=" + quietest.at(95);
    }

    private static final String PREAMBLE = """
            # What one node carries, measured on the machine named below.
            #
            # GENERATED. Re-measure with:
            #     ./gradlew :core:harness:nodeProfile
            #
            # Two records are worth comparing only when their machine lines
            # agree: the same cores, the same heap, the same architecture.
            # Everything else here is fixed on purpose — the corpus, the mix
            # of operations and how many of them — because a benchmark that
            # varies with the run measures the run.
            #
            # The operations go through the store the server calls and not
            # through HTTP, so these are dbo's own milliseconds. On one small
            # box a real client is another machine, and its cost is not this
            # one's to carry.
            #
            # The suggestion at the end is the last level whose tail had not
            # doubled and which still carried more per second than the level
            # before it. Past there a node is not doing more work; it is
            # doing the same work while somebody waits.
            """;
}
