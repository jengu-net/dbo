package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.runner.transport.Access;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A heartbeat crosses a real HTTP door as one more verb: the statistics arrive
 * as the worker nested them, and a node's refusal comes back as a refusal.
 *
 * <p>The far side is a lane that records what reached it. What the node then
 * does with a heartbeat — whom it tells, and what it refuses for size — is
 * proven where a node is.
 */
class AHeartbeatIsAVerbOfTheLaneTest {

    private static final Map<String, Object> STATISTICS = Map.of(
            "dbo.runner", Map.of("lab.result.verify", Map.of("performed", 12L, "failed", 1L)),
            "example.bench", Map.of("behind", List.of(
                    Map.of("id", "line-1", "queued", 3L),
                    Map.of("id", "line-2", "queued", 0L))));

    @Test
    @DisplayName("a heartbeat crosses the lane with its statistics nested as the worker sent them")
    @Proving(DboPromises.PROC_A_HEARTBEAT_IS_A_LANE_VERB)
    void aHeartbeatCrossesWhole() throws Exception {
        List<Object> arrived = new ArrayList<>();
        Lane near = over(farSide(arrived, null));

        near.heartbeat(STATISTICS);

        assertEquals(List.of(STATISTICS), arrived,
                "the statistics arrived as something other than what was sent");
    }

    @Test
    @DisplayName("a heartbeat the node refuses is a refusal the worker hears, with the node's "
            + "reason")
    @Proving(DboPromises.PROC_A_HEARTBEAT_IS_A_LANE_VERB)
    void aRefusedHeartbeatIsHeard() throws Exception {
        Lane near = over(farSide(new ArrayList<>(),
                "a heartbeat's statistics are 70000 bytes, over this node's limit of 65536"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> near.heartbeat(STATISTICS));

        assertTrue(refused.getMessage().contains("limit of 65536"), refused.getMessage());
    }

    private static Lane over(Lane farSide) throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/work", new LaneHandler("/work",
                authorization -> new Access.Grant("bench",
                        Lane.Entitlement.everything(), true),
                (participant, identity, entitlement) -> farSide));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        return HttpLane.to(URI.create("http://localhost:" + server.getAddress().getPort()
                + "/work"), () -> "anything", "hospital", "bench", identity());
    }

    /** A lane that keeps each heartbeat's statistics, or refuses with the reason given. */
    private static Lane farSide(List<Object> arrived, String refusing) {
        return (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    if ("heartbeat".equals(method.getName())) {
                        if (refusing != null) {
                            throw new IllegalStateException(refusing);
                        }
                        arrived.add(arguments[0]);
                    }
                    return switch (method.getName()) {
                        case "tenant" -> "hospital";
                        case "identity" -> identity();
                        default -> null;
                    };
                });
    }

    private static Executor identity() {
        return new Executor("bench", "1", "example.bench", Scope.BASELINE);
    }
}
