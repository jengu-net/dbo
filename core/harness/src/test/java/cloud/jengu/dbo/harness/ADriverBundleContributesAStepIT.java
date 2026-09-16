package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bundle contributes a step, and the whiteboard wires it.
 *
 * <p>The embeddable promise was cited by nine tests and every one of them
 * constructed the runner: a lane obtained three ways, one service, the same
 * outcome. That proves the <b>seam</b> — location-blind, tenant-stateless,
 * needing only a lane — and says nothing about the <b>wiring</b>, which is
 * the half a driver bundle actually rests on. The runner's own build file
 * records the gap without anything acting on it: its activator is <i>the
 * whiteboard the runtime's own bundles never fill: with no StepService and no
 * Lane registered it cycles over nothing.</i>
 *
 * <p>So: a real framework, the runner bundle installed and started, and a
 * separate bundle registering a {@code StepService} and a {@code Lane} as
 * ordinary services. Nothing here constructs a runner, attaches a lane or
 * registers a service — if the work is performed, the only thing that can
 * have wired it is the activator.
 *
 * <p>The probe reports through a system property, deliberately. Its classes
 * live in the framework's class space, so a test holding a reference into it
 * would be the very thing this replaces: a proof that reaches inside the
 * container it is meant to be standing outside of.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADriverBundleContributesAStepIT {

    private static final String PERFORMED = "dbo.probe.performed";

    static Framework framework;

    @BeforeAll
    void up() throws Exception {
        System.clearProperty(PERFORMED);
        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", FelixStorage.directory("dbo-probe-felix"));
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        // Fast enough that the test is not mostly waiting, slow enough to be
        // a poll rather than a busy loop.
        config.put("dbo.runner.poll.millis", "50");

        framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.start();
        BundleContext ctx = framework.getBundleContext();
        // The logging arrangement the distribution ships, in its order: the
        // mediator first as a framework extension and never started, then the
        // API bundle, then the binding that provides its serviceloader
        // capability. Borrowed rather than simplified — a container that
        // installs something other than what the distribution installs is
        // proving something about itself.
        ctx.installBundle("file:" + System.getProperty("spifly.jar"));
        ctx.installBundle("file:" + System.getProperty("slf4j.api.jar"));
        ctx.installBundle("file:" + System.getProperty("dbo.logging.jar")).start();
        // Then the minimum a runner needs, and nothing else. What is absent
        // is part of the claim: no store bundle, no face, no transport, no
        // substrate — a driver bundle contributes a step without any of them.
        for (String jar : List.of("dbo.core.jar", "dbo.work.jar",
                "dbo.telemetry.jar", "dbo.runner.jar")) {
            ctx.installBundle("file:" + System.getProperty(jar)).start();
        }
        ctx.installBundle("file:" + System.getProperty("dbo.step.probe.jar")).start();
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10_000);
        }
        System.clearProperty(PERFORMED);
    }

    @Test
    @DisplayName("a bundle that registers a step service and a lane has its work performed, "
            + "with nothing wired by hand — which is what a driver bundle rests on")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void theWhiteboardWiresADriverBundle() {
        // Its own wait rather than the suite's: the shared one is patient for
        // four minutes and blames a database transaction when it gives up,
        // and there is no database within reach of this container. A failure
        // here has exactly one meaning and should say so.
        //
        // Nothing nudges the runner. It polls on its own thread, and a nudge
        // from here would be this test driving the cycle it is claiming the
        // container drives.
        String performed = null;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (performed == null && System.nanoTime() < deadline) {
            performed = System.getProperty(PERFORMED);
            if (performed == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        assertNotNull(performed,
                "the step never ran, so registering a StepService and a Lane in a container "
                        + "wires nothing — and a driver bundle contributing a step is a "
                        + "sentence with no mechanism behind it");
        assertTrue(performed.startsWith("probe.assay"),
                "something ran, but not the work this bundle offered: " + performed);
    }

    @Test
    @DisplayName("a driver bundle reaches the lane and nothing else, which is the clause the "
            + "rest of the promise is built on and the one a driver author would feel first")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void aDriverNeedsOnlyTheLane() throws Exception {
        // Computed by bnd from bytecode, so this is what the code actually
        // reaches rather than what anybody declared. If contributing a step
        // ever starts needing the store, a transport or an orchestrator, the
        // manifest says so here before a driver author discovers it.
        java.util.Set<String> reached = new java.util.TreeSet<>();
        try (var jar = new java.util.jar.JarFile(System.getProperty("dbo.step.probe.jar"))) {
            String imports = jar.getManifest().getMainAttributes().getValue("Import-Package");
            for (String one : imports.split(",(?=[a-zA-Z])")) {
                reached.add(one.split(";")[0].trim());
            }
        }

        assertEquals(java.util.Set.of("cloud.jengu.dbo.runner", "cloud.jengu.dbo.work",
                        "java.lang", "java.util", "org.osgi.framework"),
                reached,
                "a bundle that contributes a step reaches something new, and what a driver "
                        + "must depend on is the whole of this clause: " + reached);
    }

    @Test
    @DisplayName("everything resolved and the runner and the driver are running, so a step "
            + "that did not run would be a wiring failure rather than a bundle that never "
            + "started")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void theContainerIsWhatItClaimsToBe() {
        java.util.Map<String, Integer> states = new java.util.TreeMap<>();
        for (Bundle bundle : framework.getBundleContext().getBundles()) {
            if (bundle.getBundleId() != 0) {
                states.put(bundle.getSymbolicName(), bundle.getState());
            }
        }

        // Nothing merely INSTALLED: a bundle that failed to resolve is the
        // reading the other test must never be given, because an unresolved
        // runner and a broken whiteboard are the same silence from outside.
        states.forEach((name, state) -> assertTrue(
                state == Bundle.ACTIVE || state == Bundle.RESOLVED,
                name + " neither resolved nor started, so a step that did not run would say "
                        + "nothing about the wiring: " + states));

        // And the two that must actually be running are. The mediator is a
        // framework extension and the API bundle is never started — that is
        // the distribution's arrangement, not a gap — so this names what has
        // to be ACTIVE rather than demanding it of everything.
        assertEquals(Bundle.ACTIVE, states.get("cloud.jengu.dbo.runner"), states.toString());
        assertEquals(Bundle.ACTIVE, states.get("cloud.jengu.dbo.probe"), states.toString());
    }
}
