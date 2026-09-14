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
import org.junit.jupiter.api.Timeout;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant holds a declaration of its own, as itself.
 *
 * <p>A consumer's own declarations — which tenant this is, which zone it
 * belongs to, where its configuration is kept — have no FHIR shape, and FHIR
 * will not let them be given one: a StructureDefinition defining a resource
 * type the specification does not have is refused by its own validator, and
 * the only legal way to describe an arbitrary shape is a logical model, which
 * is not a resource and can be neither read nor searched. Measured, not
 * assumed — that refusal is what sent this design here.
 *
 * <p>So the alternative was a second representation: the document as a string
 * inside a {@code Basic}, searchable by nothing, validated as a Basic. This
 * store's own model says the opposite — the payload is the spec, verbatim, and
 * a second representation is a second thing to be wrong.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantHoldsItsOwnDeclarationIT {

    static final String CODE = "holds-its-own";
    static final String SYS = "https://declarations.test/participant";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String service;
    static String id;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    /** As a declarer writes it: no resourceType is required of it, and none is added. */
    static final String DECLARATION = "{\"resourceType\":\"ParticipantDeclaration\","
            + "\"identifier\":[{\"system\":\"" + SYS + "\",\"value\":\"main-lab\"}],"
            + "\"zone\":\"ee\",\"repo\":\"https://git.test/cfg\"}";

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-holds-its-own");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantHoldsItsOwnDeclarationIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CODE + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"ParticipantDeclaration","identity":"identifier","systems":["%s"],
                   "handling":"operational","definition":"none"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CODE, SYS));
        UntilServed.scan(manager, CODE);
        service = token();
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
    @Timeout(600)
    @Proving(DboPromises.CORE_PAYLOAD_IS_TRUTH)
    @DisplayName("a declaration of a type the face does not define is written, and read back "
            + "byte for byte")
    void writtenAndReadBackVerbatim() throws Exception {
        HttpResponse<String> wrote = post("/ParticipantDeclaration", DECLARATION);
        assertEquals(201, wrote.statusCode(), wrote.body());
        // From the Location header, not the body: the body is the declaration
        // as it was written, and nothing has been added to it — which is the
        // thing this test is about.
        String location = wrote.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("created and not told where: " + wrote.body()));
        id = location.substring(location.lastIndexOf('/') + 1);

        HttpResponse<String> read = get("/ParticipantDeclaration/" + id);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(DECLARATION, read.body(),
                "read back as something other than what was written — which is the second "
                        + "representation this exists to avoid");
    }

    @Test
    @Order(2)
    @Timeout(600)
    @Proving(DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS)
    @DisplayName("it is found by the identity its type declares")
    void foundByItsIdentity() throws Exception {
        String found = get("/ParticipantDeclaration?identifier="
                + URLEncoder.encode(SYS + "|main-lab", StandardCharsets.UTF_8)).body();

        assertTrue(found.contains("\"fullUrl\""),
                "declared findable by its identity and not found by it: " + found);
        assertTrue(found.contains("main-lab"), found);
    }

    /**
     * And the face it is not defined by still works. A type declared opaque
     * must not make the tenant's FHIR types opaque with it — the failure that
     * would matter most and show up least.
     */
    @Test
    @Order(3)
    @Timeout(600)
    @DisplayName("an ordinary FHIR type in the same tenant is still parsed, validated and "
            + "rendered as it always was")
    void theRestOfTheTenantIsUnchanged() throws Exception {
        HttpResponse<String> patient = post("/Patient",
                "{\"resourceType\":\"Patient\",\"active\":true}");
        assertEquals(201, patient.statusCode(), patient.body());
        assertTrue(patient.body().contains("\"meta\""),
                "a defined type stopped being rendered: " + patient.body());

        HttpResponse<String> refused = post("/Patient",
                "{\"resourceType\":\"Patient\",\"active\":\"not-a-boolean\"}");
        assertTrue(refused.statusCode() >= 400,
                "a defined type stopped being validated, which is what declaring another type "
                        + "opaque must never do: " + refused.statusCode() + " " + refused.body());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CODE) + path))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CODE) + path))
                        .header("Authorization", "Bearer " + service).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        manager.authority(CODE).ensureClient("holder", "holder-secret",
                List.of("system/*.read", "system/*.write"));
        String form = "grant_type=client_credentials&client_id=holder&client_secret="
                + URLEncoder.encode("holder-secret", StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CODE).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString()).body()
                .replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
