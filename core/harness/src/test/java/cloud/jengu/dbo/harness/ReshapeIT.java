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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Reshape: the store converts stamped stock to a target major in
 * place — the engine owning the loop, the face owning the transformation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReshapeIT {

    /**
     * One canonical whose major bumps — which is what a breaking shape change
     * IS: {@code meta.profile} stays put and the stamp moves.
     */
    private static final String SHAPE = "https://sonavara.example/StructureDefinition/note";
    private static final String UNCOVERED = "https://sonavara.example/StructureDefinition/orphan";

    /** The hop, spelled the way FHIR spells a versioned reference. */
    private static final String MAP = """
            {"resourceType":"StructureMap",
             "url":"https://sonavara.example/StructureMap/note-2-to-3","version":"1.0.0",
             "name":"NoteTwoToThree","status":"active",
             "structure":[{"url":"%s|2.0.0","mode":"source"},
                          {"url":"%s|3.0.0","mode":"target"}],
             "group":[{"name":"main","typeMode":"types",
               "input":[{"name":"src","type":"Basic","mode":"source"},
                        {"name":"tgt","type":"Basic","mode":"target"}],
               "rule":[
                 {"name":"code","source":[{"context":"src","element":"code","variable":"c"}],
                  "target":[{"context":"tgt","contextType":"variable","element":"code",
                             "transform":"copy","parameter":[{"valueId":"c"}]}]},
                 {"name":"meta","source":[{"context":"src","element":"meta","variable":"m"}],
                  "target":[{"context":"tgt","contextType":"variable","element":"meta",
                             "transform":"copy","parameter":[{"valueId":"m"}]}]}]}]}""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static String adminBase;
    static String oldStock;
    private static volatile String cachedToken;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-reshape");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ReshapeIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve("kuju.json"), """
                {"code":"kuju","face":"r4","types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"StructureMap","identity":"canonical","handling":"operational"},
                  {"name":"Basic","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "kuju");
        base = manager.baseUrl("kuju");
        adminBase = "http://127.0.0.1:" + manager.port() + "/t/kuju/admin";

        HttpResponse<String> firstShape = post("/StructureDefinition", shape(SHAPE, "2.0.0"));
        assertEquals(201, firstShape.statusCode(), firstShape.body());
        assertEquals(201, post("/StructureDefinition", shape(UNCOVERED, "2.0.0")).statusCode());

        // Stock written while the pack stood at 2.0.0 — stamped as it was.
        HttpResponse<String> created = post("/Basic", note(SHAPE));
        assertEquals(201, created.statusCode(), created.body());
        oldStock = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertTrue(get("/Basic/" + oldStock).body().contains("\"valueString\":\"2.0.0\""),
                "the stock starts stamped 2.0.0");

        // The pack advances, and ships the converter for the hop beside it.
        assertTrue(put("/StructureDefinition?url="
                + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8),
                shape(SHAPE, "3.0.0")).statusCode() < 300);
        HttpResponse<String> mapWritten = post("/StructureMap", MAP.formatted(SHAPE, SHAPE));
        assertEquals(201, mapWritten.statusCode(), mapWritten.body());
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

    private static String shape(String url, String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"Shape%s","status":"active","kind":"resource","abstract":false,
                 "type":"Basic",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(url, version, Math.abs(url.hashCode()));
    }

    private static String note(String profile) {
        return """
                {"resourceType":"Basic","code":{"text":"note"},
                 "meta":{"profile":["%s"]}}""".formatted(profile);
    }

    @Test
    @Order(1)
    @Proving(DboPromises.SHAPE_RESHAPED_IN_PLACE)
    @DisplayName("stock stamped below the target is converted in place; the new version "
            + "carries the new stamp and history keeps the old one")
    void convertsInPlace() throws Exception {
        String run = reshape(SHAPE, 3);
        assertTrue(run.contains("\"converted\":1"), run);

        assertTrue(get("/Basic/" + oldStock).body().contains("\"valueString\":\"3.0.0\""),
                "the converted object is stamped at the target");
        String history = get("/Basic/" + oldStock + "/_history").body();
        assertTrue(history.contains("\"valueString\":\"2.0.0\""),
                "and the version written under the old shape keeps its own stamp: " + history);
    }

    @Test
    @Order(2)
    @Proving(DboPromises.SHAPE_RESHAPE_RESUMABLE)
    @DisplayName("a re-run finds only what is still behind — converted stock is not "
            + "converted twice")
    void reRunConvertsNothing() throws Exception {
        String run = reshape(SHAPE, 3);
        assertTrue(run.contains("\"converted\":0"), run);
        assertTrue(run.contains("\"complete\":true"), run);
    }

    @Test
    @Order(3)
    @Proving(DboPromises.SHAPE_REFUSED_OBJECT_LEFT_BEHIND)
    @DisplayName("an object no map covers is named and left behind, with its reason, and "
            + "the run does not claim to be complete")
    void uncoveredIsNamedAndLeftBehind() throws Exception {
        HttpResponse<String> created = post("/Basic", note(UNCOVERED));
        assertEquals(201, created.statusCode(), created.body());
        String orphan = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        String run = reshape(UNCOVERED, 3);
        assertTrue(run.contains("\"converted\":0"), run);
        assertTrue(run.contains(orphan) && run.contains("no converter covers"), run);
        assertTrue(run.contains("\"complete\":false"),
                "the walk ended but the data is still old, and the run says so: " + run);
        assertTrue(get("/Basic/" + orphan).body().contains("\"valueString\":\"2.0.0\""),
                "and the object is untouched");
    }

    @Test
    @Order(4)
    @Proving(DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK)
    @DisplayName("a stamp outlives the pack version that made it: re-numbering the shape "
            + "leaves the stock findable and countable under what stamped it")
    void stampOutlivesItsPack() throws Exception {
        String stranded = post("/Basic", note(UNCOVERED)).body()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // The pack re-numbers the shape out from under stock already stamped.
        assertTrue(put("/StructureDefinition?url="
                + URLEncoder.encode(UNCOVERED, StandardCharsets.UTF_8),
                shape(UNCOVERED, "9.0.0")).statusCode() < 300);

        assertTrue(get("/Basic?_shape-below="
                        + URLEncoder.encode(UNCOVERED + "|9", StandardCharsets.UTF_8))
                .body().contains(stranded),
                "stock stamped under the withdrawn version is still findable");
        assertTrue(cloud.jengu.dbo.maintenance.TenantInventory.shapes(
                        provisioner.provision(cloud.jengu.dbo.tenant.TenantSpec.parse(
                                Files.readString(dir.resolve("kuju.json")))).dataSource())
                .stream().anyMatch(l -> "2.0.0".equals(l.version())
                        && UNCOVERED.equals(l.profile())),
                "and still counted under the version that stamped it");
    }

    @Test
    @Order(5)
    @Proving(DboPromises.SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING)
    @DisplayName("a claim writes nothing and holds nothing: abandoning it strands no data, "
            + "and the same stock comes back")
    void claimHoldsNothing() throws Exception {
        // Stock behind a bound this store's own maps do not cover — the case
        // the hand-back lane exists for. The pack stands at 9.0.0 by now, so
        // fresh stock is stamped 9.0.0 and "behind" means below 10.
        String stranded = idOf(post("/Basic", note(UNCOVERED)));

        String first = claim(UNCOVERED, 10);
        assertTrue(first.contains(stranded), first);

        // Walk away. Nothing was held, so nothing is stuck.
        String second = claim(UNCOVERED, 10);
        assertTrue(second.contains(stranded),
                "an abandoned claim strands nothing — the stock is simply still behind: "
                        + second);
    }

    @Test
    @Order(6)
    @Proving(DboPromises.SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE)
    @DisplayName("a converted form handed back is validated, re-stamped and version-checked; "
            + "a stale one is refused and its object left untouched")
    void handBackKeepsTheDiscipline() throws Exception {
        String id = idOf(post("/Basic", note(UNCOVERED)));
        long version = versionOf(claim(UNCOVERED, 10), id);

        // A stale hand-back: the version moved on since it was claimed.
        assertTrue(put("/Basic/" + id, note(UNCOVERED)).statusCode() < 300);
        String stale = applyBack(id, version, note(UNCOVERED));
        assertTrue(stale.contains("\"converted\":0") && stale.contains(id),
                "a form built from stock that has moved is refused by name: " + stale);

        // Converted outside and handed back at the version it now holds.
        long current = versionOf(claim(UNCOVERED, 10), id);
        String applied = applyBack(id, current, note(UNCOVERED));
        assertTrue(applied.contains("\"converted\":1"), applied);
        assertTrue(get("/Basic/" + id).body().contains("\"valueString\":\"9.0.0\""),
                "the pack re-stamped what came back, rather than the runner asserting it");
    }

    private static String claim(String profile, int target) throws Exception {
        return admin("/reshape/claim?type=Basic&profile="
                + URLEncoder.encode(profile, StandardCharsets.UTF_8) + "&target=" + target)
                .body();
    }

    private static long versionOf(String claimJson, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "\\{\"id\":\"" + id + "\",\"version\":(\\d+)")
                .matcher(claimJson);
        assertTrue(m.find(), "the claim names " + id + ": " + claimJson);
        return Long.parseLong(m.group(1));
    }

    private static String applyBack(String id, long version, String payload) throws Exception {
        String body = "{\"held\":[{\"id\":\"" + id + "\",\"version\":" + version
                + ",\"payload\":\"" + java.util.Base64.getEncoder().encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
        return send(HttpRequest.newBuilder(URI.create(adminBase + "/reshape/apply?type=Basic"))
                .POST(HttpRequest.BodyPublishers.ofString(body))).body();
    }

    private static String idOf(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        return created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    // ---------------------------------------------------------- plumbing

    private static String reshape(String profile, int target) throws Exception {
        return admin("/reshape?type=Basic&profile="
                + URLEncoder.encode(profile, StandardCharsets.UTF_8) + "&target=" + target)
                .body();
    }

    // This tenant has an authority — the admin surface needs one — so every
    // request carries the tenant's own bootstrap token, cached for the class
    // because minting one per call would test the token endpoint, not this.
    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/fhir+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    private static HttpResponse<String> admin(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(adminBase + path))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.header("Authorization", "Bearer " + token()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        if (cachedToken != null) {
            return cachedToken;
        }
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(provisioner.bootstrapClientSecret("kuju"),
                        StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/kuju/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        cachedToken = body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        return cachedToken;
    }
}
