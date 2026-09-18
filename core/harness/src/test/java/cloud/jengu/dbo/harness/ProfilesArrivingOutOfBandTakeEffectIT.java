package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A profile takes effect however it arrived.
 *
 * <p>A profile written THROUGH the facade rebuilds that tenant's validation
 * view at the write, and always did. This is about every other way one can
 * arrive: replicated from a zone, restored from an archive, written by a lane
 * straight to the engine. Those bypass the facade entirely, and a facade cannot
 * notice what it did not do — so the tenant went on validating against the
 * shapes it held at bring-up while its store already had the new one.
 *
 * <p>The write below goes directly to the engine, which is what those paths do.
 * It is not a contrived shortcut: it is the same call
 * {@code ContentSyncEngine} makes when a zone's profile lands in a tenant that
 * inherits it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfilesArrivingOutOfBandTakeEffectIT {

    private static final String CANONICAL =
            "https://zone.example/StructureDefinition/observed-on-somebody";

    /** The zone's rule: an Observation here carries a subject. */
    private static final String PROFILE = """
            {"resourceType":"StructureDefinition","url":"%s",
             "name":"ObservedOnSomebody","status":"active","kind":"resource",
             "abstract":false,"type":"Observation",
             "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
             "derivation":"constraint",
             "differential":{"element":[
               {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
            .formatted(CANONICAL);

    private static final String WITHOUT_SUBJECT = """
            {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
             "meta":{"profile":["%s"]}}""".formatted(CANONICAL);

    static final HttpClient http = HttpClient.newHttpClient();
    static SharedTenants.Tenant tenant;
    static String base;
    static String token;

    @BeforeAll
    void up() throws Exception {
        // Shared. What this class is about is a profile arriving by a path no
        // facade served and taking effect anyway — it needs a tenant that
        // holds canonical StructureDefinitions, not one of its own. The
        // profiles it writes are claimed by meta.profile, so they bind only
        // the documents that claim them and no other class is held to them.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_IDENTIFIER);
        base = tenant.fhir();
        // A private tenant here was built without an authority, so its surface
        // answered anybody. A shared one is guarded like the real thing, which
        // is the more honest surface to be testing against anyway.
        token = tenant.token("out-of-band-profiles", "system/*.read", "system/*.write");
        // whatever the bring-up feed already holds is somebody else's news
        SharedTenants.manager().shapesRound();
    }

    @Test
    @Order(1)
    void aProfileWrittenPastTheFacadeIsInTheStore() {
        tenant.engine().put(PutRequest.create(
                "StructureDefinition", PROFILE.getBytes(StandardCharsets.UTF_8)));
        // The one written here, not the only one there: the face publishes its
        // own definitions into every tenant, so a count is a statement about
        // how many OTHER things exist and breaks when one is added.
        assertTrue(tenant.engine()
                        .select(cloud.jengu.dbo.core.api.Criteria.of("StructureDefinition"))
                        .stream()
                        .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                        .anyMatch(body -> body.contains(CANONICAL)),
                "the engine holds it — this test is about the facade not knowing");
    }

    /**
     * The defect, stated before it is fixed: the store has the profile and the
     * validator does not, so a document the tenant's own rule forbids is
     * accepted.
     */
    @Test
    @Order(2)
    void andUntilTheRuntimeNoticesTheTenantValidatesAgainstTheOldShapes() throws Exception {
        HttpResponse<String> stale = post("/Observation", WITHOUT_SUBJECT);
        assertTrue(stale.statusCode() >= 400,
                "an unknown profile is refused as unknown rather than silently satisfied — "
                        + "the shape is absent from the view either way, and a store that "
                        + "PASSED this would be claiming conformance to a rule it never "
                        + "read: " + stale.statusCode() + " " + stale.body());
    }

    @Test
    @Order(3)
    void afterTheRoundTheTenantsOwnRuleApplies() throws Exception {
        assertEquals(1, SharedTenants.manager().shapesRound(),
                "exactly one tenant had a profile arrive without its facade knowing");

        HttpResponse<String> refused = post("/Observation", WITHOUT_SUBJECT);
        assertEquals(422, refused.statusCode(),
                "the zone's rule now applies to this tenant: " + refused.body());
        assertTrue(refused.body().contains("subject"),
                "and it names the element the profile requires: " + refused.body());

        HttpResponse<String> accepted = post("/Observation", """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "subject":{"reference":"Patient/7c2f5d3e-0000-4000-8000-000000000001"},
                 "meta":{"profile":["%s"]}}""".formatted(CANONICAL));
        assertEquals(201, accepted.statusCode(),
                "and a document that satisfies it is written: " + accepted.body());
    }

    /** A round with nothing new rebuilds nothing — the trigger is the event. */
    @Test
    @Order(4)
    void aRoundWithNothingNewDoesNothing() {
        assertEquals(0, SharedTenants.manager().shapesRound(),
                "the feed is the trigger, so a quiet round costs a read and no rebuild");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
