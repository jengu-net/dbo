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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment can present the credential it holds, so a guarded tenant is
 * reachable by whoever runs it.
 *
 * <p>In a cluster the operator generates the bootstrap secret, writes it into
 * custody and the serving side ensures the client record from there. A
 * deployment with no operator had no such path: the secret was generated in
 * memory, written down nowhere and readable through no surface, so a tenant
 * came up guarded by a credential nobody could present. The only way to run
 * one was with the authority switched off — the configuration the launcher
 * itself calls embedded/test only.
 *
 * <p>So custody is stated rather than invented. What is asserted here is not
 * that a field is carried, but the thing that makes the difference: a token
 * obtained with the secret the deployment chose opens the tenant's surface.
 *
 * <p><b>Per tenant, not per deployment.</b> One secret opening every tenant
 * would undo the isolation the store is otherwise built around, so the second
 * test presents one tenant's credential to another and expects to be refused.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ADeploymentPresentsItsOwnCredentialIT {

    private static final String ONE = "custody-one";
    private static final String TWO = "custody-two";
    private static final String ONE_SECRET = "the-secret-this-deployment-chose";
    private static final String TWO_SECRET = "a-different-one-entirely";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-custody");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADeploymentPresentsItsOwnCredentialIT"),
                postgres.getUsername(), postgres.getPassword());
        // What custody holds, named before anything is provisioned — which is
        // the order a deployment actually has: the secret exists in its vault
        // before the tenant it belongs to exists.
        provisioner.bootstrapSecrets(Map.of(ONE, ONE_SECRET, TWO, TWO_SECRET));
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        for (String code : java.util.List.of(ONE, TWO)) {
            Files.writeString(dir.resolve(code + ".json"), """
                    {"code":"%s","face":"r4","types":[
                      {"name":"Patient","identity":"identifier",
                       "systems":["https://custody.example/nid"],
                       "handling":"operational"}]}""".formatted(code));
        }
        UntilServed.scan(manager, ONE, TWO);
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
    @DisplayName("the secret the deployment chose is the one the tenant's authority accepts, so "
            + "a guarded surface is reachable by whoever runs it")
    @Proving(DboPromises.AUTH_BOOTSTRAP_SECRET_IS_CUSTODY)
    void theDeploymentsOwnSecretOpensItsTenant() throws Exception {
        String token = token(ONE, ONE_SECRET);
        assertNotEquals("", token, "no token came back for the secret custody holds");

        HttpResponse<String> guarded = HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(ONE) + "/metadata"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, guarded.statusCode(), guarded.body());
        assertTrue(guarded.body().contains("CapabilityStatement"), guarded.body());
    }

    @Test
    @Order(2)
    @DisplayName("and it opens that tenant only: a credential is custody for one tenant, never "
            + "a key to the deployment")
    @Proving(DboPromises.AUTH_BOOTSTRAP_SECRET_IS_CUSTODY)
    void oneTenantsCredentialIsNotAnothers() throws Exception {
        // The right secret, presented to the wrong tenant's authority. If this
        // ever succeeds, the per-tenant map has become a deployment-wide key
        // and the isolation everything else rests on is decoration.
        String wrong = token(TWO, ONE_SECRET);
        assertEquals("", wrong,
                "one tenant's bootstrap secret authenticated against another tenant");

        String right = token(TWO, TWO_SECRET);
        assertNotEquals("", right, "the second tenant's own secret was refused");
        assertNotEquals(token(ONE, ONE_SECRET), right,
                "two tenants issued the same token, so the issuer is not per tenant");
    }

    /** The token, or "" when the authority refused the credential. */
    private static String token(String code, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl(code).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() != 200) {
            return "";
        }
        return answer.body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
