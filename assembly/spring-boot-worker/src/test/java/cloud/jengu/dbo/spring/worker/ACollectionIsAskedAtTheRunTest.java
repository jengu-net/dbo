package cloud.jengu.dbo.spring.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an application collecting through its run sends: to the run's own
 * address, on the credential it asked with, the record as the run names it,
 * and a purpose only when it states one.
 *
 * <p>Against a socket that records what arrives and answers as the tenant's
 * door would. What the door then decides is the door's, and proven there.
 */
class ACollectionIsAskedAtTheRunTest {

    private final List<String> arrived = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private DboInitiator initiator;

    @BeforeEach
    void aTenantsRunDoor() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/t/st-jerome/run", exchange -> {
            arrived.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " " + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + exchange.getRequestHeaders().getFirst("Purpose-Of-Use"));
            boolean known = exchange.getRequestURI().getPath().contains("/run-1/");
            byte[] body = (known ? "{\"resourceType\":\"Patient\"}"
                    : "{\"error\":\"not_found\",\"detail\":\"no such run\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        DboWorkerProperties.Lane lane = new DboWorkerProperties.Lane();
        lane.setTenant("st-jerome");
        lane.setBase(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/t/st-jerome/"));
        DboWorkerProperties properties = new DboWorkerProperties();
        properties.setLanes(List.of(lane));
        initiator = new DboInitiator(properties, Map.of("st-jerome", () -> "the-token"));
    }

    @AfterEach
    void closed() {
        server.stop(0);
    }

    @Test
    @DisplayName("a version the run produced is collected at the run's context, on the asking "
            + "credential, stating no purpose unless the application states one")
    void aCollectionIsARequestToTheRun() {
        DboInitiator.Collected plain = initiator.collect("st-jerome", "run-1",
                "Patient/p1/_history/2");
        DboInitiator.Collected treating = initiator.collecting("st-jerome", "run-1",
                "Patient/p1/_history/2", "TREAT");

        assertTrue(plain.found());
        assertEquals("{\"resourceType\":\"Patient\"}", treating.recordOrFail());
        assertEquals(List.of(
                "GET /t/st-jerome/run/run-1/fhir/Patient/p1/_history/2 Bearer the-token null",
                "GET /t/st-jerome/run/run-1/fhir/Patient/p1/_history/2 Bearer the-token TREAT"),
                arrived);
    }

    @Test
    @DisplayName("an application says it has collected at the run's done, and a run that "
            + "answers nothing is said to have had no window")
    void collectedIsTheRunsDone() {
        assertTrue(initiator.collected("st-jerome", "run-1"));
        assertFalse(initiator.collected("st-jerome", "run-2"));
        assertFalse(initiator.collect("st-jerome", "run-2", "Patient/p1").found());
        assertEquals("POST /t/st-jerome/run/run-1/done Bearer the-token null", arrived.get(0));
    }
}
