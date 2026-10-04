package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.runner.transport.Access;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.Performing;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A participant told that the run it claimed is no longer its own drops the
 * work, and says nothing about the run in its name.
 *
 * <p>The far side is a lane that records the verbs reaching it and refuses
 * the one named as a tenant would refuse a holder whose claim somebody else
 * ended. What is proven is what the shared mapping does with that answer —
 * beside the tenant and across a real HTTP door — and that it tells it apart
 * from every other refusal.
 */
class AClaimLostMidWorkIsDroppedTest {

    private static final String STEP = "hogwarts.admission.register";

    /** A step that reports progress before it is done, so the report can be the one refused. */
    private static final StepService REPORTING = StepService.performing(STEP, work -> {
        work.progress().checkpoint(Map.of("read", 1L));
        return Outcome.done();
    });

    @Test
    @DisplayName("a run whose inputs are refused because somebody else acted on it is dropped "
            + "without being released, beside the tenant and across the wire")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aRefusedReadIsDropped() throws Exception {
        for (boolean acrossTheWire : new boolean[] {false, true}) {
            List<String> verbs = new ArrayList<>();
            Lane tenant = farSide(verbs, "inputs", Runs.NotHeld::new);

            Outcome said = perform(tenant, acrossTheWire);

            assertTrue(said instanceof Outcome.Lost,
                    "a lost claim was not the outcome (wire=" + acrossTheWire + "): " + said);
            assertFalse(verbs.contains("released"), "work that was no longer this "
                    + "participant's was released in its name (wire=" + acrossTheWire + "): "
                    + verbs);
            assertFalse(verbs.contains("closed"), "and closed: " + verbs);
        }
    }

    @Test
    @DisplayName("a report refused mid-work because the claim was lost ends the work there, "
            + "and nothing is released or closed")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aRefusedReportIsDropped() throws Exception {
        for (boolean acrossTheWire : new boolean[] {false, true}) {
            List<String> verbs = new ArrayList<>();
            Lane tenant = farSide(verbs, "checkpoint", Runs.NotHeld::new);

            Outcome said = perform(tenant, acrossTheWire);

            assertTrue(said instanceof Outcome.Lost,
                    "a lost claim was not the outcome (wire=" + acrossTheWire + "): " + said);
            assertFalse(verbs.contains("released") || verbs.contains("closed"),
                    "a lost run was reported on (wire=" + acrossTheWire + "): " + verbs);
        }
    }

    @Test
    @DisplayName("any other refusal is still the work failing, and the run is released with it")
    @Proving(DboPromises.PROC_FAILURE_IS_RELEASED)
    void anotherRefusalIsReleased() throws Exception {
        List<String> verbs = new ArrayList<>();
        Lane tenant = farSide(verbs, "inputs", IllegalStateException::new);

        Outcome said = perform(tenant, true);

        assertTrue(said instanceof Outcome.Failed, "an ordinary refusal was not a failure: "
                + said);
        assertEquals(1, verbs.stream().filter("released"::equals).count(),
                "an ordinary refusal was not released: " + verbs);
    }

    /** The shared mapping, over the far side directly or over HTTP to it. */
    private static Outcome perform(Lane tenant, boolean acrossTheWire) throws Exception {
        if (!acrossTheWire) {
            Run claimed = tenant.claim(tenant.poll(Set.of("register"), 1).get(0),
                    Duration.ofMinutes(1)).orElseThrow();
            return Performing.performed(tenant, claimed, REPORTING, Duration.ofMinutes(1));
        }
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/work", new LaneHandler("/work",
                authorization -> new Access.Grant("registrar",
                        Lane.Entitlement.everything(), true),
                (participant, identity, entitlement) -> tenant));
        server.start();
        try {
            Lane near = HttpLane.to(URI.create("http://localhost:"
                            + server.getAddress().getPort() + "/work"),
                    () -> "anything", "hogwarts", "registrar", identity());
            Run claimed = near.claim(near.poll(Set.of("register"), 1).get(0),
                    Duration.ofMinutes(1)).orElseThrow();
            return Performing.performed(near, claimed, REPORTING, Duration.ofMinutes(1));
        } finally {
            server.stop(0);
        }
    }

    /** A tenant handing out one run, and refusing the verb named with the refusal given. */
    private static Lane farSide(List<String> verbs, String refusing,
            Function<String, RuntimeException> refusal) {
        Run offered = new Run("run-1", 1, STEP + "/one", "hogwarts.admission", "register",
                RunKind.PIPELINE, null, null, null, Map.of(), null,
                List.of(), new Run.Assignment(Scope.BASELINE, identity(), null,
                        Instant.now().plusSeconds(60)),
                Run.Produced.NOTHING, "1", Map.of(), null,
                null, null, cloud.jengu.dbo.work.Status.IN_PROGRESS, true, null, null, 0,
                null);
        return (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    verbs.add(method.getName());
                    if (method.getName().equals(refusing)) {
                        throw refusal.apply("hogwarts: run '" + offered.key()
                                + "' is not claimed by registrar — somebody acted on it");
                    }
                    return switch (method.getName()) {
                        case "tenant" -> "hogwarts";
                        case "identity" -> identity();
                        case "poll" -> List.of(offered);
                        case "claim" -> java.util.Optional.of(offered);
                        case "checkpoint", "milestone" -> offered;
                        case "inputs" -> Map.of();
                        default -> null;
                    };
                });
    }

    private static Executor identity() {
        return new Executor("registrar", "1", "cloud.jengu.test", Scope.BASELINE);
    }
}
