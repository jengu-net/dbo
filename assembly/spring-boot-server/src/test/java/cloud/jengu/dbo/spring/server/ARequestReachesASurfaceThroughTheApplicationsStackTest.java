package cloud.jengu.dbo.spring.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A request arrives at the application, and a tenant's door answers it.
 *
 * <p>The adapter beside this proves that a handler driven with servlet
 * objects behaves; this proves the step before it — that a request coming
 * through the application's own filter chain is offered to the surfaces at
 * all, and that one landing anywhere else carries on being the
 * application's.
 *
 * <p>The handler here is the test's own. A tenant's records door answering a
 * read is the claim that ties the whole chain together, and it is made where
 * there is a tenant to answer: it needs a database, a spec and a bring-up,
 * and asserting it here would mean standing all three up to learn something
 * about a filter.
 */
class ARequestReachesASurfaceThroughTheApplicationsStackTest {

    @Test
    @DisplayName("a request on a path a tenant has a door at is answered by that door, through "
            + "the application's own filter chain")
    void aRequestOnATenantsPathIsAnsweredByIt() throws Exception {
        SpringHttpServer surfaces = new SpringHttpServer();
        surfaces.createContext("/t/hogwarts/fhir", exchange -> {
            byte[] said = "{\"resourceType\":\"CapabilityStatement\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(200, said.length);
            exchange.getResponseBody().write(said);
        });
        DboSurfaceFilter filter = filterOn(surfaces);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/t/hogwarts/fhir/metadata");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain andThen = new MockFilterChain();

        filter.doFilter(request, response, andThen);

        assertEquals(200, response.getStatus(),
                "the tenant's door did not answer, so this request went past it to whatever "
                        + "the application routes by default");
        assertEquals("application/fhir+json", response.getHeader("Content-Type"),
                "the door's own headers did not reach the response");
        assertEquals("{\"resourceType\":\"CapabilityStatement\"}", response.getContentAsString(),
                "the door's own body did not reach the response");
        assertNull(andThen.getRequest(),
                "the request carried on down the chain after a tenant answered it, so the "
                        + "application would try to route a response it has already sent");
    }

    @Test
    @DisplayName("a request on a path no tenant has a door at carries on down the chain, so "
            + "the application's own endpoints still answer on the port they share")
    void aRequestOnTheApplicationsOwnPathCarriesOn() throws Exception {
        SpringHttpServer surfaces = new SpringHttpServer();
        surfaces.createContext("/t/hogwarts/fhir", exchange -> exchange
                .sendResponseHeaders(200, -1));
        DboSurfaceFilter filter = filterOn(surfaces);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain andThen = new MockFilterChain();

        filter.doFilter(request, response, andThen);

        assertNotNull(andThen.getRequest(),
                "the store swallowed a request to a path it has no door at, so an application "
                        + "sharing this port would find its own endpoints unreachable");
        assertEquals(200, response.getStatus(), "the store answered a request it did not own");
    }

    @Test
    @DisplayName("a tenant that comes up after startup is reachable, because every request is "
            + "offered to the surfaces rather than to a pattern fixed when the filter was made")
    void aDoorMountedAfterStartupIsReachable() throws Exception {
        SpringHttpServer surfaces = new SpringHttpServer();
        DboSurfaceFilter filter = filterOn(surfaces);

        MockFilterChain before = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/t/gringotts/fhir/metadata"),
                new MockHttpServletResponse(), before);
        assertNotNull(before.getRequest(), "a path no tenant is serving was claimed anyway");

        // A tenant brought up by the scan loop, minutes after the application
        // started. Nothing re-registers the filter.
        surfaces.createContext("/t/gringotts/fhir", exchange -> {
            byte[] said = "up now".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, said.length);
            exchange.getResponseBody().write(said);
        });

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/t/gringotts/fhir/metadata"),
                response, new MockFilterChain());

        assertEquals("up now", response.getContentAsString(),
                "a tenant that came up after the application started is unreachable, so this "
                        + "deployment serves whoever happened to be declared at boot");
    }

    private static DboSurfaceFilter filterOn(SpringHttpServer surfaces) throws Exception {
        DboSurfaceFilter filter = new DboSurfaceFilter(surfaces);
        // OncePerRequestFilter needs a servlet context to be initialised, the
        // same as it would get from the container.
        filter.init(new org.springframework.mock.web.MockFilterConfig(new MockServletContext()));
        return filter;
    }
}
