package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-container: the dbo-tenant bundle wires the whole chain inside
 * Felix — default provisioner registered as a service, spec file appears,
 * per-tenant services land in the registry with tenant= properties, and the
 * FHIR endpoint answers over host-reachable HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantOsgiIT {

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static Framework framework;
    static Path dir;
    static int httpPort;
    static Bundle tenantBundle;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("TenantOsgiIT");
        dir = Files.createTempDirectory("dbo-tenants-osgi");
        try (var socket = new java.net.ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage",
                FelixStorage.directory("dbo-tenant-felix"));
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        config.put("dbo.tenant.dir", dir.toString());
        config.put("dbo.tenant.http.host", "127.0.0.1");
        config.put("dbo.tenant.http.port", String.valueOf(httpPort));
        config.put("dbo.tenant.admin.url", jdbcUrl);
        config.put("dbo.tenant.admin.user", postgres.getUsername());
        config.put("dbo.tenant.admin.password", postgres.getPassword());

        framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.start();
        BundleContext ctx = framework.getBundleContext();
        ctx.installBundle("file:" + System.getProperty("pg.driver.jar")).start();
        for (String prop : List.of("dbo.core.jar", "dbo.fhir.common.jar", "dbo.postgres.jar",
                "dbo.terminology.jar", "dbo.subscriptions.jar", "dbo.fhir.r4.jar",
                "dbo.fhir.r5.jar", "dbo.rest.jar", "dbo.auth.jar", "dbo.pdi.jar", "dbo.policy.jar",
                // the manager wires declared content dependencies —
                // without this bundle the tenant bundle does not resolve
                "dbo.sync.jar",
                // and it mounts the maintenance surface,
                // which is the same kind of requirement: an import nothing
                // exports leaves the tenant runtime unresolved, and the
                // failure reads as the tenant bundle failing to start
                "dbo.maintenance.jar")) {
            ctx.installBundle("file:" + System.getProperty(prop)).start();
        }
        tenantBundle = ctx.installBundle("file:" + System.getProperty("dbo.tenant.jar"));
        tenantBundle.start();
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20_000);
        }
    }

    @Test
    @Timeout(180)
    void aSpecFileLightsUpTheWholeChainInContainer() throws Exception {
        assertEquals(Bundle.ACTIVE, tenantBundle.getState());
        BundleContext ctx = framework.getBundleContext();
        // The host's test classpath carries same-named classes, so plain
        // getServiceReferences hides bundle services as incompatible
        // (isAssignableTo across classloaders) — the host observes via
        // getAllServiceReferences, which skips compatibility filtering.
        assertTrue(ctx.getAllServiceReferences(
                "cloud.jengu.dbo.tenant.TenantDatabaseProvisioner", null).length >= 1);

        // drop a spec: the manager provisions and registers the service set
        Files.writeString(dir.resolve("konteiner.json"), """
                {"code":"konteiner","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");

        String base = "http://127.0.0.1:" + httpPort + "/t/konteiner/fhir";
        HttpClient http = HttpClient.newHttpClient();
        long deadline = System.currentTimeMillis() + 60_000;
        int status = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                status = http.send(HttpRequest.newBuilder(URI.create(base + "/metadata")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode();
                if (status == 200) {
                    break;
                }
            } catch (java.io.IOException e) {
                // server not up yet
            }
            Thread.sleep(250);
        }
        assertEquals(200, status, "the tenant endpoint must come up from the spec file alone");

        // per-tenant services visible in the registry with tenant= properties
        ServiceReference<?>[] stores = ctx.getAllServiceReferences(
                "cloud.jengu.dbo.fhir.common.FhirStoreFacade", "(tenant=konteiner)");
        assertTrue(stores != null && stores.length == 1,
                "FhirStoreFacade(tenant=konteiner) must be registered");
        ServiceReference<?>[] feeds = ctx.getAllServiceReferences(
                "cloud.jengu.dbo.core.api.feed.ChangeFeed", "(tenant=konteiner)");
        assertTrue(feeds != null && feeds.length == 1);

        // and a real write round-trips over HTTP
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(base + "/Patient"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Konteiner\"}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());

        // spec removal retracts the services
        Files.delete(dir.resolve("konteiner.json"));
        deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ServiceReference<?>[] remaining = ctx.getAllServiceReferences(
                    "cloud.jengu.dbo.fhir.common.FhirStoreFacade", "(tenant=konteiner)");
            if (remaining == null) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("tenant services must retract when the spec disappears");
    }
}
