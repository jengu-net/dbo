package io.dbo.spike.harness;

import io.dbo.spike.api.DurableService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike dbo#1: DBOS runtime inside a Felix embedding bundle.
 *
 * Scenario A: workflow with checkpointed steps runs inside the bundle.
 * Scenario B: crash mid-workflow, relaunch, resume — checkpoint respected
 *             across a fresh DBOS instance with cross-bundle classes.
 * Scenario C: implicit throughout — the workflow implementation lives in a
 *             different bundle than the engine (whiteboard contribution).
 * Scenario D: two DBOS runtimes in one JVM against two system databases.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DbosFelixSpikeTest {

    static PostgreSQLContainer<?> postgres;
    static Framework framework;
    static Path evidenceDir;

    @BeforeAll
    void up() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        try (var conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var st = conn.createStatement()) {
            st.execute("CREATE DATABASE spike_rt1");
            st.execute("CREATE DATABASE spike_rt2");
        }
        String base = postgres.getJdbcUrl().replaceAll("/[^/]*$", "/");
        // strip any query params testcontainers appends
        base = base.contains("?") ? base.substring(0, base.indexOf('?')) : base;

        evidenceDir = Files.createTempDirectory("dbo-spike-evidence");

        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage",
                Files.createTempDirectory("dbo-spike-felix").toString());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        config.put("org.osgi.framework.system.packages.extra",
                "io.dbo.spike.api;version=\"1.0.0\"");
        config.put("dbo.spike.db.url.1", base + "spike_rt1");
        config.put("dbo.spike.db.url.2", base + "spike_rt2");
        config.put("dbo.spike.db.user", postgres.getUsername());
        config.put("dbo.spike.db.password", postgres.getPassword());
        config.put("dbo.spike.evidence.dir", evidenceDir.toString());

        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class)
                .findFirst().orElseThrow();
        framework = factory.newFramework(config);
        framework.start();

        BundleContext ctx = framework.getBundleContext();
        Bundle embedding = ctx.installBundle(
                "file:" + System.getProperty("spike.embedding.jar"));
        Bundle contrib = ctx.installBundle(
                "file:" + System.getProperty("spike.contrib.jar"));
        embedding.start();
        contrib.start();

        // give the whiteboard + launch a moment; services appear when ready
        waitForServices(2, 60_000);
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20_000);
        }
        if (postgres != null) postgres.stop();
    }

    private List<DurableService> services() throws Exception {
        BundleContext ctx = framework.getBundleContext();
        var refs = ctx.getServiceReferences(DurableService.class, null);
        return refs.stream()
                .sorted((a, b) -> String.valueOf(a.getProperty("dbo.runtime"))
                        .compareTo(String.valueOf(b.getProperty("dbo.runtime"))))
                .map(ctx::getService)
                .toList();
    }

    private void waitForServices(int count, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (services().size() >= count) return;
            Thread.sleep(200);
        }
        throw new AssertionError("expected " + count + " DurableService registrations, got "
                + services().size());
    }

    private DurableService runtime(String id) throws Exception {
        return services().stream()
                .filter(s -> s.runtimeId().equals(id))
                .findFirst().orElseThrow();
    }

    private long evidenceCount(String prefix) throws Exception {
        Path log = evidenceDir.resolve("evidence.log");
        if (!Files.exists(log)) return 0;
        return Files.readAllLines(log).stream().filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    @Order(1)
    @Timeout(120)
    void scenarioA_workflowRunsInsideBundle() throws Exception {
        DurableService rt1 = runtime("rt1");
        String result = rt1.greetAndWait("wf-a-1", "Felix");
        assertEquals("Hello, Felix!", result);
        assertEquals(1, evidenceCount("greet.s1:Felix"));
        assertEquals(1, evidenceCount("greet.s2:Felix"));
        // determinism: same workflow id again must NOT re-execute steps
        String again = rt1.greetAndWait("wf-a-1", "Felix");
        assertEquals("Hello, Felix!", again);
        assertEquals(1, evidenceCount("greet.s1:Felix"), "step re-executed for same workflow id");
    }

    @Test
    @Order(2)
    @Timeout(180)
    void scenarioB_crashRelaunchResume() throws Exception {
        DurableService rt1 = runtime("rt1");
        rt1.startGated("wf-b-1", "Phoenix");
        // wait until s1 checkpointed and the workflow is blocked on the gate
        long deadline = System.currentTimeMillis() + 30_000;
        while (evidenceCount("gated.s1:Phoenix") < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(1, evidenceCount("gated.s1:Phoenix"), "s1 did not run before crash");

        rt1.crash();
        Files.writeString(evidenceDir.resolve("gate-open"), "open");
        rt1.relaunch();

        String result = rt1.resume("wf-b-1");
        assertEquals("Hi Phoenix (recovered)", result);
        assertEquals(1, evidenceCount("gated.s1:Phoenix"),
                "s1 re-executed after relaunch — checkpoint not respected");
        assertEquals(1, evidenceCount("gated.s3:Phoenix"));
    }

    @Test
    @Order(3)
    @Timeout(120)
    void scenarioD_twoRuntimesOneJvm() throws Exception {
        DurableService rt1 = runtime("rt1");
        DurableService rt2 = runtime("rt2");
        assertNotNull(rt2, "second runtime did not come up");

        String r1 = rt1.greetAndWait("wf-d-1", "TenantOne");
        String r2 = rt2.greetAndWait("wf-d-2", "TenantTwo");
        assertEquals("Hello, TenantOne!", r1);
        assertEquals("Hello, TenantTwo!", r2);

        // isolation: each runtime only knows its own workflows
        assertEquals("UNKNOWN", rt2.statusOf("wf-d-1"),
                "rt2 sees rt1's workflow — system databases not isolated");
        assertEquals("UNKNOWN", rt1.statusOf("wf-d-2"),
                "rt1 sees rt2's workflow — system databases not isolated");
    }
}
