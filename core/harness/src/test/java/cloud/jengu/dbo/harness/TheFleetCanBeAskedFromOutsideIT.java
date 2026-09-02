package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.Trackables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A routed tree could be reported and normalised, and nothing outside the
 * container could ask what it said.
 *
 * <p>The write path was complete: an appliance reports the instruments behind
 * it over the lane, and the store normalises them into one shape at every
 * level. The read path did not exist. {@code Trackables} answers by id, by
 * what is behind a router, by who observed a thing, and by whole subtree — and
 * every one of those was reachable only from inside the framework holding the
 * store. The only consumer that wanted the answer had to keep its own copy of
 * what it had forwarded, which is a second normalisation of state the store
 * already normalises: the thing the model was accepted to avoid.
 *
 * <p>So this drives the real door over real HTTP with a real token. Nothing
 * here reaches into the JVM to answer a question the point of which is that it
 * can be asked from outside.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheFleetCanBeAskedFromOutsideIT {

    private static final String TENANT = "laevastik";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI door;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-laevastik");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheFleetCanBeAskedFromOutsideIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        door = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/fleet");

        // A connector that reports for itself, a bench behind it, and an
        // instrument behind the bench — three levels, because depth is the
        // whole point of the model and a flat answer would prove nothing.
        Trackables reported = new Trackables(
                manager.runtime(TENANT).orElseThrow().engine());
        reported.reports(Trackable.reporting("connector-1", "connector",
                Map.of("version", "2.1.0")));
        reported.routes("connector-1", List.of(
                Trackable.routed("bench-7", "appliance", "connector-1",
                        Map.of("status", "serving"))));
        reported.routes("bench-7", List.of(
                Trackable.routed("analyser-3", "instrument", "bench-7",
                        Map.of("status", "idle"))));
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
    @DisplayName("a caller outside the container asks what is under a connector and gets "
            + "the whole depth of it")
    @Proving(DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB)
    void theSubtreeIsAnswerableFromOutside() throws Exception {
        HttpResponse<String> answer = ask("subtree", "{\"router\":\"connector-1\"}");

        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("bench-7"), answer.body());
        assertTrue(answer.body().contains("analyser-3"),
                "the instrument two levels down is missing, so a caller has to walk the "
                        + "tree itself — which is the rebuilding this model exists to stop: "
                        + answer.body());
    }

    @Test
    @DisplayName("and the narrower questions answer too, so a caller need not take the "
            + "whole tree to learn one thing")
    @Proving(DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB)
    void theNarrowerQuestionsAnswer() throws Exception {
        assertTrue(ask("behind", "{\"router\":\"connector-1\"}").body().contains("bench-7"));
        assertFalse(ask("behind", "{\"router\":\"connector-1\"}").body().contains("analyser-3"),
                "'behind' returned the whole subtree, so the two questions are one question "
                        + "and the shallow one cannot be asked");

        HttpResponse<String> one = ask("trackable", "{\"id\":\"analyser-3\"}");
        assertEquals(200, one.statusCode(), one.body());
        assertTrue(one.body().contains("instrument"), one.body());

        // Something nobody ever reported is an ordinary answer, not a fault:
        // asking whether this tenant knows about a thing is usually asking
        // exactly that.
        HttpResponse<String> unknown = ask("trackable", "{\"id\":\"ei-ole\"}");
        assertEquals(200, unknown.statusCode(),
                "a thing this tenant was never told about was reported as an error: "
                        + unknown.body());
        assertFalse(unknown.body().contains("instrument"), unknown.body());
    }

    @Test
    @DisplayName("nothing is filtered by how long ago it was seen — attestation is reported "
            + "and judged by the caller")
    @Proving(DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB)
    void nothingIsHiddenForBeingStale() throws Exception {
        String body = ask("subtree", "{\"router\":\"connector-1\"}").body();

        assertTrue(body.contains("observedBy") || body.contains("attested"),
                "the answer carries no attestation, so a caller cannot judge freshness at "
                        + "all — which is the judgement the store refuses to make FOR them, "
                        + "not one it refuses to inform: " + body);
        assertTrue(body.contains("bench-7"),
                "something was dropped from the answer, and the only reason a read here "
                        + "would drop a row is a freshness rule this store does not have");
    }

    @Test
    @DisplayName("a participation credential cannot read the fleet, and none is refused "
            + "before anything is looked up")
    @Proving(DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB)
    void theDoorHasItsOwnScope() throws Exception {
        HttpResponse<String> asABench = http.send(HttpRequest.newBuilder(
                        URI.create(door + "/subtree"))
                .header("Authorization", "Bearer " + token("bench", "work"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"router\":\"connector-1\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, asABench.statusCode(),
                "a participation credential read the fleet, so a bench can see the benches "
                        + "beside it: " + asABench.body());
        assertTrue(asABench.body().contains("fleet"), asABench.body());

        HttpResponse<String> none = http.send(HttpRequest.newBuilder(
                        URI.create(door + "/subtree"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"router\":\"connector-1\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, none.statusCode(), none.body());
    }

    private static HttpResponse<String> ask(String verb, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(door + "/" + verb))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token("operaator", "fleet"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String clientId, String... scopes) throws Exception {
        String secret = clientId + "-secret";
        manager.authority(TENANT).ensureClient(clientId, secret, List.of(scopes));
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + TENANT + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
