package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles("test")
class TheApplicationServesItsWorldIT {

    private static final String TENANT = "hogwarts";

    @Autowired
    DboTestContext dbo;

    @Test
    @DisplayName("the tenant in this application's world answers a FHIR read on the "
            + "application's own port, and refuses a caller carrying nothing")
    @Proving(DboPromises.CONT_EMBEDDED_IN_JVM)
    void theWorldIsServed() {
        assertTrue(dbo.until(TENANT, true, Duration.ofMinutes(6)),
                "the tenant declared in this application's world never came up, so adding the "
                        + "dependency started a container and served nobody: " + dbo.serving());

        // Fetched once and asked several times, and asserted as a GROUP: a
        // bring-up costs minutes, so a test that stopped at the first
        // disagreement would spend them again for the second.
        var tenantSays = dbo.capability(TENANT);
        Proves.all(DboPromises.CONT_EMBEDDED_IN_JVM,
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

    @Test
    @DisplayName("a record written through this application's port is stored with its person "
            + "sealed, and a search that cannot reach under the membrane is refused rather "
            + "than answered empty")
    void aRecordIsWrittenAndFound() {
        assertTrue(dbo.until(TENANT, true, Duration.ofMinutes(6)),
                "the tenant never came up: " + dbo.serving());

        var written = dbo.write(TENANT, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"RL-9001"}],
                 "name":[{"family":"Kontekst","given":["Anu"]}]}""");
        assertTrue(written.accepted(),
                "a correct document was not accepted: " + written.statusCode() + " "
                        + written.body());

        // READ BACK, AND THE PERSON IS NOT IN IT. This tenant declares pdi, so
        // the identifying elements are encrypted in the payload and a system
        // credential sees the record without them. That is the vault working
        // rather than a write losing data — and asserting the name came back
        // would have been asserting the opposite of what this store promises.
        String id = written.idOrFail();
        var back = dbo.read(TENANT, "Patient", id);
        var record = dbo.says(back);
        assertAll(
                () -> assertEquals(200, back.statusCode(),
                        "the record was not readable: " + back.body()),
                // Asked of the document rather than matched in its text: a
                // name absent from the payload and a name that happens not to
                // appear in a rendering are different answers.
                () -> assertTrue(!record.has("name"),
                        "a name came back to a caller holding a system credential, so this "
                                + "tenant's vault is not holding the person it declared it "
                                + "would: " + back.body()),
                () -> assertEquals(java.util.Optional.of("Patient"), record.one("resourceType"),
                        "what came back is not the record that was written: " + back.body()));

        // AND AN IDENTIFYING SEARCH IS REFUSED, NOT ANSWERED EMPTY. This
        // tenant holds Patient.identifier under the membrane, so the store
        // cannot match on it — and says so, because an empty page would have
        // said nobody has that identifier, which is a different thing and the
        // one a caller would have believed.
        var found = dbo.search(TENANT, "Patient", "identifier=urn:rl:nid|RL-9001", "TREAT");
        assertAll(
                () -> assertEquals(403, found.statusCode(),
                        "a search the store cannot make was not refused: " + found.body()),
                () -> assertTrue(found.body().contains("under the membrane"),
                        "the refusal did not say why it cannot match, so a caller cannot tell "
                                + "it from a rejection: " + found.body()));
    }
}
