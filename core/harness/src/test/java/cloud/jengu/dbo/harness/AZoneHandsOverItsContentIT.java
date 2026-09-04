package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A loader holding a zone's content hands it over, and gets a sentence back
 * that posting one resource at a time never gives it: how many were read, how
 * many applied, and which ones nobody could apply.
 *
 * <p>That absence is why a partial application presents to whoever declared it
 * as "my configuration had no effect".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AZoneHandsOverItsContentIT {

    static final String CLINIC = "handed-over";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String bearer;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-handed-over");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AZoneHandsOverItsContentIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(CLINIC));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("a-loader", "loader-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
        bearer = token();
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

    private static String valueSet(int i) {
        return "{\"type\":\"ValueSet\",\"name\":\"value-sets/vs-" + i + ".json\",\"payload\":"
                + "{\"resourceType\":\"ValueSet\",\"status\":\"active\","
                + "\"url\":\"https://zone.test/vs/" + i + "\",\"name\":\"VS" + i + "\"}}";
    }

    @Test
    @Order(1)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    void aSetIsHandedOverAndTheAnswerSaysWhatBecameOfIt() throws Exception {
        StringBuilder declarations = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            declarations.append(declarations.isEmpty() ? "" : ",").append(valueSet(i));
        }
        // One nobody can apply, beside four that are fine.
        declarations.append(",{\"type\":\"ValueSet\",\"name\":\"value-sets/broken.json\","
                + "\"payload\":{\"resourceType\":\"Nonesuch\"}}");

        HttpResponse<String> answered = hand("{\"correlation\":\"commit:abc123\","
                + "\"declarations\":[" + declarations + "]}");

        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("\"applied\":5"), answered.body());
        assertTrue(answered.body().contains("\"skipped\":1"), answered.body());

        Run pass = new Runs(manager.runtime(CLINIC).orElseThrow().engine())
                .byKey(ConfigApplication.PROCESS + "/" + ConfigApplication.STEP + "/" + CLINIC)
                .orElseThrow(() -> new AssertionError("handed over and never recorded"));
        assertEquals("commit:abc123", pass.correlated().orElseThrow(),
                "the declarer's own name for the set, echoed and never parsed");
        assertTrue(pass.needsAPerson(), "one of them needs somebody, and the run says so");

        // And the five that were fine are in the tenant, which is the point of
        // handing them over at all.
        assertEquals(5, held(), "applied, and not there");
    }

    @Test
    @Order(2)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    void aCredentialWithoutTheGrantHandsOverNothing() throws Exception {
        manager.authority(CLINIC).ensureClient("a-writer", "writer-secret",
                List.of("system/*.write"));
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CLINIC).replace("/fhir", "/configuration")))
                .header("Authorization", "Bearer " + token("a-writer", "writer-secret",
                        "system/*.write"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"declarations\":[" + valueSet(99) + "]}"));
        HttpResponse<String> refused =
                HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals(5, held(), "it was refused and applied anyway");
    }

    private HttpResponse<String> hand(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CLINIC).replace("/fhir", "/configuration")))
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * What the tenant holds, asked of the store rather than over the surface:
     * the surface answers nobody without a credential, and a reading
     * credential is a different grant from the one that hands configuration
     * over — which is the point of the scope, not an inconvenience of it.
     */
    private long held() {
        return manager.runtime(CLINIC).orElseThrow().engine()
                .count(cloud.jengu.dbo.core.api.Criteria.of("ValueSet"));
    }

    private static String token() throws Exception {
        return token("a-loader", "loader-secret", cloud.jengu.dbo.auth.Scopes.CONFIGURATION);
    }

    private static String token(String client, String secret, String scope) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + secret + "&scope="
                + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        String body = HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CLINIC).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        int at = body.indexOf("\"access_token\"");
        int start = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(start, body.indexOf('"', start));
    }
}
