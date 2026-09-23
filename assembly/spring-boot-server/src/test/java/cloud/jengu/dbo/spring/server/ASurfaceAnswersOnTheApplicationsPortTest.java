package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.core.api.Audience;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.Reach;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's own doors, answered by the application's web tier.
 *
 * <p>Every surface a tenant offers is a {@code com.sun.net.httpserver}
 * handler on one server, so filling that one seam mounts all of them on the
 * application's port at once. What is asserted here is the seam and not the
 * surfaces: a handler of this test's own, driven through the adapter with the
 * servlet objects a container would supply, and nothing of the runtime
 * anywhere near it. A tenant answering a FHIR read through this is a claim
 * about the whole arrangement and is made where there is a tenant to answer.
 */
class ASurfaceAnswersOnTheApplicationsPortTest {

    @Test
    @DisplayName("a handler's status, headers and body reach the servlet response as it wrote "
            + "them, and its request reaches the handler as the client sent it")
    void whatAHandlerWritesIsWhatTheApplicationAnswers() throws IOException {
        SpringHttpServer server = new SpringHttpServer();
        AtomicReference<String> sawBody = new AtomicReference<>();
        AtomicReference<String> sawHeader = new AtomicReference<>();
        server.createContext("/t/hogwarts/fhir", exchange -> {
            sawBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            sawHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] said = "{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(201, said.length);
            exchange.getResponseBody().write(said);
        });

        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/t/hogwarts/fhir/Patient");
        request.setContent("{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer a-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(server.serve(request, response), "no surface answered a path one is mounted "
                + "at, so the store's doors are 404 on this application's port");

        assertEquals("{\"resourceType\":\"Patient\"}", sawBody.get(),
                "the handler did not receive the body the client sent");
        assertEquals("Bearer a-token", sawHeader.get(),
                "the handler did not receive the Authorization header, so every surface would "
                        + "refuse every caller as unauthenticated");
        assertEquals(201, response.getStatus());
        assertEquals("application/fhir+json", response.getHeader("Content-Type"));
        assertEquals("{\"resourceType\":\"Patient\"}", response.getContentAsString());
    }

    @Test
    @DisplayName("the longest context at or before a path answers it, which is the rule the "
            + "runtime mounts its surfaces expecting")
    void theLongestMatchingContextAnswers() throws IOException {
        SpringHttpServer server = new SpringHttpServer();
        server.createContext("/t/hogwarts/fhir", answering("records"));
        server.createContext("/t/hogwarts/fhir/Binary", answering("content"));
        server.createContext("/t/hogwarts", answering("the tenant"));

        assertEquals("content", answerTo(server, "/t/hogwarts/fhir/Binary/abc"),
                "a longer context was mounted and a shorter one answered, so the content door "
                        + "would be served by the records door");
        assertEquals("records", answerTo(server, "/t/hogwarts/fhir/Patient/1"));
        assertEquals("the tenant", answerTo(server, "/t/hogwarts/oidc/token"));
    }

    @Test
    @DisplayName("a path no surface is mounted at is left alone, so the application's own "
            + "controllers still answer on the port they share")
    void aPathTheStoreDoesNotOwnIsLeftAlone() throws IOException {
        SpringHttpServer server = new SpringHttpServer();
        server.createContext("/t/hogwarts/fhir", answering("records"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(server.serve(new MockHttpServletRequest("GET", "/actuator/health"), response),
                "the store claimed a path it has no door at, so an application sharing this "
                        + "port would find its own endpoints answered by a tenant");
        assertEquals("", response.getContentAsString());
    }

    @Test
    @DisplayName("a body of unknown length is written as it is produced, because a search "
            + "answering with a large bundle is not held in memory to learn its size")
    void aStreamedBodyIsNotBuffered() throws IOException {
        SpringHttpServer server = new SpringHttpServer();
        server.createContext("/t/hogwarts/fhir", exchange -> {
            // 0 is the JDK server's "as much as is written". The records
            // surface writes its headers on the first byte for exactly this
            // reason, and an adapter that set a Content-Length here would
            // undo it for every read the store serves.
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int each = 0; each < 3; each++) {
                out.write(("chunk" + each).getBytes(StandardCharsets.UTF_8));
            }
        });

        MockHttpServletResponse response = new MockHttpServletResponse();
        server.serve(new MockHttpServletRequest("GET", "/t/hogwarts/fhir/Patient"), response);

        assertEquals("chunk0chunk1chunk2", response.getContentAsString());
        assertNull(response.getHeader("Content-Length"),
                "a length was declared for a body whose length the handler did not know");
    }

    @Test
    @DisplayName("a second request finds nothing the first request's credential bound, which "
            + "the serving distribution gets from a thread per request and a pool does not")
    void aCredentialDoesNotOutliveItsRequest() throws IOException {
        SpringHttpServer server = new SpringHttpServer();
        AtomicReference<String> callerOnEntry = new AtomicReference<>();
        AtomicReference<Set<String>> reachOnEntry = new AtomicReference<>();
        AtomicReference<String> audienceOnEntry = new AtomicReference<>();
        AtomicReference<String> purposeOnEntry = new AtomicReference<>();
        server.createContext("/t/hogwarts/fhir", exchange -> {
            callerOnEntry.set(Caller.current());
            reachOnEntry.set(Reach.organisations());
            audienceOnEntry.set(Audience.named());
            purposeOnEntry.set(Disclosure.purpose());
            // What a tenant's guard binds for the rest of a request.
            Caller.set("Practitioner/one");
            Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
            Reach.bind(Set.of("Organization/ward"));
            Audience.serving("a-partner");
            exchange.sendResponseHeaders(204, -1);
        });

        // Both on this thread, which is what a servlet container's pool does
        // and what the JDK server's thread-per-request never could.
        server.serve(new MockHttpServletRequest("GET", "/t/hogwarts/fhir/Patient/1"),
                new MockHttpServletResponse());
        server.serve(new MockHttpServletRequest("GET", "/t/hogwarts/fhir/Patient/2"),
                new MockHttpServletResponse());

        assertEquals("system", callerOnEntry.get(),
                "the second request opened holding the first request's caller, so the trail "
                        + "would attribute its writes to somebody who never made them");
        assertNull(reachOnEntry.get(),
                "the second request opened bound to the organisations the FIRST credential was "
                        + "entitled to read from");
        assertNull(audienceOnEntry.get(),
                "the second request opened answering as the audience the first was answered as");
        assertNull(purposeOnEntry.get(),
                "the second request opened under the first request's purpose of use, which is "
                        + "the reason the trail would record for disclosing somebody");
    }

    private static HttpHandler answering(String said) {
        return exchange -> {
            byte[] body = said.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        };
    }

    private static String answerTo(SpringHttpServer server, String path) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(server.serve(new MockHttpServletRequest("GET", path), response),
                "nothing answered " + path);
        return response.getContentAsString();
    }
}
