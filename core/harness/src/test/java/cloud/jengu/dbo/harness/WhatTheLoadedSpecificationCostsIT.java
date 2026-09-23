package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.element.ElementVersion;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tenant costs to hold, and how much of it is the loaded specification.
 *
 * <p>"Moving this into the database will cut dbo's memory" is said easily and
 * has never been checkable. The symptoms are everywhere — test tasks carrying
 * two and four gigabytes, a validator that dies on a default heap three frames
 * above an OutOfMemoryError nobody sees, a pool held behind soft references —
 * and not one of them is a number.
 *
 * <p><b>This reduces nothing.</b> It says what the numbers are, so the work
 * that would move them has a figure to aim at and a way to show it arrived.
 *
 * <p><b>What is measured, said plainly, because the quantity matters.</b> Heap
 * in use after a forced collection: what the JVM still holds when asked to let
 * go of what it can. That is not retained size and not resident set — a
 * collector that has merely not run yet would report more, and native memory
 * is not in it at all. It is the quantity a sizing conversation uses, and it
 * is reproducible, which the others are not.
 *
 * <p>Recorded rather than asserted tightly. Allocation wanders between runs
 * and a threshold that fights the collector fails on Tuesdays; what is caught
 * here is a step change, which is what a regression in this actually looks
 * like.
 *
 * <p><b>A world of its own, and the number is why.</b> What it reports is
 * what a tenant costs to hold, read off the heap before and after bringing
 * one up. On a runtime other classes have used, that number is what the
 * whole suite is holding — so the measurement means nothing unless the
 * runtime holds only what this class put in it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatTheLoadedSpecificationCostsIT {

    private static final Path BASELINE = Path.of("..", "..", "config", "memory-baseline.txt");

    /** A step change rather than drift: allocation wanders, designs do not. */
    private static final double TOLERANCE = 1.30;

    /**
     * Below this, a ratio says nothing.
     *
     * <p>A thirty per cent tolerance on a number in the hundreds is a design
     * change. On a number in the single digits it is the collector: a tenant
     * serving from the face base read 3 MB, then 4, then 7 on a runner, and the
     * third of those failed a build that had nothing to do with it. This class
     * already says a threshold that fights the collector fails on Tuesdays, and
     * that is what a Tuesday looks like.
     *
     * <p>The numbers this exists to protect are the hundreds — what a face
     * costs and what a second one costs. A small line is still recorded,
     * because it is how the large ones are read.
     */
    private static final long WORTH_RATCHETING = 20;

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-memory");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("WhatTheLoadedSpecificationCostsIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
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
    @DisplayName("what a served tenant costs, what its first validated write adds, and whether "
            + "a second tenant on the same face pays for the specification again")
    @Proving(DboPromises.OPS_NUMBERS_LEAVE_THE_NODE)
    void theNumbersAreRecorded() throws Exception {
        long before = heapInUse();

        serve("malu-uks");
        long oneTenant = heapInUse();

        write("malu-uks");
        long afterAWrite = heapInUse();

        // Several more, from several threads, so the pool fills: the cap is
        // one validator per processor, and whether that cap is the knob it
        // looks like depends on what one costs.
        writeFromEveryThread("malu-uks");
        long afterThePool = heapInUse();

        // The question a sizing conversation actually has: is the loaded
        // specification per version or per tenant? A second tenant on the SAME
        // face answers it, and the answer decides whether a node's cost grows
        // with the tenants it serves or stays where it started.
        serve("malu-kaks");
        write("malu-kaks");
        long twoTenants = heapInUse();

        // The OTHER way a version becomes a context, which nothing here had
        // ever measured. A tenant with no version root takes the carried
        // packages — every scenario above — and one that has a root in its
        // store takes a base built from the records instead, shared per face.
        // The whole of "moving this into the database will cut dbo's memory"
        // is the difference between those two numbers, and until now the
        // second one existed only as a sentence in a javadoc.
        long basesBefore = ElementVersion.baseBuilds();
        long contextsBefore = ElementVersion.contextBuilds();
        serveARoot("malu-juur");
        long aRoot = heapInUse();
        serveOnTheFace("malu-baasil", "malu-juur");
        write("malu-baasil");
        long onTheBase = heapInUse();
        System.out.println("MEASURED bases built " + (ElementVersion.baseBuilds() - basesBefore)
                + ", carried contexts built " + (ElementVersion.contextBuilds() - contextsBefore));

        // A SECOND FACE, which is the number that decides whether this store
        // can serve many versions at once. Everything above measures what
        // another tenant costs; this measures what another VERSION costs, and
        // a deployment serving three faces pays it twice over before a tenant
        // exists. The target is that it stops being a number at all.
        long buildsBeforeTheSecondFace = ElementVersion.contextBuilds();
        serveOn("malu-teine-nagu", "r5");
        long aSecondFace = heapInUse();
        // Where the 444 is actually paid, said as a count rather than
        // inferred from a size. A face's carried context is the 225; if
        // serving the first tenant on a new face builds one, that is the
        // whole of the number, and no declaration this tenant could make
        // would avoid it while it is the thing that loads the definitions.
        secondFaceBuilds = ElementVersion.contextBuilds() - buildsBeforeTheSecondFace;


        Map<String, Long> now = new LinkedHashMap<>();
        // Deltas only, and the absolute floor deliberately not among them.
        // This runs inside a suite that shares one JVM, so what is resident
        // when it starts is whatever ran before it — measured alone that read
        // 23 MB and inside the suite 1.3 GB, which is a fact about the suite
        // and not about a tenant. A difference across a forced collection is
        // the same either way, which is why these are the numbers kept.
        now.put("oneServedTenantBeforeAnyWrite", mb(oneTenant - before));
        now.put("theFirstValidatedWrite", mb(afterAWrite - oneTenant));
        now.put("theRestOfTheValidatorPool", mb(afterThePool - afterAWrite));
        now.put("aSecondTenantOnTheSameFace", mb(twoTenants - afterThePool));
        now.put("aFaceRootHoldingTheVersionAsRecords", mb(aRoot - twoTenants));
        now.put("aTenantServingFromTheFaceBase", mb(onTheBase - aRoot));
        now.put("aSecondFaceServed", mb(aSecondFace - onTheBase));

        String rendered = PREAMBLE + asLines(now);
        if (Boolean.getBoolean("dbo.memory.record")) {
            Files.writeString(BASELINE, rendered);
            return;
        }
        if (!Files.exists(BASELINE)) {
            Files.writeString(BASELINE, rendered);
            return;
        }
        Map<String, Long> recorded = parse(Files.readString(BASELINE));
        StringBuilder moved = new StringBuilder();
        now.forEach((what, mb) -> {
            Long was = recorded.get(what);
            if (was != null && was >= WORTH_RATCHETING && mb > was * TOLERANCE) {
                moved.append(String.format("%n  %s: %d MB recorded, %d MB now", what, was, mb));
            }
        });
        assertTrue(moved.isEmpty(),
                "something now costs a great deal more than it did, which is a design "
                        + "change rather than the collector wandering — re-record with "
                        + "-Ddbo.memory.record=true once somebody has said why:" + moved
                        + System.lineSeparator() + rendered);
    }

    /**
     * What a tenant costs is a size and it wanders; what a context costs is a
     * decision and it does not.
     *
     * <p>Every figure above is a delta across a forced collection on a warm
     * JVM, and they move a few per cent between runs of unchanged code — the
     * validator pool has read 21 and 36 on the same commit. That is fine for
     * "did this get much worse" and useless for "does this path build a
     * context", which is the question the work to take the toolchain off the
     * serving path is actually asking.
     *
     * <p><b>And a size could not answer it anyway.</b> A context is one per
     * version per process: {@code ElementVersion.BY_CODE} is a static map and
     * a face base is one worker context per face and process. So the moment
     * any tenant in this JVM touches a path that needs one, the corpus is
     * resident for every tenant in it, and a per-tenant delta measured
     * afterwards shows nothing however well the tenant behaves. The unit of
     * saving is the process, not the tenant — which is why a scenario that
     * served one well-declared tenant among six others read the same before
     * and after the write path stopped parsing, and was deleted rather than
     * kept as evidence.
     *
     * <p>So this counts instead. It is deterministic, it cannot drift, and it
     * fails the day somebody puts a parse back on a path that had stopped
     * needing one.
     */
    @Test
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    @DisplayName("a face's context is built once for the whole process, however many tenants "
            + "serve on it")
    void oneContextPerFaceHoweverManyTenants() {
        // Both faces have served several tenants by now: three on r4, one
        // holding the version as records, one taking it from that root, and
        // one on r5. If a context were per tenant rather than per face, this
        // would be six.
        assertTrue(ElementVersion.contextBuilds() <= 2,
                "a context was built more than once per face, so the sharing that makes a "
                        + "second tenant cost 11 MB rather than 226 has stopped holding: "
                        + ElementVersion.contextBuilds() + " builds for 2 faces");

        assertTrue(ElementVersion.baseBuilds() <= 2,
                "a face base was built more than once per face: "
                        + ElementVersion.baseBuilds());
    }

    /**
     * Where a new face's cost is paid, counted.
     *
     * <p>`aSecondFaceServed` is 444 MB and the item that chases it needs to
     * know whether that is one carried context or an accumulation of smaller
     * things, because the two want opposite work. Counted here so the answer
     * is a fact rather than an inference from a size.
     */
    @Test
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    @DisplayName("what a new face costs is one carried context, and serving its first tenant "
            + "is what builds it")
    void aNewFaceCostsOneCarriedContext() {
        assertTrue(secondFaceBuilds == 1,
                "serving the first tenant on a face that nothing had touched did not build "
                        + "exactly one carried context, so what the 444 MB is made of is not "
                        + "what this item has been assuming: " + secondFaceBuilds);
    }

    private static long secondFaceBuilds;

    private void serve(String code) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code));
        UntilServed.scan(manager, code);
    }

    /** The same as {@link #serve}, on whichever face is named. */
    private void serveOn(String code, String face) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"%s","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, face));
        UntilServed.scan(manager, code);
    }

    /** A tenant that holds the version as records, so the face has a base. */
    private void serveARoot(String code) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(code));
        UntilServed.scan(manager, code);
    }

    /** A tenant that takes its face from that root rather than from packages. */
    private void serveOnTheFace(String code, String root) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                   "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, root));
        UntilServed.scan(manager, code);
    }

    private void write(String code) {
        manager.runtime(code).orElseThrow().store().create(
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Malu\"}],"
                        + "\"gender\":\"female\"}");
    }

    private void writeFromEveryThread(String code) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<?>> all = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> write(code)));
            }
            for (var one : all) {
                one.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * What the JVM still holds when asked to let go of what it can.
     *
     * <p>Twice, with a pause: one collection frees what is unreachable, and
     * the second collects what the first made unreachable — soft references
     * among them, which is how the validator pool is held.
     */
    private static long heapInUse() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long mb(long bytes) {
        return Math.max(0, bytes) / (1024 * 1024);
    }

    private static final String PREAMBLE = """
            # What a tenant costs to hold, and how much of it is the loaded
            # specification. One line per scenario, in megabytes:
            #
            #   <scenario> <mb>
            #
            # GENERATED by WhatTheLoadedSpecificationCostsIT. Re-record with:
            #     ./gradlew :core:harness:test --tests '*WhatTheLoadedSpecification*' \\
            #         -Ddbo.memory.record=true
            #
            # HEAP IN USE AFTER A FORCED COLLECTION, which is not retained size
            # and not resident set. A collector that has merely not run yet
            # would report more; native memory is not in this at all. It is the
            # quantity a sizing conversation uses and the one that reproduces.
            #
            # Every line is a DELTA, measured across a forced collection: what
            # that thing added, not a running total. There is deliberately no
            # figure for the process itself — this runs in a suite sharing one
            # JVM, so what is resident at the start is whatever ran before, and
            # an absolute there would record the suite rather than the store.
            #
            # These are measured on one process with a two-gigabyte heap, which
            # is not a deployment. What they are for is comparison: a change
            # that claims to reduce memory says which of these it moved.
            #
            # A rise of more than thirty per cent fails, because that is a
            # design change rather than the collector wandering. Re-record when
            # somebody has said why.
            #
            # A recorded number is the HIGH observation, not the last one. The
            # validator pool has measured 21 MB and 36 MB on consecutive runs
            # of the same commit — how much of the pool a forced collection
            # happens to have reclaimed — and recording a low reading makes
            # the next ordinary run fail. So after re-recording, check the
            # numbers against a second run and keep the larger.
            """;

    private static String asLines(Map<String, Long> numbers) {
        StringBuilder out = new StringBuilder();
        numbers.forEach((what, mb) -> out.append(what).append(' ').append(mb).append('\n'));
        return out.toString();
    }

    private static Map<String, Long> parse(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            out.put(parts[0], Long.parseLong(parts[1]));
        }
        return out;
    }
}
