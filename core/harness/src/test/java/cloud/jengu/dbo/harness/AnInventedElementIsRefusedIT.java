package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An element this face does not define is refused, where it used to be kept.
 *
 * <p>The store has always taken this position on an unrecognised <b>search
 * parameter</b>: refused rather than answered more broadly, because a caller
 * who believed a filter applied would act on a wider answer than they asked
 * for. The write path took the opposite position on an unrecognised
 * <b>element</b>, and took it silently — the element was stored, handed back
 * to the next reader as though it were part of the record, invisible to
 * validation, and unsearchable.
 *
 * <p>The two halves disagreed by construction. The parser drops what it does
 * not recognise before the validator sees it, and the bytes are kept as
 * written so that what was written is what is read. Both are wanted on their
 * own; together they meant a document could carry a field the store never
 * checked and could never answer by.
 *
 * <p><b>The parser always knew.</b> {@code Manager.parseSingle} hands it no
 * list to report into, and {@code ParserBase#logError} returns on its first
 * line when the list is null — so the finding was made and dropped, every
 * time.
 *
 * <p>FHIR has a way to carry what a resource does not define, and this is not
 * it: {@code extension} is the extension point, and a test below writes the
 * same fact that way to keep the refusal from reading as "no extra data
 * allowed".
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnInventedElementIsRefusedIT {

    private static final String CLINIC = "invented";
    private static final String NID = "https://invented.example/nid";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-invented");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnInventedElementIsRefusedIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC, NID));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("a-writer", "writer-secret",
                java.util.List.of("system/*.read", "system/*.write"));
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
    @DisplayName("an element this face does not define is refused by name, rather than stored "
            + "and handed back as though the store had checked it")
    @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
    void anInventedElementIsRefused() throws Exception {
        HttpResponse<String> refused = post("/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"one"}],
                 "favouriteColour":"blue"}""".formatted(NID));

        assertEquals(422, refused.statusCode(),
                "an invented element was accepted, so the record now carries a field the "
                        + "store never validated and cannot search: " + refused.body());
        assertTrue(refused.body().contains("favouriteColour"),
                "the refusal does not name the element, which is the whole of what a writer "
                        + "needs to fix it: " + refused.body());
    }

    @Test
    @DisplayName("nested, and on another type, because the rule is the parser's rather than a "
            + "list of known field names")
    @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
    void nestedAndOnAnotherType() throws Exception {
        assertEquals(422, post("/Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"urn:x","code":"1"}]},
                 "housePoints":50}""").statusCode());

        HttpResponse<String> nested = post("/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"two"}],
                 "name":[{"family":"Ambrose","housePoints":50}]}""".formatted(NID));
        assertEquals(422, nested.statusCode(),
                "an invented element inside a nested element was accepted: " + nested.body());
        assertTrue(nested.body().contains("housePoints"), nested.body());
    }

    @Test
    @DisplayName("$validate says the same thing the write does, which it did not: the element "
            + "was invisible to validation rather than tolerated by it")
    @Proving({DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED,
            DboPromises.VER_VALIDATION_WITHOUT_WRITING})
    void validateSaysTheSameThing() throws Exception {
        HttpResponse<String> outcome = post("/Patient/$validate", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"three"}],
                 "favouriteColour":"blue"}""".formatted(NID));

        assertEquals(200, outcome.statusCode(), outcome.body());
        assertTrue(outcome.body().contains("favouriteColour"),
                "$validate reported nothing about an element the write refuses, so a writer "
                        + "asking first is told the body is fine: " + outcome.body());
    }

    @Test
    @DisplayName("and the sanctioned way to carry the same fact is accepted, so this is a rule "
            + "about inventing elements rather than about extra data")
    @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
    void anExtensionCarriesItInstead() throws Exception {
        HttpResponse<String> accepted = post("/Patient", """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"four"}],
                 "extension":[{"url":"https://invented.example/colour","valueString":"blue"}]}"""
                .formatted(NID));

        assertEquals(201, accepted.statusCode(),
                "an extension is FHIR's own way to carry what a resource does not define, and "
                        + "refusing it would make this a rule about extra data: "
                        + accepted.body());
        assertTrue(accepted.body().contains("invented.example/colour"),
                "the extension did not survive the round trip: " + accepted.body());
    }

    // ------------------------------------------------------------ plumbing

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CLINIC) + path))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        String form = "grant_type=client_credentials&client_id=a-writer&client_secret="
                + URLEncoder.encode("writer-secret", StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(CLINIC).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
