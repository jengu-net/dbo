package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller can ask whether a resource would be accepted, without writing it
 * (#48).
 *
 * <p>The property worth having is not that the operation answers — it is that
 * <b>the two answers agree</b>. What `$validate` accepts, a write accepts; what
 * it rejects, a write rejects. A second opinion is worse than none, because the
 * one a caller consulted would not be the one that mattered.
 *
 * <p>Today's scope is the resource's shape against the profiles a write applies.
 * State — an identity already claimed, a version moved on — is answered by the
 * write and is not promised here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidateOperationIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static FhirHttpServer server;
    static R4Store store;

    /** Valid: an Observation with the two elements the base profile requires. */
    private static final String VALID = """
            {"resourceType":"Observation","status":"final",
             "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}}""";

    /** Invalid: the same resource with both of them missing. */
    private static final String INVALID = """
            {"resourceType":"Observation","id":"ignored"}""";

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ValidateOperationIT"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.internal("Observation")));
        store = new R4Store(new PgObjectStore(ds, personality.registrations()),
                personality, "http://127.0.0.1/fhir");
        server = new FhirHttpServer(store, null, "127.0.0.1", 0, "/fhir");
    }

    @AfterAll
    void down() {
        server.close();
    }

    private static HttpResponse<String> validate(String body, String query) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                                server.baseUrl() + "/Observation/$validate" + query))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Timeout(300)
    @DisplayName("what $validate rejects, a write rejects — and it says where, not just that")
    void aRejectionAgreesWithTheWriteAndNamesTheLocation() throws Exception {
        HttpResponse<String> answer = validate(INVALID, "");

        assertEquals(200, answer.statusCode(),
                "asking a question correctly is not a bad request: " + answer.body());
        assertTrue(answer.body().contains("\"resourceType\":\"OperationOutcome\""), answer.body());
        assertTrue(answer.body().contains("\"severity\":\"error\""),
                "a resource the store would refuse came back clean: " + answer.body());
        assertTrue(answer.body().contains("status") || answer.body().contains("code"),
                "the outcome must say what to fix, not that something is wrong: "
                        + answer.body());

        // and the write agrees — the whole point of sharing one validation
        assertTrue(assertThrowsValidation(() -> store.create(INVALID)),
                "$validate rejected what the store accepts");
    }

    @Test
    @Timeout(300)
    @DisplayName("what $validate accepts, a write accepts, and nothing was written by asking")
    void anAcceptanceAgreesAndWritesNothing() throws Exception {
        HttpResponse<String> answer = validate(VALID, "");

        assertEquals(200, answer.statusCode(), answer.body());
        assertFalse(answer.body().contains("\"severity\":\"error\""),
                "a resource the store accepts was reported invalid: " + answer.body());
        assertFalse(answer.body().contains("\"severity\":\"fatal\""), answer.body());

        // asking is not writing: the store held nothing before the write below
        assertEquals("{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0}",
                countOnly(store.search("Observation", java.util.Map.of("_summary", "count"), null)),
                "asking whether a resource would be accepted stored it");

        store.create(VALID); // and the write really does accept it
    }

    @Test
    @Timeout(120)
    @DisplayName("an unsupported mode is refused rather than silently ignored")
    void anUnsupportedModeIsRefused() throws Exception {
        HttpResponse<String> answer = validate(VALID, "?mode=delete");

        assertEquals(400, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("mode"), answer.body());
    }

    @Test
    @Timeout(120)
    @DisplayName("a type this store does not serve is a 404, not an empty verdict")
    void anUnknownTypeIsNotSilentlyValid() throws Exception {
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(
                                server.baseUrl() + "/Medication/$validate"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"resourceType\":\"Medication\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, answer.statusCode(), answer.body());
    }

    /** Strips the entry array so the count line can be compared as a whole. */
    private static String countOnly(String searchsetJson) {
        return searchsetJson.replaceAll(",\"entry\":\\[.*\\]", "");
    }

    private static boolean assertThrowsValidation(Runnable write) {
        try {
            write.run();
            return false;
        } catch (cloud.jengu.dbo.fhir.common.ValidationFailedException expected) {
            return true;
        }
    }
}
