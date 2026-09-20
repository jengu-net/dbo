package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant that receives a profile by replication holds the row and refuses
 * every resource claiming it: the store's validation view never learns about a
 * shape that arrived by a path its facade did not serve.
 *
 * <p>Written to reproduce a report that a tenant refuses what it holds, and it
 * <b>does not reproduce it</b>: with the shape the report describes — a
 * dependent declaring StructureDefinition replicated from a zone that already
 * held the profile — the copy arrives, the view rebuilds, and the claim is
 * accepted. It is kept because the guarantee is worth holding whatever the
 * mechanism, and because the next person to look at that report should start
 * from something that runs rather than from a fixture that has to be built
 * first. What it says about the report is only that the cause is a condition
 * this does not have.
 *
 * <p>It asserts the guarantee a consumer needs — the profile is here,
 * therefore a resource claiming it is accepted — rather than any particular
 * mechanism reaching it, so a fix that moves the signal from the feed to the
 * sync engine keeps it passing.
 *
 * <p>On the shared runtime, as two shapes: a zone that publishes and a reader
 * that declares it. The rounds it drives are what the scan loop drives
 * anyway, and nothing here asserts what a round returned — only what this
 * class's own tenant does with the two profiles this class wrote.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AReplicatedProfileCanBeValidatedAgainstIT {

    private static final String CANONICAL =
            "https://replicated.example/StructureDefinition/observed-on-somebody";

    private static final String PROFILE = """
            {"resourceType":"StructureDefinition",
             "url":"%s","version":"1.0.0",
             "name":"ObservedOnSomebody","status":"active","kind":"resource",
             "abstract":false,"type":"Observation",
             "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
             "derivation":"constraint",
             "differential":{"element":[
               {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
            .formatted(CANONICAL);

    private static final String CLAIMING = """
            {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
             "subject":{"display":"somebody"},
             "meta":{"profile":["%s"]}}""".formatted(CANONICAL);

    private static final String BEHIND = "https://replicated.example/StructureDefinition/arrived-quietly";

    private static final String BEHIND_THE_FACADE = """
            {"resourceType":"StructureDefinition",
             "url":"%s","version":"1.0.0",
             "name":"ArrivedQuietly","status":"active","kind":"resource",
             "abstract":false,"type":"Observation",
             "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
             "derivation":"constraint",
             "differential":{"element":[
               {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
            .formatted(BEHIND);

    private static final String CLAIMING_BEHIND = """
            {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
             "subject":{"display":"somebody"},
             "meta":{"profile":["%s"]}}""".formatted(BEHIND);

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static SharedTenants.Tenant zone;
    static SharedTenants.Tenant reader;
    static String onZone;
    static String onReader;

    @BeforeAll
    void up() throws Exception {
        // In this order: the reader declares a dependency on the zone, so the
        // zone has to be serving before it.
        zone = SharedTenants.of(SharedTenants.Shape.R4_SHAPE_ZONE);
        reader = SharedTenants.of(SharedTenants.Shape.R4_SHAPE_READER);
        onZone = zone.token("shape-zone-writer", "system/*.write", "system/*.read");
        onReader = reader.token("shape-reader-writer", "system/*.write", "system/*.read");

        // The zone publishes the profile, exactly as a platform zone does.
        assertEquals(201, post(zone, onZone, "/StructureDefinition", PROFILE).statusCode());
    }

    @Test
    @Proving(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED)
    void aProfileThatArrivedByReplicationCanBeValidatedAgainst() throws Exception {
        // The row arrives — this is the half that already works, and the half
        // a consumer sees when it polls for its declared shapes.
        long deadline = System.currentTimeMillis() + Eventually.PATIENCE.toMillis();
        boolean here = false;
        while (!here && System.currentTimeMillis() < deadline) {
            reader.syncOnce();
            here = get(reader, "/StructureDefinition?url="
                    + URLEncoder.encode(CANONICAL, StandardCharsets.UTF_8)
                    + "&_summary=count").body().contains("\"total\":1");
            if (!here) {
                Thread.sleep(200);
            }
        }
        assertTrue(here, "the profile never replicated at all, so this proves nothing yet");

        // Everything the runtime does about arriving shapes, run to quiescence.
        SharedTenants.manager().shapesRound();
        SharedTenants.manager().shapesRound();

        HttpResponse<String> claimed = post(reader, onReader, "/Observation", CLAIMING);
        assertEquals(201, claimed.statusCode(),
                "the tenant holds the profile and refuses what claims it: " + claimed.body());
    }

    private static HttpResponse<String> post(SharedTenants.Tenant tenant, String bearer,
            String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(tenant.fhir() + path))
                        .header("Content-Type", "application/fhir+json")
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(SharedTenants.Tenant tenant, String path)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(tenant.fhir() + path))
                .header("Authorization", "Bearer " + onReader).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The symptom itself, without depending on how a shape got there: a
     * profile written straight into the tenant's store, so the validation
     * view was built without it and no watch ever saw it arrive. A tenant
     * that holds a profile and refuses what claims it is telling its author
     * something untrue about its own contents.
     */
    @Test
    @Proving(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED)
    void aProfileTheTenantHoldsIsValidatedAgainstHoweverItArrived() throws Exception {
        // Behind the facade, so nothing rebuilds and nothing is notified —
        // which is the situation every path that is not the facade produces.
        reader.engine().put(
                new cloud.jengu.dbo.core.api.PutRequest("StructureDefinition", null, null,
                        BEHIND_THE_FACADE.getBytes(StandardCharsets.UTF_8)),
                cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);

        HttpResponse<String> claimed = post(reader, onReader, "/Observation",
                CLAIMING_BEHIND);

        assertEquals(201, claimed.statusCode(),
                "the tenant holds this profile and refused what claims it: " + claimed.body());
    }
}
