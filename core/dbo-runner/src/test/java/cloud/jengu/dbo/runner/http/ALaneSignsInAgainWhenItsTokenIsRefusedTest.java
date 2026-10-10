package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.transport.Access;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A tenant whose authority restarted takes none of the tokens it issued
 * before. A credential that keeps its token until near expiry kept offering
 * the old one, and every verb failed until the token expired by itself.
 */
class ALaneSignsInAgainWhenItsTokenIsRefusedTest {

    @Test
    @DisplayName("a token the tenant answers 401 to is dropped, and the lane's next call "
            + "signs in again")
    @Proving(DboPromises.PROC_A_LANE_SIGNS_IN_AGAIN_WHEN_ITS_TOKEN_IS_REFUSED)
    void aRefusedTokenIsDropped() throws Exception {
        Kept credential = new Kept();
        Lane lane = against("token-2", credential);

        assertThrows(IllegalStateException.class, () -> lane.heartbeat(Map.of()),
                "the tenant took a token its authority no longer issues");
        assertDoesNotThrow(() -> lane.heartbeat(Map.of()),
                "the lane offered the refused token again");
        assertEquals(List.of("token-1", "token-2"), credential.issued);
    }

    /** Keeps its token until told the tenant refused it, as a caching client does. */
    private static final class Kept implements LaneCredential {

        final List<String> issued = new ArrayList<>();
        private String token;

        @Override
        public synchronized String get() {
            if (token == null) {
                token = "token-" + (issued.size() + 1);
                issued.add(token);
            }
            return token;
        }

        @Override
        public synchronized void refused(String refused) {
            if (refused.equals(token)) {
                token = null;
            }
        }
    }

    /** A tenant that takes only the token named, and answers 401 to any other. */
    private static Lane against(String taken, LaneCredential credential) throws Exception {
        Lane farSide = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) ->
                        "tenant".equals(method.getName()) ? "hospital" : null);
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/work", new LaneHandler("/work",
                authorization -> ("Bearer " + taken).equals(authorization)
                        ? new Access.Grant("worker", Lane.Entitlement.everything(), true)
                        : new Access.Denied(401, "Bearer error=\"invalid_token\"",
                                "invalid token"),
                (participant, identity, entitlement) -> farSide));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        return HttpLane.to(URI.create("http://localhost:" + server.getAddress().getPort()
                        + "/work"), credential, "hospital", "worker",
                new Executor("worker", "1", "example.worker", Scope.BASELINE));
    }
}
