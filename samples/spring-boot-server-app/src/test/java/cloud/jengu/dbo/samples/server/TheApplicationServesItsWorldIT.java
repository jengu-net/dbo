package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This application serves the world beside it.
 *
 * <p>The assembly's own tests prove the wrapper: a container boots inside a
 * Spring context, an exchange converts, a filter offers a request. This one
 * proves something else, and it is what an integrator is actually asking —
 * that an application of the shape somebody would write comes up and answers.
 *
 * <p><b>Nothing here is a fixture.</b> The world is {@code samples/sample-world},
 * the one a reader opens; the application is the one {@code main} starts. A
 * test that built its own tenant would prove the wrapper again and say nothing
 * about this application.
 */
@DboSpringBootTest
class TheApplicationServesItsWorldIT {

    private static final String TENANT = "hogwarts";

    @Autowired
    DboTestContext dbo;

    @Test
    @DisplayName("the tenant in this application's world answers a FHIR read on the "
            + "application's own port, and refuses a caller carrying nothing")
    void theWorldIsServed() {
        assertTrue(dbo.until(TENANT, true, Duration.ofMinutes(6)),
                "the tenant declared in this application's world never came up, so adding the "
                        + "dependency started a container and served nobody: " + dbo.serving());

        // Fetched once and asked several times, and asserted as a GROUP: a
        // bring-up costs minutes, so a test that stopped at the first
        // disagreement would spend them again for the second.
        var tenantSays = dbo.capability(TENANT);
        assertAll(
                () -> assertTrue(tenantSays.serves("Patient"), tenantSays.why("serves Patient")),
                () -> assertTrue(tenantSays.interactionsWith("Patient").contains("create"),
                        tenantSays.why("may be written to for Patient")),
                () -> assertTrue(tenantSays.searchableBy("Patient").contains("identifier"),
                        tenantSays.why("may be searched by identifier for Patient")));

        // The door is the TENANT's, not the application's: it refuses a caller
        // with no credential even though the port is the application's and its
        // filter chain let the request through.
        assertEquals(401, dbo.get(dbo.at(TENANT) + "/fhir/Patient", null).statusCode(),
                "the records door answered a caller carrying nothing, so this application "
                        + "serves a tenant's records to anybody who knows the path");
    }
}
