package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.ProvingLane;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step answers with records, and the lane carries them to the tenant that
 * writes them — whole, across a real HTTP door, and back as the run the
 * tenant left.
 *
 * <p>The far side here is a lane that records what reached it rather than a
 * tenant, which is the point at this level: what is proven is the CARRYING —
 * that a result's records arrive as the step wrote them, and that a refusal
 * comes back as an answer the runner acts on rather than an error it retries.
 * That the tenant then writes them, all or none, is proven where a tenant is.
 */
class AResultCrossesTheLaneWholeTest {

    private static final String STEP = "hogwarts.admission.register";
    private static final String PATIENT = """
            {"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"39001"}],\
            "name":[{"family":"Weasley","given":["Ginevra"]}]}""";

    /** A step that answers with the person it was given, as a record to create. */
    private static final StepService REGISTERING = StepService.performing(STEP, work ->
            Outcome.done(Map.of("registered", 1L)).writing(Outcome.Write.create(
                    new String(work.input("patient").payload(), StandardCharsets.UTF_8))));

    @Test
    @DisplayName("a result's records cross the lane as the step wrote them, and the run comes "
            + "back closed")
    @Proving(DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT)
    void aResultCrossesWhole() throws Exception {
        List<String> verbs = new ArrayList<>();
        List<Outcome.Write> arrived = new ArrayList<>();
        Lane tenant = farSide(verbs, (result) -> {
            arrived.addAll(result);
            return null;
        });

        Outcome said = performOver(tenant);

        assertTrue(said instanceof Outcome.Done, "the step's work was not reported done: " + said);
        assertEquals(1, arrived.size(), "the result did not arrive: " + verbs);
        Outcome.Write write = arrived.get(0);
        assertEquals("POST", write.method());
        assertEquals("Patient", write.url());
        assertEquals(PATIENT, write.resource(),
                "the record arrived as something other than what the step wrote");
        assertTrue(verbs.contains("committed") && !verbs.contains("closed"),
                "a result that writes must land with its close, not as a bare close: " + verbs);
        assertTrue(!verbs.contains("released"), "a carried result was released: " + verbs);
    }

    @Test
    @DisplayName("a result the tenant refuses comes back as an ended run, and the runner "
            + "neither releases it for another attempt nor reports it closed")
    @Proving(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN)
    void aRefusedResultIsAnAnswer() throws Exception {
        List<String> verbs = new ArrayList<>();
        Lane tenant = farSide(verbs,
                result -> "hogwarts: Patient: the identifier urn:rl:nid|39001 is already held");

        Outcome said = performOver(tenant);

        assertTrue(said instanceof Outcome.Refused, "the refusal was not the outcome: " + said);
        assertTrue(((Outcome.Refused) said).reason().contains("already held"),
                "the tenant's reason did not reach the runner: " + said);
        assertTrue(!verbs.contains("released"),
                "a refused result was handed back for another attempt, which would be "
                        + "refused in the same words: " + verbs);
    }

    @Test
    @DisplayName("a step proven without a tenant shows the records its result asks for")
    @Proving(DboPromises.PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT)
    void aProvingLaneShowsTheResult() {
        ProvingLane lane = ProvingLane.offering(STEP).with("patient", "Patient", PATIENT).lane();
        new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(1))
                .register(REGISTERING).attach(lane).cycle();

        assertEquals(ProvingLane.Ended.CLOSED, lane.ended());
        assertEquals(List.of(Outcome.Write.create(PATIENT)), lane.written());
    }

    /** One cycle of a real runner, over HTTP, against the far side given. */
    private static Outcome performOver(Lane tenant) throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/work", new LaneHandler("/work",
                authorization -> new LaneHandler.Grant("registrar",
                        Lane.Entitlement.everything(), true),
                (participant, identity, entitlement) -> tenant));
        server.start();
        try {
            Lane near = HttpLane.to(URI.create("http://localhost:"
                            + server.getAddress().getPort() + "/work"),
                    () -> "anything", "hogwarts", "registrar", identity());
            // The mapping a runner and a fleet consumer share, asked for
            // directly so the test reads the outcome rather than a counter.
            Run claimed = near.claim(near.poll(java.util.Set.of("register"), 1).get(0),
                    Duration.ofMinutes(1)).orElseThrow();
            return cloud.jengu.dbo.runner.Performing.performed(near, claimed, REGISTERING,
                    Duration.ofMinutes(1));
        } finally {
            server.stop(0);
        }
    }

    /**
     * A tenant reduced to what this proof needs: one run to hand out, the
     * person it was given, and an answer to a result — null for written,
     * a reason for refused.
     */
    private static Lane farSide(List<String> verbs, Function<List<Outcome.Write>, String> answer) {
        Run offered = new Run("run-1", 1, STEP + "/one", "hogwarts.admission", "register",
                RunKind.PIPELINE, null, null, null, Map.of(), null,
                List.of(), new Run.Assignment(Scope.BASELINE, identity(), null,
                        Instant.now().plusSeconds(60)),
                Run.Produced.NOTHING, "1", Map.of("patient", RunSlot.given(PATIENT)), null,
                "the-asker", null, cloud.jengu.dbo.work.Status.IN_PROGRESS, true, null, null, 0,
                null);
        return (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    verbs.add(method.getName());
                    return switch (method.getName()) {
                        case "tenant" -> "hogwarts";
                        case "identity" -> identity();
                        case "poll" -> List.of(offered);
                        case "claim" -> java.util.Optional.of(offered);
                        case "checkpoint", "milestone" -> offered;
                        case "inputs" -> Map.of("patient",
                                List.of(RunSlot.asObject(PATIENT)));
                        case "committed" -> {
                            @SuppressWarnings("unchecked")
                            String refused = answer.apply((List<Outcome.Write>) arguments[2]);
                            yield new Run(offered.id(), 2, offered.key(), offered.process(),
                                    offered.step(), offered.kind(), null, null,
                                    null, Map.of("registered", 1L), null, List.of(),
                                    offered.assignment(), refused == null
                                            ? new Run.Produced(List.of("Patient/p1/1"), Map.of(), 1)
                                            : Run.Produced.NOTHING,
                                    "1", offered.inputs(), null, "the-asker", refused,
                                    refused == null ? cloud.jengu.dbo.work.Status.COMPLETED
                                            : cloud.jengu.dbo.work.Status.FAILED,
                                    true, null, refused, 0, null);
                        }
                        default -> null;
                    };
                });
    }

    private static Executor identity() {
        return new Executor("registrar", "1", "cloud.jengu.test", Scope.BASELINE);
    }
}
