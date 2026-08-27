package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runtime can be asked which tenants it is serving, and what it is doing
 * about the ones it is not (#67).
 *
 * <p>The consumer is a drift report: an operator asks the store what it serves
 * and compares that against what the configuration declares. The cheap answer —
 * listing the spec files — reads the report's own writes, agrees with the
 * configuration by construction, and can only ever say "no drift". So the
 * answer has to come from runtime state, and the interesting tenant is the one
 * that appears in the directory and not in the answer.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARuntimeSaysWhatItIsServingIT {

    private static final String OPS_TOKEN = "ops-token-for-this-deployment";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        String jdbcUrl = SharedPostgres.urlFor("ARuntimeSaysWhatItIsServingIT");
        dir = Files.createTempDirectory("dbo-tenants-state");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        manager.serveRuntimeState(OPS_TOKEN);

        Files.writeString(dir.resolve("teenib.json"), spec("teenib", "r4"));
        // declared, and this container has no face for it: the state an
        // operator most wants, and the one a list of served tenants omits
        Files.writeString(dir.resolve("kukkunud.json"), spec("kukkunud", "seitsmes"));
        UntilServed.scan(manager, up -> up.contains("teenib"));
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

    private static String spec(String code, String version) {
        return """
                {"code":"%s","fhirVersion":"%s","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, version);
    }

    @Test
    @DisplayName("the runtime answers with a state per tenant, not a list of the ones that worked")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void everyDeclaredTenantHasAState() {
        Map<String, TenantState.State> states = manager.tenantStates().stream()
                .collect(Collectors.toMap(TenantState::code, TenantState::state));

        assertEquals(TenantState.State.SERVING, states.get("teenib"));
        assertEquals(TenantState.State.FAILED, states.get("kukkunud"),
                "a tenant that was declared and did not come up must say so, not be missing: "
                        + states);
    }

    @Test
    @DisplayName("and says it over the wire, to a caller the deployment named")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void anOperatorCanAskOverHttp() throws Exception {
        HttpResponse<String> answer = ask(OPS_TOKEN);

        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("{\"code\":\"kukkunud\",\"state\":\"failed\"}"),
                answer.body());
        assertTrue(answer.body().contains("{\"code\":\"teenib\",\"state\":\"serving\"}"),
                answer.body());
    }

    @Test
    @DisplayName("a caller the deployment did not name learns nothing, including how many there are")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void anUnnamedCallerIsRefused() throws Exception {
        assertEquals(401, ask("some-other-token").statusCode());
        assertEquals(401, ask(null).statusCode());
        assertTrue(!ask("some-other-token").body().contains("teenib"),
                "a refusal that leaked a tenant code would be the disclosure it exists to prevent");
    }

    @Test
    @DisplayName("a tenant nobody declares any more stops being a state at all")
    void retractionIsNotAFailure() throws Exception {
        Files.delete(dir.resolve("kukkunud.json"));
        manager.scanOnce();

        List<String> codes = manager.tenantStates().stream().map(TenantState::code).toList();

        assertEquals(List.of("teenib"), codes,
                "a removal reported as a failure would make every retraction look like a fault");
    }

    private HttpResponse<String> ask(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + manager.port() + "/runtime/tenants")).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
