package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
 *
 * <p>On the shared runtime, as two shapes: an upstream and the dependant that
 * declares it. Running a sync round here is what the scan loop does anyway,
 * and every assertion is scoped to the vocabulary and the encounter this
 * class wrote — the round's own count is never the subject.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EachTypeStreamsAtItsOwnGrainIT {

    static SharedTenants.Tenant upstream;
    static SharedTenants.Tenant dependant;
    static final HttpClient http = HttpClient.newHttpClient();
    /** A token is its tenant's, so each of the two has one. */
    static String onUpstream;

    static String onDependant;
    static String encounterId;

    /** A canonical of this class's own, on a tenant other classes may share. */
    private static final String VOCABULARY = "https://shared.test/cs/grain-teenused";

    @BeforeAll
    void up() {
        // In this order: a dependency names the tenant it is on, so the
        // upstream has to be serving before the dependant that declares it.
        upstream = SharedTenants.of(SharedTenants.Shape.R4_GRAIN_UPSTREAM);
        dependant = SharedTenants.of(SharedTenants.Shape.R4_GRAIN_DEPENDANT);
        onUpstream = upstream.token("grain-writer", "system/*.write", "system/*.read");
        onDependant = dependant.token("grain-reader", "system/*.read");
    }

    /**
     * Given back. A tenant one class uses is a database the whole
     * suite carries until the run ends, and the saving on this rung is
     * the runtime rather than the tenant.
     */
    @AfterAll
    void down() {
        SharedTenants.retire(dependant);
        SharedTenants.retire(upstream);
    }

    @Test
    @Order(1)
    @DisplayName("an upstream publishes a vocabulary and a clinical record side by side")
    void theUpstreamHoldsBothKinds() throws Exception {
        assertEquals(201, post(upstream.fhir() + "/CodeSystem", """
                {"resourceType":"CodeSystem","url":"%s",
                 "status":"active","content":"complete",
                 "concept":[{"code":"vastuvott","display":"Vastuvott"}]}"""
                .formatted(VOCABULARY)).statusCode());

        HttpResponse<String> encounter = post(upstream.fhir() + "/Encounter", """
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
        // The vocabulary's grain is the whole thing: arriving is not enough,
        // the dependant has to be able to ANSWER from it, which it can only do
        // if the concepts were carried and taken apart on arrival.
        String lookup = await("the vocabulary must be answerable, not merely present", () -> {
            HttpResponse<String> answer = get(dependant.fhir()
                    + "/CodeSystem/$lookup?system=" + VOCABULARY + "&code=vastuvott");
            return answer.statusCode() == 200 ? answer.body() : null;
        });
        assertTrue(lookup.contains("Vastuvott"), lookup);

        // The clinical record's grain is itself. It has no native form to
        // reassemble, and putting it through one would be the defect.
        String copied = await("a non-terminology type must stream too, or a dependency "
                + "is a terminology feature wearing a general name", () -> {
                    HttpResponse<String> copy =
                            get(dependant.fhir() + "/Encounter/" + encounterId);
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
            dependant.syncOnce();
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
                        .header("Authorization", "Bearer " + onUpstream)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Every read here is of the dependant, which holds its own credential. */
    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + onDependant).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
