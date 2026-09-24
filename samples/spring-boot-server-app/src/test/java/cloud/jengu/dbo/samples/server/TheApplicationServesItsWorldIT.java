package cloud.jengu.dbo.samples.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This application serves the world in its own directory.
 *
 * <p>The assembly's own tests prove the wrapper: a container boots inside a
 * Spring context, an exchange converts, a filter offers a request. This one
 * proves something else, and it is what an integrator is actually asking —
 * that an application of the shape somebody would write, with a world of spec
 * files and no code about containers, comes up and answers.
 *
 * <p><b>Nothing here is a fixture.</b> The world under test is the module's
 * own {@code world/} directory, the one a reader opens; the application is the
 * one {@code main} starts. A test that built its own tenant would prove the
 * wrapper again and say nothing about this application.
 */
@DboSpringBootTest
class TheApplicationServesItsWorldIT {

    private static final String TENANT = "hogwarts";

    @Value("${server.port}")
    int applicationPort;

    @Test
    @DisplayName("the tenant in this application's world answers a FHIR read on the "
            + "application's own port, and refuses a caller carrying nothing")
    void theWorldIsServed() throws Exception {
        String base = "http://127.0.0.1:" + applicationPort + "/t/" + TENANT;
        untilServing(base);

        HttpResponse<String> capability = get(base + "/fhir/metadata");
        assertEquals(200, capability.statusCode(),
                "the tenant declared in this application's world does not answer on its port, "
                        + "so adding the dependency started a container and served nobody: "
                        + capability.body());
        assertTrue(capability.body().contains("CapabilityStatement"),
                "something answered and it was not the records door: " + capability.body());

        // The door is the tenant's, not the application's, so it refuses a
        // caller with no credential even though the port is the application's
        // and the filter chain let the request through.
        assertEquals(401, get(base + "/fhir/Patient").statusCode(),
                "the records door answered a caller carrying nothing, so this application "
                        + "serves a tenant's records to anybody who knows the path");
    }

    /** A tenant expands a whole FHIR version the first time it comes up. */
    private static void untilServing(String base) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofMinutes(6).toNanos();
        while (System.nanoTime() < giveUp) {
            if (get(base + "/fhir/metadata").statusCode() == 200) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("the tenant never came up, so this application does not serve "
                + "the world in its own directory");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
