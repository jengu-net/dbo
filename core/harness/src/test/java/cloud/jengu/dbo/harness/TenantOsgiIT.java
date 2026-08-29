package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
        // The logging arrangement the distribution ships: the mediator first
        // (a framework extension, never started), then the API bundle, then
        // the binding that provides its serviceloader capability.
        ctx.installBundle("file:" + System.getProperty("spifly.jar"));
        ctx.installBundle("file:" + System.getProperty("slf4j.api.jar"));
        ctx.installBundle("file:" + System.getProperty("dbo.logging.jar")).start();
        for (String prop : List.of("dbo.core.jar", "dbo.fhir.common.jar", "dbo.postgres.jar",
                // the HL7/HAPI engine both personalities import from
                "dbo.fhir.stack.jar",
                "dbo.terminology.jar",
                // run records, before subscriptions and policy, which write them
                "dbo.work.jar",
                "dbo.subscriptions.jar",
                // the shared facade, before the faces that import it
                "dbo.fhir.element.jar",
                "dbo.fhir.r4.jar", "dbo.fhir.r5.jar", "dbo.rest.jar", "dbo.auth.jar",
                // the promise framework and the store's catalogue — dbo-pdi
                // imports the catalogue to name promises in refusals (#140)
                "dbo.promise.jar", "dbo.promises.jar",
                "dbo.pdi.jar",
                // the provisioning door the manager mounts — imports auth
                // and pdi, imported by the tenant bundle
                "dbo.scim.jar",
                "dbo.policy.jar",
                // the manager wires declared content dependencies —
                // without this bundle the tenant bundle does not resolve
                "dbo.sync.jar",
                // and it mounts the maintenance surface,
                // which is the same kind of requirement: an import nothing
                // exports leaves the tenant runtime unresolved, and the
                // failure reads as the tenant bundle failing to start
                "dbo.maintenance.jar",
                // and the lane surface (#154): the tenant mounts it, so the
                // runner's Lane must be exported to something. Same shape of
                // requirement — an import nothing exports leaves the tenant
                // bundle unresolved, and it reads as the tenant failing
                "dbo.runner.jar")) {
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
    @Proving(DboPromises.CONT_DYNAMIC_TENANT_SERVICES)
    void aSpecFileLightsUpTheWholeChainInContainer() throws Exception {
        assertEquals(Bundle.ACTIVE, tenantBundle.getState());
        BundleContext ctx = framework.getBundleContext();
        // The host's test classpath carries same-named classes, so plain
        // getServiceReferences hides bundle services as incompatible
        // (isAssignableTo across classloaders) — the host observes via
        // getAllServiceReferences, which skips compatibility filtering.
        assertTrue(ctx.getAllServiceReferences(
                "cloud.jengu.dbo.tenant.TenantDatabaseProvisioner", null).length >= 1);

        // Each face bundle announces the version it serves, and the wiring
        // resolves under that code rather than choosing between versions it
        // was compiled against (R6). Installing a bundle is how a container
        // learns a version; there is no list to edit.
        ServiceReference<?>[] faces = ctx.getAllServiceReferences(
                "cloud.jengu.dbo.fhir.common.FhirVersion", null);
        assertTrue(faces != null && faces.length >= 3,
                "the installed faces must announce themselves: "
                        + (faces == null ? 0 : faces.length));
        for (String code : List.of("r4", "r5", "r6")) {
            ServiceReference<?>[] one = ctx.getAllServiceReferences(
                    "cloud.jengu.dbo.fhir.common.FhirVersion", "(fhir.version=" + code + ")");
            assertTrue(one != null && one.length == 1,
                    "exactly one face must serve " + code);
        }

        // drop a spec: the manager provisions and registers the service set
        Files.writeString(dir.resolve("konteiner.json"), """
                {"code":"konteiner","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");

        String base = "http://127.0.0.1:" + httpPort + "/t/konteiner/fhir";
        HttpClient http = HttpClient.newHttpClient();
        // A LIVENESS wait, not a budget. What it asserts is that a spec file
        // alone brings a tenant up in a container — and how long that takes on
        // a shared CI runner is not this test's subject: the comparable flow in
        // EmbeddedContainerIT measured 81s there while this gave up at 60,
        // which made a passing assertion a coin flip on machine speed.
        //
        // The budget that IS a subject lives in ServerDistIT, where a cold
        // start is measured against a number somebody chose. Two waits, two
        // meanings; only one of them should fail when a runner is busy.
        long deadline = System.currentTimeMillis() + 180_000;
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
