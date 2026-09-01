package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One server, one answer about how many and in what order.
 *
 * <p>dbo serves some types through the ordinary FHIR path and some through a
 * surface of their own — the audit trail today, runs tomorrow. A client should
 * not be able to tell which is which by how {@code _count} and {@code _sort}
 * behave: those shape a result, they are not filters, and they mean the same
 * thing whatever is being read.
 *
 * <p>This is the ratchet against the two contracts drifting apart again. It
 * asks the SAME questions of a type on each path and requires the same kind of
 * answer — which is how the divergence was found, by a consumer whose audit
 * page worked against one path and 400'd against the other.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneResultContractIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-result");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("OneResultContractIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("uhtlepingud.json"), """
                {"code":"uhtlepingud","face":"r4",
                 "audit":{"level":"writes"},
                 "types":[{"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("uhtlepingud"));
        base = manager.baseUrl("uhtlepingud");
        // three writes: three Patients on the ordinary path, and three audit
        // entries on the surfaced one, so both have something to bound
        for (int i = 0; i < 3; i++) {
            post("/Patient", "{\"resourceType\":\"Patient\"}");
        }
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

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int members(String bundle, String type) {
        return bundle.split("\"resourceType\":\"" + type + "\"", -1).length - 1;
    }

    @Test
    @Proving(DboPromises.POL_FHIR_AUDIT_PROJECTION)
    void bothPathsBoundAPageTheSameWay() throws Exception {
        assertEquals(2, members(get("/Patient?_count=2").body(), "Patient"),
                "the ordinary path bounds a page");
        assertEquals(2, members(get("/AuditEvent?_count=2").body(), "AuditEvent"),
                "and so does the surfaced one — same server, same parameter");
    }

    @Test
    void bothPathsOrderTheSameWay() throws Exception {
        assertEquals(200, get("/Patient?_sort=-_lastUpdated").statusCode());
        assertEquals(200, get("/AuditEvent?_sort=-date").statusCode(),
                "an ordering a surface can give is honoured, not refused for being "
                        + "a result parameter");
    }

    @Test
    @Proving(DboPromises.SRCH_STRICT_BY_DEFAULT)
    void bothPathsRefuseWhatTheyCannotHonourRatherThanIgnoringIt() throws Exception {
        // an unreadable count is not a count, on either path
        assertEquals(400, get("/Patient?_count=lots").statusCode());
        assertEquals(400, get("/AuditEvent?_count=lots").statusCode());

        // and a field a path cannot order by is refused there, which is a
        // property of that surface rather than of the parameter
        assertEquals(400, get("/AuditEvent?_sort=agent").statusCode(),
                "the trail orders by time, and says so rather than answering "
                        + "a different question");
    }

    @Test
    void theStatementDeclaresThemOnceForTheServer() throws Exception {
        String capability = get("/metadata").body();
        String rest = capability.substring(capability.indexOf("\"rest\""),
                capability.indexOf("\"resource\""));
        assertTrue(rest.contains("_count") && rest.contains("_sort"),
                "result parameters belong to the server, not to each type: " + rest);
    }
}
