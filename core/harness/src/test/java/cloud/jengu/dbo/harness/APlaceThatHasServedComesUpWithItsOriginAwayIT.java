package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.runner.transport.Place;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site whose place holds only what was written there — by runs, by its own
 * people — and nothing its origin's streams applied, switched off and on again
 * while its origin cannot be reached.
 *
 * <p><b>A world of its own, and bring-up is why.</b> What is asserted is whether a
 * node brings a place up, read off nodes as they are started.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class APlaceThatHasServedComesUpWithItsOriginAwayIT {

    private static final String ROOT = "laane-r4";
    private static final String CLINIC = "saare";
    /**
     * Takes its face from a root and no records from anywhere: on a site
     * with a root of its own beside it, nothing the place holds was put there
     * by its origin's streams.
     */
    private static final String DECLARATION = """
            {"code":"%s","face":"r4","audit":{"level":"none"},
             "dependencies":[{"name":"%s","face":true,
                              "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
             "types":[
              {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
              {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
              {"name":"ValueSet","identity":"canonical","handling":"replicated"},
              {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
              {"name":"Patient","identity":"internal","handling":"operational"}]}"""
            .formatted(CLINIC, ROOT);

    /** The face root, declared alike on every node. */
    private static final String ROOT_DECLARATION = """
            {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
             "types":[
              {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
              {"name":"SearchParameter","identity":"canonical","handling":"operational"},
              {"name":"ValueSet","identity":"canonical","handling":"operational"},
              {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
            .formatted(ROOT);

    static final HttpClient http = HttpClient.newHttpClient();
    private final List<TenantRuntimeManager> nodes = new java.util.ArrayList<>();
    private final java.util.Map<Path, byte[]> keys = new java.util.HashMap<>();

    @AfterAll
    void down() {
        nodes.forEach(TenantRuntimeManager::close);
    }

    @Test
    @DisplayName("a place that has served comes up with its origin away, serves what was written "
            + "there, and is not torn down on the scans after; one that never served still waits")
    @Proving(DboPromises.SYNC_A_PLACE_SERVES_WHAT_IT_HOLDS_WHILE_ITS_ORIGIN_IS_AWAY)
    void aPlaceThatHasServedComesUpWithItsOriginAway() throws Exception {
        String admin = SharedPostgres.urlFor("APlaceThatHasServedComesUpWithItsOriginAwayIT");
        String user = SharedPostgres.get().getUsername();
        String password = SharedPostgres.get().getPassword();
        Path cloudDir = Files.createTempDirectory("dbo-place-away-cloud");
        Path siteDir = Files.createTempDirectory("dbo-place-away-site");
        // One server for all three nodes, each under a namespace of its own.
        TenantRuntimeManager cloud = node(cloudDir,
                new LocalDatabasePerTenantProvisioner(admin, user, password, "cloud"));
        Files.writeString(cloudDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        UntilServed.scan(cloud, ROOT);
        Files.writeString(cloudDir.resolve(CLINIC + ".json"), DECLARATION);
        UntilServed.scan(cloud, CLINIC);

        cloud.authority(CLINIC).ensureClient("site", "site-secret", List.of("place"));
        HttpLane lane = HttpLane.to(URI.create(base(cloud) + "/work"),
                () -> clientToken(cloud, "site", "site-secret"), CLINIC, "site",
                new Executor("site", "1", "example.site", Scope.BASELINE));

        LocalDatabasePerTenantProvisioner siteDatabases =
                new LocalDatabasePerTenantProvisioner(admin, user, password, "site");
        TenantRuntimeManager site = node(siteDir, siteDatabases);
        site.servesAPlaceOf(CLINIC);
        site.readsItsOriginThrough(CLINIC, new TenantRuntimeManager.OriginFeeds(
                lane.placeFeed(Place.RECORDS), lane.placeFeed(Place.DEFINITIONS),
                lane.definitionsWithoutTheFace()));
        Files.writeString(siteDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        UntilServed.scan(site, ROOT);
        Files.writeString(siteDir.resolve(CLINIC + ".json"), DECLARATION);
        UntilServed.scan(site, CLINIC);
        // What the place holds is what was written there, as a run writes it.
        assertEquals(201, post(site, "{\"resourceType\":\"Patient\",\"gender\":\"other\"}"));

        // Switched off, and on again with no way to its origin.
        site.close();
        nodes.remove(site);
        TenantRuntimeManager restarted = node(siteDir, siteDatabases);
        restarted.servesAPlaceOf(CLINIC);

        UntilServed.scan(restarted, ROOT);
        assertTrue(servingWithin(restarted, 5),
                "a place that had served did not come up with its origin away: "
                        + restarted.troubles());
        assertTrue(read(restarted).contains("\"gender\":\"other\""),
                "the place came up without what was written there");
        for (int scan = 0; scan < 3; scan++) {
            assertTrue(restarted.scanOnce().contains(CLINIC),
                    "the place was torn down on a later scan: " + restarted.troubles());
        }
        assertTrue(read(restarted).contains("\"gender\":\"other\""),
                "the place's doors closed after it came up");

        // A place that has never served has nothing to serve, and waits.
        Path freshDir = Files.createTempDirectory("dbo-place-away-fresh");
        TenantRuntimeManager fresh = node(freshDir,
                new LocalDatabasePerTenantProvisioner(admin, user, password, "fresh"));
        fresh.servesAPlaceOf(CLINIC);
        Files.writeString(freshDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        UntilServed.scan(fresh, ROOT);
        Files.writeString(freshDir.resolve(CLINIC + ".json"), DECLARATION);
        assertFalse(servingWithin(fresh, 3),
                "a place that never served came up with nothing from its origin");
    }

    private static boolean servingWithin(TenantRuntimeManager node, int scans) {
        for (int scan = 0; scan < scans; scan++) {
            Set<String> up = node.scanOnce();
            if (up.contains(CLINIC)) {
                return true;
            }
        }
        return false;
    }

    private TenantRuntimeManager node(Path dir, LocalDatabasePerTenantProvisioner databases) {
        byte[] kek = keys.computeIfAbsent(dir, ignored -> {
            byte[] fresh = new byte[32];
            new java.security.SecureRandom().nextBytes(fresh);
            return fresh;
        });
        TenantRuntimeManager node = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0,
                null, new TenantRuntimeManager.AuthorityConfig(kek, null));
        nodes.add(node);
        return node;
    }

    private static int post(TenantRuntimeManager node, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(node) + "/fhir/Patient"))
                        .header("Authorization", "Bearer " + writer(node))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String read(TenantRuntimeManager node) throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                        URI.create(base(node) + "/fhir/Patient"))
                        .header("Authorization", "Bearer " + writer(node)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    /** A token from the node's own authority: what answers only once the place is up. */
    private static String writer(TenantRuntimeManager node) {
        cloud.jengu.dbo.auth.TenantAuthority authority = node.authority(CLINIC);
        if (authority == null) {
            throw new AssertionError("the place's authority answers nothing");
        }
        authority.ensureClient("writer", "writer-secret",
                List.of("system/*.read", "system/*.write"));
        return clientToken(node, "writer", "writer-secret");
    }

    private static String clientToken(TenantRuntimeManager node, String client, String secret) {
        try {
            return Extracted.tokenIn(http.send(HttpRequest.newBuilder(
                            URI.create(base(node) + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "grant_type=client_credentials&client_id=" + client
                                            + "&client_secret=" + secret)).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static String base(TenantRuntimeManager node) {
        return "http://127.0.0.1:" + node.port() + "/t/" + CLINIC;
    }
}
