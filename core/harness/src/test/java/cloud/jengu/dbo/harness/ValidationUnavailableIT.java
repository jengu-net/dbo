package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import cloud.jengu.dbo.fhir.common.ValidationUnavailableException;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.rest.FhirHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A busy machine does not tell a caller their resource is malformed
 * (REQ-DBO-VER-SPECIFIED-VALIDATION).
 *
 * <p>HL7's ReDoS guard runs each primitive-type regex on its own thread against
 * a 500ms wall clock, so under load it expires on values that match in
 * microseconds — `rest-hook` was the one that started this. The store asks
 * again once, and if the clock runs out twice it says the verdict is
 * unavailable rather than inventing one.
 *
 * <p>Driven through a stub facade rather than by loading the machine, because
 * the thing worth pinning is what a CALLER is told. Reproducing the timeout
 * would need a contended CPU and would be the kind of test that passes when the
 * bug is present and fails when the box is idle.
 */
class ValidationUnavailableIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Answers every write with the given failure; enough of a store to route. */
    private record Throwing(RuntimeException failure) implements FhirStoreFacade {

        @Override
        public PutResult create(String resourceJson) {
            throw failure;
        }

        @Override
        public PutResult update(String id, Long expectedVersion, String resourceJson) {
            throw failure;
        }

        @Override
        public PutResult conditionalCreate(String json, Map<String, String> condition) {
            throw failure;
        }

        @Override
        public String validationOutcome(String resourceJson) {
            throw failure;
        }

        @Override
        public String read(String typeName, String id) {
            return null;
        }

        @Override
        public ReadResult readForServing(String typeName, String id) {
            return null;
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion) {
        }

        @Override
        public String search(String typeName, Map<String, String> params, String cursor) {
            return "{}";
        }

        @Override
        public String historyBundle(String typeName, String id) {
            return "{}";
        }

        @Override
        public String capabilityStatement(String baseUrl) {
            return "{\"resourceType\":\"CapabilityStatement\"}";
        }

        @Override
        public String operationOutcome(String issueCode, String diagnostics) {
            return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\""
                    + issueCode + "\",\"diagnostics\":\"" + diagnostics + "\"}]}";
        }

        @Override
        public boolean knowsType(String typeName) {
            return true;
        }
    }

    private static HttpResponse<String> postTo(FhirStoreFacade store) throws Exception {
        try (FhirHttpServer server = new FhirHttpServer(store, null, "127.0.0.1", 0, "/fhir")) {
            return HTTP.send(HttpRequest.newBuilder(
                            URI.create(server.baseUrl() + "/Subscription"))
                            .header("Content-Type", "application/fhir+json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"resourceType\":\"Subscription\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a verdict that could not be reached answers 503 and asks for a retry, "
            + "never 422")
    @Proving(DboPromises.VER_SPECIFIED_VALIDATION)
    void anUnreachableVerdictIsNotARejection() throws Exception {
        HttpResponse<String> response = postTo(new Throwing(
                new ValidationUnavailableException("Subscription",
                        "ERROR Subscription.channel.type: Regex evaluation timed out after ")));

        assertEquals(503, response.statusCode(),
                "a caller was told something about their resource: " + response.body());
        assertEquals("1", response.headers().firstValue("Retry-After").orElse(null),
                "a 503 with no Retry-After leaves the caller guessing whether to try again");
        assertTrue(response.body().contains("\"code\":\"timeout\""), response.body());
    }

    /** The neighbouring case still answers as before — this is not a blanket softening. */
    @Test
    @Timeout(60)
    @DisplayName("a resource that really is invalid still answers 422")
    void arealRejectionIsStill422() throws Exception {
        HttpResponse<String> response = postTo(new Throwing(
                new ValidationFailedException("Subscription",
                        List.of("ERROR Subscription.status: minimum required = 1"))));

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"invalid\""), response.body());
    }
}
