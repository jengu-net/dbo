package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A routed tree reaches the store as a lane verb.
 *
 * <p>Normalising the state and registering the type per tenant left
 * exactly one thing missing: nothing outside the container could carry a tree
 * to it. That is the shape this repository now names as a trap — built,
 * proven, unreachable — so the proof here is deliberately over real HTTP with
 * a real token, against a tenant the manager provisioned. A harness holding a
 * {@code Trackables} it constructed itself would prove nothing that was in
 * doubt.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARoutedTreeArrivesOverTheLaneIT {

    private static final String TENANT = "routing";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static Trackables inTheTenantsOwnStore;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-routing");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ARoutedTreeArrivesOverTheLaneIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","fhirVersion":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        // Read back through the tenant's OWN engine, which is the half of the
        // claim that matters: the rows have to be where the tenant keeps its
        // records, not where the test put them.
        inTheTenantsOwnStore = new Trackables(manager.runtime(TENANT).orElseThrow().engine());
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
    @DisplayName("a connector reports its tree over the lane, and every trackable in it "
            + "lands in the tenant's own store at whatever depth it sits")
    @Proving({DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB,
            DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS})
    void theTreeArrivesFromOutsideTheContainer() throws Exception {
        Lane lane = laneFor("connector-a");

        lane.routes(List.of(
                Trackable.routed("bench-a", "appliance", "connector-a", Map.of("power", "on")),
                Trackable.routed("analyser-a", "instrument", "bench-a",
                        Map.of("state", "running", "reagent", "low"))));

        Trackable deep = inTheTenantsOwnStore.byId("analyser-a").orElseThrow(
                () -> new AssertionError("the instrument two hops down never arrived"));
        assertEquals("bench-a", deep.routedBy(), "the tree survived the wire, one edge per row");
        assertEquals("low", deep.state().get("reagent"), "and so did its state");
        assertEquals(2, inTheTenantsOwnStore.subtree("connector-a").size(),
                "the whole subtree is there, reported by a host that is not the container");
    }

    @Test
    @DisplayName("the observer is the lane's own participant, so a router cannot attest "
            + "as somebody else however it fills the field")
    @Proving(DboPromises.PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB)
    void theObserverIsStampedNotSent() throws Exception {
        Lane lane = laneFor("connector-b");

        // Forged on purpose: the wire carries an attestation naming a
        // different worker, an hour ago. If any of it survived, an operator
        // chasing a silent instrument would be sent to the wrong hop by the
        // very party that should be accountable for it.
        lane.routes(List.of(new Trackable("probe-b", "instrument", "connector-b",
                Map.of("state", "idle"),
                new Trackable.Attested("connector-a", Instant.now().minusSeconds(3600)))));

        Trackable probe = inTheTenantsOwnStore.byId("probe-b").orElseThrow();
        assertEquals("connector-b", probe.attested().observedBy(),
                "stamped from the lane's participant, not taken from the body");
        assertNotEquals("connector-a", probe.attested().observedBy(),
                "the name on the wire is discarded rather than believed");
        assertTrue(probe.attested().at().isAfter(Instant.now().minusSeconds(120)),
                "and so is the time, which is the store's rather than the reporter's");
    }

    private static Lane laneFor(String participant) throws Exception {
        String secret = participant + "-secret";
        manager.authority(TENANT).ensureClient(participant, secret, List.of("work/dbo.lab.any"));
        String token = token(participant, secret);
        return HttpLane.to(laneUri, () -> token, TENANT, participant,
                new Executor(participant, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    private static String token(String clientId, String secret) throws Exception {
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
