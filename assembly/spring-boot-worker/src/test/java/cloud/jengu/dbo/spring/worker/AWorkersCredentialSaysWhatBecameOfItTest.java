package cloud.jengu.dbo.spring.worker;

import cloud.jengu.dbo.core.api.StoreUnreachableException;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The worker's own credential, against an authority that answers as told: a
 * token for an hour, a refusal, or a server error.
 */
class AWorkersCredentialSaysWhatBecameOfItTest {

    private HttpServer authority;

    @AfterEach
    void down() {
        if (authority != null) {
            authority.stop(0);
        }
    }

    @Test
    @DisplayName("a token the tenant refused is signed in for again on the next call, not "
            + "at its expiry")
    @Proving(DboPromises.PROC_A_LANE_SIGNS_IN_AGAIN_WHEN_ITS_TOKEN_IS_REFUSED)
    void aRefusedTokenIsSignedInForAgain() throws Exception {
        AtomicInteger signIns = new AtomicInteger();
        ClientCredentials credential = against(200, signIns);
        String first = credential.get();
        assertEquals(first, credential.get(), "a token an hour from expiry was not kept");

        credential.refused(first);

        assertNotEquals(first, credential.get(), "the refused token was offered again");
        assertEquals(2, signIns.get());
    }

    @Test
    @DisplayName("a refusal of a token already replaced leaves the new one kept")
    @Proving(DboPromises.PROC_A_LANE_SIGNS_IN_AGAIN_WHEN_ITS_TOKEN_IS_REFUSED)
    void aStaleRefusalKeepsTheNewToken() throws Exception {
        AtomicInteger signIns = new AtomicInteger();
        ClientCredentials credential = against(200, signIns);
        String first = credential.get();
        credential.refused(first);
        String second = credential.get();

        credential.refused(first);

        assertEquals(second, credential.get(), "a call refused the old token dropped the new one");
        assertEquals(2, signIns.get());
    }

    @Test
    @DisplayName("an authority that cannot be reached is the link down")
    @Proving(DboPromises.PROC_AN_AUTHORITY_OUT_OF_REACH_IS_THE_LINK_DOWN)
    void anAuthorityOutOfReachIsTheLinkDown() throws Exception {
        int nobody;
        try (ServerSocket free = new ServerSocket(0)) {
            nobody = free.getLocalPort();
        }
        ClientCredentials credential = new ClientCredentials(
                URI.create("http://127.0.0.1:" + nobody + "/t/hospital/"), "hospital",
                "worker", "secret");

        assertThrows(StoreUnreachableException.class, credential::get);
    }

    @Test
    @DisplayName("an authority answering a server error is the link down")
    @Proving(DboPromises.PROC_AN_AUTHORITY_OUT_OF_REACH_IS_THE_LINK_DOWN)
    void aServerErrorIsTheLinkDown() throws Exception {
        ClientCredentials credential = against(503, new AtomicInteger());

        assertThrows(StoreUnreachableException.class, credential::get);
    }

    @Test
    @DisplayName("an authority that refuses the credential is a refusal, not the link down")
    @Proving(DboPromises.PROC_AN_AUTHORITY_OUT_OF_REACH_IS_THE_LINK_DOWN)
    void aRefusalIsStillARefusal() throws Exception {
        ClientCredentials credential = against(401, new AtomicInteger());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                credential::get);
        assertFalse(refused instanceof StoreUnreachableException,
                "a refused credential read as the link down");
    }

    /** A credential against an authority that answers every sign-in with that status. */
    private ClientCredentials against(int status, AtomicInteger signIns) throws Exception {
        authority = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authority.createContext("/t/hospital/oidc/token", exchange -> {
            int n = signIns.incrementAndGet();
            byte[] body = (status == 200
                    ? "{\"access_token\":\"token-" + n + "\",\"expires_in\":3600}"
                    : "{\"error\":\"invalid_client\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        authority.start();
        return new ClientCredentials(URI.create("http://127.0.0.1:"
                + authority.getAddress().getPort() + "/t/hospital/"), "hospital", "worker",
                "secret");
    }
}
