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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration naming a referent the same apply creates.
 *
 * <p>A department points at its root organisation; a service points at the
 * department that provides it. Whoever composes the set has never seen either
 * store id, so the relationship had to be written as a logical reference —
 * which nothing downstream resolves, so a parented department read as
 * unparented.
 *
 * <p>A conditional reference is the form that would make the stored record
 * correct for every reader at once, and the authored path has always answered
 * one. The configuration door never did: declarations are written through the
 * engine with the face's grain applied, not through the face's accept path, so
 * the query was stored verbatim — a reference that reads as a promise and
 * resolves to nothing, which is worse than not having written one.
 *
 * <p><b>A type with no definition takes the path it always took.</b>
 * Answering a reference means parsing the body as FHIR, and a type this face
 * has no definition for cannot be parsed at all — so for it the parse is not
 * a lighter validation, it is a refusal of the only shape that ever worked.
 * It carries no conditional reference to answer either, which is why leaving
 * it alone costs nothing.
 *
 * <p><b>And the order is not the contract.</b> The set is composed here in the
 * worst order on purpose — every referrer before its referent — because the
 * alternative to fixing that is a rule nobody can see, discovered by whoever
 * composes a set the other way round.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ADeclarationNamesWhatTheSameApplyCreatesIT {

    private static final String CLINIC = "viide";
    private static final String ORGS = "https://viide.example/org";
    /** A type this face has no definition for, declared through the same door. */
    private static final String PARTICIPANTS = "https://viide.example/participant";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-refs");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADeclarationNamesWhatTheSameApplyCreatesIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Organization","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"ParticipantDeclaration","identity":"identifier","systems":["%s"],
                   "handling":"operational","definition":"none"}]}"""
                .formatted(CLINIC, ORGS, PARTICIPANTS));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("a-loader", "loader-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
        // Reading is a different right from declaring, and this door does not
        // imply that one — which is the arrangement working, not an obstacle.
        manager.authority(CLINIC).ensureClient("a-reader", "reader-secret",
                java.util.List.of("system/*.read"));
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @Order(1)
    @DisplayName("a declared department points at the root the same apply creates, and the "
            + "stored record names it by id — composed referrer-first on purpose")
    @Proving(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT)
    void theReferrerIsComposedFirstAndStillResolves() throws Exception {
        // Worst order deliberately: the leaf, then its parent, then the root.
        HttpResponse<String> applied = apply(
                declaration("ward", org("ward", "department")),
                declaration("department", org("department", "root")),
                declaration("root", org("root", null)));

        assertEquals(200, applied.statusCode(), applied.body());
        assertTrue(applied.body().contains("\"applied\":3"),
                "not everything was applied, so an order nobody stated is still a contract: "
                        + applied.body());
        assertTrue(applied.body().contains("\"cards\":[]"),
                "something was carded: " + applied.body());

        String department = read("department");
        assertTrue(department.contains("\"reference\":\"Organization/"),
                "the stored record does not name its parent by id, so every reader still has "
                        + "to resolve a question: " + department);
        // On partOf specifically: the bundle's own self link is a query too,
        // and asserting over the whole answer would go red for the wrong
        // reason — or worse, green for one.
        assertFalse(department.contains("\"partOf\":{\"reference\":\"Organization?"),
                "the query was stored verbatim — a reference that reads as a promise and "
                        + "resolves to nothing: " + department);
    }

    @Test
    @Order(2)
    @DisplayName("and the id it names is the root's own, so the relationship a reader follows "
            + "is the one that was declared")
    @Proving(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT)
    void itPointsAtTheRightRecord() throws Exception {
        String rootId = idOf(read("root"));
        assertTrue(read("department").contains("Organization/" + rootId),
                "the department points somewhere, but not at the root it declared");
    }

    @Test
    @Order(3)
    @DisplayName("a reference nobody can answer fails the declaration by name, rather than "
            + "landing as a question for a reader to trip over later")
    @Proving(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT)
    void whatCannotBeAnsweredIsRefused() throws Exception {
        HttpResponse<String> applied = apply(
                declaration("orphan", org("orphan", "a-parent-nobody-declared")));

        assertEquals(200, applied.statusCode(), applied.body());
        assertTrue(applied.body().contains("\"skipped\":1"), applied.body());
        assertTrue(applied.body().contains("a-parent-nobody-declared"),
                "the card does not name the reference that could not be answered: "
                        + applied.body());
    }

    @Test
    @Order(4)
    @DisplayName("a declaration of a type this face has no definition for lands as it "
            + "arrived, because answering references is a parse and it has nothing to parse "
            + "against")
    @Proving(DboPromises.TEN_A_DECLARATION_NAMES_ITS_REFERENT)
    void aTypeWithNoDefinitionIsStillDeclarable() throws Exception {
        HttpResponse<String> applied = apply(participant("main-lab"));

        assertEquals(200, applied.statusCode(), applied.body());
        assertTrue(applied.body().contains("\"applied\":1"),
                "a declaration of a definitionless type did not land, so the door answers "
                        + "references at the cost of every type that has none: "
                        + applied.body());
        assertTrue(applied.body().contains("\"cards\":[]"),
                "carded: " + applied.body());

        String stored = readParticipant("main-lab");
        assertTrue(stored.contains("\"zone\":\"ee\""),
                "the record does not hold what was declared: " + stored);
    }

    // ------------------------------------------------------------ plumbing

    private static String participant(String name) {
        String payload = """
                {"resourceType":"ParticipantDeclaration",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "zone":"ee","repo":"https://git.test/cfg"}""".formatted(PARTICIPANTS, name);
        return """
                {"type":"ParticipantDeclaration","name":"%s","payload":%s}"""
                .formatted(name, payload);
    }

    private static String readParticipant(String name) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CLINIC)
                        + "/ParticipantDeclaration?identifier="
                        + URLEncoder.encode(PARTICIPANTS + "|" + name, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + reader()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String org(String code, String parent) {
        String partOf = parent == null ? "" :
                ",\"partOf\":{\"reference\":\"Organization?identifier=%s|%s\"}"
                        .formatted(ORGS, parent);
        return """
                {"resourceType":"Organization","identifier":[{"system":"%s","value":"%s"}],
                 "name":"%s"%s}""".formatted(ORGS, code, code, partOf);
    }

    private static String declaration(String name, String payload) {
        return """
                {"type":"Organization","name":"%s","payload":%s}""".formatted(name, payload);
    }

    private static HttpResponse<String> apply(String... declarations) throws Exception {
        String body = "{\"declarations\":[" + String.join(",", declarations) + "]}";
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(CLINIC).replace("/fhir", "/configuration")))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String read(String code) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CLINIC)
                        + "/Organization?identifier="
                        + URLEncoder.encode(ORGS + "|" + code, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + reader()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String idOf(String bundle) {
        return bundle.replaceAll("(?s).*?\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private static String reader() throws Exception {
        return tokenFor("a-reader", "reader-secret", "system/*.read");
    }

    private static String token() throws Exception {
        return tokenFor("a-loader", "loader-secret", cloud.jengu.dbo.auth.Scopes.CONFIGURATION);
    }

    private static String tokenFor(String client, String secret, String scope) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client + "&client_secret="
                + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(CLINIC).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
