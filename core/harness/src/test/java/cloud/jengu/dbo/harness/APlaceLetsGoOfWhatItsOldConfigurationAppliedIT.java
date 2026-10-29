package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.runner.transport.Place;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site that served a clinic as its own, applying its owner's configuration,
 * and then comes up as a place of the clinic in the cloud — as a site that
 * applied its owner's configuration before a place left that to its origin
 * does once it runs a release that does.
 *
 * <p><b>A world of its own, and bring-up is why.</b> What is asserted is what a
 * node does with what it holds when it first brings a tenant up as a place,
 * read off a node restarted as one.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class APlaceLetsGoOfWhatItsOldConfigurationAppliedIT {

    private static final String ROOT = "kaasiku-r4";
    private static final String CLINIC = "hiiu";

    /** What the owner's configuration declares, at the cloud and once at the site. */
    private static final String CATALOGUE = "urn:dbo-test:map:catalogue";
    /** What the owner declares, and somebody on the site wrote a map of their own for. */
    private static final String OVERRIDDEN = "urn:dbo-test:map:overridden";
    /** Another the site wrote its own of, and keeps when the first is let go. */
    private static final String KEPT = "urn:dbo-test:map:kept";

    /** The clinic, its owner's configuration named where the node applying it keeps it. */
    private static final String DECLARATION = """
            {"code":"%s","face":"r4","audit":{"level":"writes"},
             "configuration":{"git":"%%s","ref":"main"},
             "dependencies":[{"name":"%s","face":true,
                              "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem",
                                       "StructureMap"]}],
             "types":[
              {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
              {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
              {"name":"ValueSet","identity":"canonical","handling":"replicated"},
              {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
              {"name":"StructureMap","identity":"canonical","handling":"operational"},
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
    private final List<TenantRuntimeManager> nodes = new ArrayList<>();
    private final Map<Path, byte[]> keys = new java.util.HashMap<>();
    /** The site, up again as a place of the cloud's clinic. */
    private TenantRuntimeManager site;

    @AfterAll
    void down() {
        nodes.forEach(TenantRuntimeManager::close);
    }

    @Test
    @Order(1)
    @DisplayName("a place releases what its own configuration apply wrote there, so the "
            + "origin's versions apply; what somebody on the site wrote still shadows")
    @Proving(DboPromises.SYNC_A_PLACE_LETS_GO_OF_WHAT_ITS_OLD_CONFIGURATION_APPLIED)
    void aPlaceLetsGoOfWhatItsOldConfigurationApplied() throws Exception {
        String admin = SharedPostgres.urlFor("APlaceLetsGoOfWhatItsOldConfigurationAppliedIT");
        String user = SharedPostgres.get().getUsername();
        String password = SharedPostgres.get().getPassword();
        Path cloudDir = Files.createTempDirectory("dbo-place-config-cloud");
        Path siteDir = Files.createTempDirectory("dbo-place-config-site");

        Path cloudConfiguration = repository(cloudDir.resolve("configuration"), Map.of(
                "maps/catalogue.json", map(CATALOGUE, "AsTheOriginPublishesIt"),
                "maps/overridden.json", map(OVERRIDDEN, "AsTheOriginPublishesIt"),
                "maps/kept.json", map(KEPT, "AsTheOriginPublishesIt")));
        TenantRuntimeManager cloud = node(cloudDir,
                new LocalDatabasePerTenantProvisioner(admin, user, password, "cloud"));
        Files.writeString(cloudDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        UntilServed.scan(cloud, ROOT);
        Files.writeString(cloudDir.resolve(CLINIC + ".json"),
                DECLARATION.formatted(cloudConfiguration));
        UntilServed.scan(cloud, CLINIC);
        assertTrue(mapNamed(cloud, CATALOGUE).contains("AsTheOriginPublishesIt"),
                "the cloud's clinic did not apply its owner's configuration: "
                        + cloud.troubles().get(CLINIC));

        // The site serving the clinic as its own, applying the configuration
        // itself; and somebody there writing a map of their own.
        Path siteConfiguration = repository(siteDir.resolve("configuration"), Map.of(
                "maps/catalogue.json", map(CATALOGUE, "AsTheSiteAppliedIt")));
        LocalDatabasePerTenantProvisioner siteDatabases =
                new LocalDatabasePerTenantProvisioner(admin, user, password, "site");
        TenantRuntimeManager before = node(siteDir, siteDatabases);
        Files.writeString(siteDir.resolve(ROOT + ".json"), ROOT_DECLARATION);
        UntilServed.scan(before, ROOT);
        Files.writeString(siteDir.resolve(CLINIC + ".json"),
                DECLARATION.formatted(siteConfiguration));
        UntilServed.scan(before, CLINIC);
        assertTrue(mapNamed(before, CATALOGUE).contains("AsTheSiteAppliedIt"),
                "the site's clinic did not apply its owner's configuration: "
                        + before.troubles().get(CLINIC));
        assertEquals(201, post(before, map(OVERRIDDEN, "AsSomebodyOnTheSiteWroteIt")));
        assertEquals(201, post(before, map(KEPT, "AsSomebodyOnTheSiteWroteIt")));
        before.close();
        nodes.remove(before);

        // Up again as a place of the cloud's clinic, declared as it is there.
        cloud.authority(CLINIC).ensureClient("site", "site-secret", List.of("place"));
        HttpLane lane = HttpLane.to(URI.create(base(cloud) + "/work"),
                () -> clientToken(cloud, "site", "site-secret"), CLINIC, "site",
                new Executor("site", "1", "example.site", Scope.BASELINE));
        site = node(siteDir, siteDatabases);
        site.servesAPlaceOf(CLINIC);
        site.readsItsOriginThrough(CLINIC, new TenantRuntimeManager.OriginFeeds(
                lane.placeFeed(Place.RECORDS), lane.placeFeed(Place.DEFINITIONS),
                lane.definitionsWithoutTheFace()));
        Files.writeString(siteDir.resolve(CLINIC + ".json"),
                DECLARATION.formatted(cloudConfiguration));
        UntilServed.scan(site, ROOT);
        UntilServed.scan(site, CLINIC);
        site.scanOnce();
        site.syncRound();

        assertTrue(mapNamed(site, CATALOGUE).contains("AsTheOriginPublishesIt"),
                "what the site's own configuration apply wrote still stands in front of the "
                        + "origin's version: " + mapNamed(site, CATALOGUE));
        assertTrue(mapNamed(site, OVERRIDDEN).contains("AsSomebodyOnTheSiteWroteIt"),
                "what somebody on the site wrote was released with the configuration's writes");
        assertEquals(java.util.Set.of(OVERRIDDEN, KEPT), parked(),
                "the place holds back something other than the maps somebody there wrote");
    }

    @Test
    @Order(2)
    @DisplayName("a person at the place lets one override go after reading what would go, the "
            + "origin's version applies, the trail names them, and the one they kept still "
            + "shadows")
    @Proving(DboPromises.SYNC_A_PERSON_LETS_A_LOCAL_OVERRIDE_GO)
    void aPersonLetsALocalOverrideGo() throws Exception {
        site.authority(CLINIC).ensureClient("keeper", "keeper-secret",
                List.of("configuration"));
        String keeper = clientToken(site, "keeper", "keeper-secret");
        ContentSyncEngine.ShadowedEvent overridden = shadowOf(OVERRIDDEN);
        String dependency = site.streamsOf(CLINIC).stream()
                .filter(stream -> stream.shadowedEvents().stream()
                        .anyMatch(one -> one.objectId().equals(overridden.objectId())))
                .findFirst().orElseThrow().dependency();
        String asked = """
                {"dependency":"%s","type":"StructureMap","records":["%s"]}"""
                .formatted(dependency, overridden.shadowedBy());

        assertEquals(422, overrides(keeper, "/preview", """
                {"dependency":"%s","type":"StructureMap","records":["not-a-shadow"]}"""
                .formatted(dependency)).statusCode(),
                "a record that holds nothing back was offered to let go");
        HttpResponse<String> preview = overrides(keeper, "/preview", asked);
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(preview.body().contains(OVERRIDDEN) && preview.body().contains(
                "\"record\":\"" + overridden.objectId() + "\""),
                "the preview did not say what goes and what the origin holds instead: "
                        + preview.body());
        assertTrue(mapNamed(site, OVERRIDDEN).contains("AsSomebodyOnTheSiteWroteIt"),
                "a preview let the override go");

        HttpResponse<String> letGo = overrides(keeper, "", asked);
        assertEquals(200, letGo.statusCode(), letGo.body());
        assertTrue(mapNamed(site, OVERRIDDEN).contains("AsTheOriginPublishesIt"),
                "the origin's version did not apply once the override went: "
                        + mapNamed(site, OVERRIDDEN));
        assertTrue(mapNamed(site, KEPT).contains("AsSomebodyOnTheSiteWroteIt"),
                "an override nobody named was let go");
        assertEquals(java.util.Set.of(KEPT), parked());

        HttpResponse<String> trail = http.send(HttpRequest.newBuilder(
                        URI.create(base(site) + "/fhir/AuditEvent?action=D&entity=StructureMap/"
                                + overridden.shadowedBy()))
                        .header("Authorization", "Bearer " + writer(site)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, trail.statusCode(), trail.body());
        assertTrue(trail.body().contains("keeper"),
                "the trail does not name who let the override go: " + trail.body());
    }

    private HttpResponse<String> overrides(String token, String verb, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(site) + "/overrides" + verb))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The maps the place holds back, by url. */
    private java.util.Set<String> parked() {
        java.util.Set<String> parked = new java.util.HashSet<>();
        for (ContentSyncEngine stream : site.streamsOf(CLINIC)) {
            stream.shadowedEvents().forEach(shadow -> {
                String held = new String(shadow.payload(), StandardCharsets.UTF_8);
                parked.add(held.contains(OVERRIDDEN) ? OVERRIDDEN
                        : held.contains(KEPT) ? KEPT : shadow.objectId());
            });
        }
        return parked;
    }

    private ContentSyncEngine.ShadowedEvent shadowOf(String url) {
        for (ContentSyncEngine stream : site.streamsOf(CLINIC)) {
            for (ContentSyncEngine.ShadowedEvent shadow : stream.shadowedEvents()) {
                if (new String(shadow.payload(), StandardCharsets.UTF_8).contains(url)) {
                    return shadow;
                }
            }
        }
        throw new AssertionError("nothing holds back " + url);
    }

    /** A repository holding these files, committed once on main. */
    private static Path repository(Path at, Map<String, String> files) throws Exception {
        Files.createDirectories(at);
        try (Git git = Git.init().setDirectory(at.toFile()).setInitialBranch("main").call()) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = at.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
            git.add().addFilepattern(".").call();
            PersonIdent owner = new PersonIdent("Owner", "owner@example.org");
            git.commit().setMessage("The clinic's maps").setAuthor(owner).setCommitter(owner)
                    .call();
        }
        return at;
    }

    private static String map(String url, String name) {
        return """
                {"resourceType":"StructureMap","url":"%s","name":"%s","status":"active",
                 "group":[{"name":"main","typeMode":"none","input":[{"name":"source","mode":"source"}],
                           "rule":[{"name":"copy","source":[{"context":"source"}]}]}]}"""
                .formatted(url, name);
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
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                        URI.create(base(node) + "/fhir/StructureMap"))
                        .header("Authorization", "Bearer " + writer(node))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, answer.statusCode(), answer.body());
        return answer.statusCode();
    }

    private static String mapNamed(TenantRuntimeManager node, String url) throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                        URI.create(base(node) + "/fhir/StructureMap?url="
                                + URLEncoder.encode(url, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + writer(node)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    private static String writer(TenantRuntimeManager node) {
        cloud.jengu.dbo.auth.TenantAuthority authority = node.authority(CLINIC);
        if (authority == null) {
            throw new AssertionError("the clinic's authority answers nothing");
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
