package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-replicated-shape");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AReplicatedProfileCanBeValidatedAgainstIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);

        // The zone publishes the profile, exactly as a platform zone does.
        Files.writeString(dir.resolve("shape-zone.json"), """
                {"code":"shape-zone","face":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "shape-zone");
        assertEquals(201, post("shape-zone", "/StructureDefinition", PROFILE).statusCode());

        // And a tenant that takes its shapes from the zone rather than
        // authoring them: StructureDefinition is somebody else's publication
        // here, which is what "replicated" says.
        Files.writeString(dir.resolve("shape-reader.json"), """
                {"code":"shape-reader","face":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"Observation","identity":"internal","handling":"operational"}],
                 "dependencies":[{"name":"shape-zone","types":["StructureDefinition"]}]}""");
        UntilServed.scan(manager, "shape-zone", "shape-reader");
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    @Proving(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED)
    void aProfileThatArrivedByReplicationCanBeValidatedAgainst() throws Exception {
        // The row arrives — this is the half that already works, and the half
        // a consumer sees when it polls for its declared shapes.
        long deadline = System.currentTimeMillis() + Eventually.PATIENCE.toMillis();
        boolean here = false;
        while (!here && System.currentTimeMillis() < deadline) {
            manager.syncRound();
            here = get("shape-reader", "/StructureDefinition?url="
                    + URLEncoder.encode(CANONICAL, StandardCharsets.UTF_8)
                    + "&_summary=count").body().contains("\"total\":1");
            if (!here) {
                Thread.sleep(200);
            }
        }
        assertTrue(here, "the profile never replicated at all, so this proves nothing yet");

        // Everything the runtime does about arriving shapes, run to quiescence.
        manager.shapesRound();
        manager.shapesRound();

        HttpResponse<String> claimed = post("shape-reader", "/Observation", CLAIMING);
        assertEquals(201, claimed.statusCode(),
                "the tenant holds the profile and refuses what claims it: " + claimed.body());
    }

    private static HttpResponse<String> post(String tenant, String path, String body)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(tenant) + path))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String tenant, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(tenant) + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
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
        manager.runtime("shape-reader").orElseThrow().engine().put(
                new cloud.jengu.dbo.core.api.PutRequest("StructureDefinition", null, null,
                        BEHIND_THE_FACADE.getBytes(StandardCharsets.UTF_8)),
                cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);

        HttpResponse<String> claimed = post("shape-reader", "/Observation", CLAIMING_BEHIND);

        assertEquals(201, claimed.statusCode(),
                "the tenant holds this profile and refused what claims it: " + claimed.body());
    }
}
