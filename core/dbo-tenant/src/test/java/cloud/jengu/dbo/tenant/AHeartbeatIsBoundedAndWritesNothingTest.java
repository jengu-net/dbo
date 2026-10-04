package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Contacts;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a node does with a heartbeat before anybody hears it: measures it,
 * and hands nothing to the tenant but the verb itself.
 *
 * <p>The lane underneath records every verb it is asked, so a heartbeat that
 * reached a write, a claim or anything but itself would show here. Nobody
 * listens, so the store and the declarations are never asked for.
 */
class AHeartbeatIsBoundedAndWritesNothingTest {

    private static final int LIMIT = 1024;

    @Test
    @DisplayName("statistics over the node's limit are refused, naming the limit, before "
            + "they reach the lane")
    @Proving(DboPromises.PROC_HEARTBEAT_STATISTICS_ARE_OPAQUE_AND_BOUNDED)
    void anOversizedHeartbeatIsRefused() {
        List<String> asked = new ArrayList<>();
        ContactLane lane = heard(asked);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.heartbeat(Map.of("example.worker", "x".repeat(LIMIT))));

        assertTrue(refused.getMessage().contains("limit of " + LIMIT + " bytes"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains(ContactLane.HEARTBEAT_LIMIT),
                "the refusal names the setting that would change it: " + refused.getMessage());
        assertEquals(List.of(), asked, "a refused heartbeat reached the lane");
    }

    @Test
    @DisplayName("statistics within the limit pass through unread, whatever they nest, and the "
            + "heartbeat is the only thing the lane is asked")
    @Proving(DboPromises.PROC_HEARTBEAT_STATISTICS_ARE_OPAQUE_AND_BOUNDED)
    void aHeartbeatWithinTheLimitIsTheOnlyVerb() {
        List<String> asked = new ArrayList<>();
        ContactLane lane = heard(asked);

        lane.heartbeat(Map.of("example.worker",
                Map.of("anything", List.of(1L, Map.of("at", "any depth")))));

        assertEquals(List.of("heartbeat"), asked);
    }

    private static ContactLane heard(List<String> asked) {
        Lane underneath = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> switch (
                        method.getName()) {
                    case "tenant" -> "hospital";
                    case "identity" -> new Executor("worker", "1", "example.worker",
                            Scope.BASELINE);
                    default -> {
                        asked.add(method.getName());
                        yield null;
                    }
                });
        return new ContactLane(underneath, new Contacts("node-a", Clock.systemUTC(), null,
                (listener, threw) -> {
                    throw threw;
                }), null, null, LIMIT, "worker");
    }
}
