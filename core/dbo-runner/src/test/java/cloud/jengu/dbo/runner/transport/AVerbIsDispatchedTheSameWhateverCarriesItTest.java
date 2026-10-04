package cloud.jengu.dbo.runner.transport;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatch a transport hands its verbs to, with no transport at all: a
 * caller and a verb go in, and what comes out is decided by the store.
 *
 * <p>The tenant here is a lane that records which participant it was built
 * for and what it was asked; the grants are the two ways a caller is known.
 */
class AVerbIsDispatchedTheSameWhateverCarriesItTest {

    private static final String SIGNED_BY = "signed-by-the-worker";

    private final List<String> built = new ArrayList<>();

    private final LaneVerbService verbs = new LaneVerbService(
            authorization -> "Bearer good".equals(authorization)
                    ? new Access.Grant("worker", Lane.Entitlement.ofSteps("lab.result.verify"),
                            false)
                    : new Access.Denied(401, "Bearer", "invalid token"),
            (participant, signed, signature) -> SIGNED_BY.equals(signature)
                    ? new Access.Grant(participant, Lane.Entitlement.ofSteps(
                            "lab.result.verify"), false)
                    : new Access.Denied(401, null, "an ask is signed by the enrolment key"),
            (participant, identity, entitlement) -> lane(participant, entitlement));

    @Test
    @DisplayName("a verb from a token and the same verb from a signature reach the same lane, "
            + "bounded by the same credential")
    @Proving(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS)
    void bothWaysOfBeingKnownReachTheSameLane() {
        LaneVerbService.Answer byToken = verbs.serve("Bearer good", LaneVerbs.POLL, poll("worker"));
        LaneVerbService.Answer bySignature = verbs.serveSigned("worker",
                "the ask's bytes".getBytes(StandardCharsets.UTF_8), SIGNED_BY,
                LaneVerbs.POLL, poll("worker"));

        assertTrue(byToken instanceof LaneVerbService.Answer.Ok, "by token: " + byToken);
        assertTrue(bySignature instanceof LaneVerbService.Answer.Ok,
                "by signature: " + bySignature);
        assertEquals(List.of("worker work=[lab.result.verify]", "worker work=[lab.result.verify]"),
                built, "the two callers were not given the same lane");
    }

    @Test
    @DisplayName("a caller the store does not know is denied before any lane is built, and a "
            + "bounded caller asking to work as somebody else is refused")
    @Proving(DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS)
    void theStoreDecidesWhoIsAsking() {
        LaneVerbService.Answer forged = verbs.serveSigned("worker",
                "the ask's bytes".getBytes(StandardCharsets.UTF_8), "somebody else's",
                LaneVerbs.POLL, poll("worker"));
        LaneVerbService.Answer unknown = verbs.serve("Bearer bad", LaneVerbs.POLL, poll("worker"));
        LaneVerbService.Answer asAnother = verbs.serve("Bearer good", LaneVerbs.POLL,
                poll("another-worker"));

        assertEquals(401, ((LaneVerbService.Answer.Denied) forged).status());
        assertEquals(401, ((LaneVerbService.Answer.Denied) unknown).status());
        assertEquals(403, ((LaneVerbService.Answer.Denied) asAnother).status());
        assertEquals(List.of(), built, "a lane was built for a caller the store refused");
    }

    private static Map<String, Object> poll(String executor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(LaneVerbs.PARTICIPANT, executor);
        body.put(LaneVerbs.IDENTITY, RecordWire.encode(
                new Executor(executor, "1", "example.worker", Scope.BASELINE)));
        body.put(LaneVerbs.STEPS, List.of("verify"));
        body.put(LaneVerbs.LIMIT, 10);
        return body;
    }

    private Lane lane(String participant, Lane.Entitlement entitlement) {
        built.add(participant + " " + entitlement.toString().split(" ")[0]);
        return (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) ->
                        "poll".equals(method.getName()) ? List.of() : null);
    }
}
