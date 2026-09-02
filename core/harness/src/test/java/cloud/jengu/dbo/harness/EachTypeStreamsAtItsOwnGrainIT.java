package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependency is not a terminology feature: any declared type streams, and
 * each one travels at the grain its own kind needs.
 *
 * <p>The first clause had only incidental coverage — a clinical type happened
 * to ride along in another scenario — and the second had none, because the
 * test that came closest asserts the opposite shape: that an <i>undeclared</i>
 * type does not stream. Both are worth holding, because "any type" is what
 * separates a general dependency mechanism from a terminology pipe with a
 * general-sounding name, and this store's argument for being a neutral engine
 * rests on the difference.
 *
 * <p>Grain is the interesting half. A CodeSystem is stored as a shell with its
 * concepts in a native form, so a dependant that received the stored bytes
 * would hold a vocabulary it could not answer a single question about; it has
 * to be reassembled in flight and taken apart again on arrival. A clinical
 * resource has no such split and must NOT be put through that — it travels as
 * itself. One dependency declaring both is what shows the engine choosing per
 * type rather than per stream.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EachTypeStreamsAtItsOwnGrainIT {

    private static final String UPSTREAM = "grain-ee";
    private static final String DEPENDANT = "grain-haigla";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String encounterId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-grain-tenants");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("EachTypeStreamsAtItsOwnGrainIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    @Test
    @Order(1)
    @DisplayName("an upstream publishes a vocabulary and a clinical record side by side")
    void theUpstreamHoldsBothKinds() throws Exception {
        Files.writeString(dir.resolve(UPSTREAM + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"Encounter","identity":"internal","handling":"operational"}]}"""
                .formatted(UPSTREAM));
        UntilServed.scan(manager, UPSTREAM);

        assertEquals(201, post(manager.baseUrl(UPSTREAM) + "/CodeSystem", """
                {"resourceType":"CodeSystem","url":"https://ee.ee/cs/teenused",
                 "status":"active","content":"complete",
                 "concept":[{"code":"vastuvott","display":"Vastuvott"}]}""").statusCode());

        HttpResponse<String> encounter = post(manager.baseUrl(UPSTREAM) + "/Encounter", """
                {"resourceType":"Encounter","status":"finished",
                 "class":{"code":"AMB"}}""");
        assertEquals(201, encounter.statusCode(), encounter.body());
        String location = encounter.headers().firstValue("Location").orElseThrow();
        encounterId = location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    @Order(2)
    @DisplayName("one dependency declaring both delivers each at its own grain")
    @Proving(DboPromises.SYNC_ANY_TYPE)
    void bothArriveAndOnlyOneIsReassembled() throws Exception {
        Files.writeString(dir.resolve(DEPENDANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Encounter","identity":"internal","handling":"replicated"}],
                 "dependencies":[{"name":"%s","types":["CodeSystem","Encounter"]}]}"""
                .formatted(DEPENDANT, UPSTREAM));
        UntilServed.scan(manager, DEPENDANT);

        // The vocabulary's grain is the whole thing: arriving is not enough,
        // the dependant has to be able to ANSWER from it, which it can only do
        // if the concepts were carried and taken apart on arrival.
        String lookup = await("the vocabulary must be answerable, not merely present", () -> {
            HttpResponse<String> answer = get(manager.baseUrl(DEPENDANT)
                    + "/CodeSystem/$lookup?system=https://ee.ee/cs/teenused&code=vastuvott");
            return answer.statusCode() == 200 ? answer.body() : null;
        });
        assertTrue(lookup.contains("Vastuvott"), lookup);

        // The clinical record's grain is itself. It has no native form to
        // reassemble, and putting it through one would be the defect.
        String copied = await("a non-terminology type must stream too, or a dependency "
                + "is a terminology feature wearing a general name", () -> {
                    HttpResponse<String> copy =
                            get(manager.baseUrl(DEPENDANT) + "/Encounter/" + encounterId);
                    return copy.statusCode() == 200 ? copy.body() : null;
                });
        assertTrue(copied.contains("\"status\":\"finished\"") && copied.contains("AMB"),
                "the record arrived changed: " + copied);
        assertTrue(copied.contains(encounterId),
                "a copy under a different id is not the upstream's record: " + copied);
    }

    private interface Probe {
        String get() throws Exception;
    }

    private static String await(String what, Probe probe) throws Exception {
        // The suite's one number for a wait on the feed (see Eventually).
        long deadline = System.currentTimeMillis() + Eventually.PATIENCE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            manager.syncRound();
            String answer = probe.get();
            if (answer != null) {
                return answer;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(what);
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
