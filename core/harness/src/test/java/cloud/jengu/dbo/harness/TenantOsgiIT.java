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
    private static final String OPS_TOKEN = "osgi-it-ops";

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
        // The operator surface, so a failure here can tell the two silences
        // apart: a manager that never started answers nothing at all, and one
        // that started and could not bring a tenant up answers with the
        // tenant's state. Without it both read as "no services, 404".
        config.put("dbo.tenant.ops.token", OPS_TOKEN);

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

    /**
     * What the container looks like right now, for a failure to carry.
     *
     * <p>A bring-up that dies inside the container is this project's
     * signature failure — the bundle is ACTIVE, the tenant never serves, and
     * from the host the two are one silence. A bundle that resolved but is
     * not ACTIVE, or a missing per-tenant service, is the difference between
     * "a busy runner was slow" and "the wiring is wrong", and only one of
     * those is worth re-running.
     */
    private static String inTheContainer(BundleContext ctx) {
        StringBuilder report = new StringBuilder("bundles=[");
        for (Bundle bundle : ctx.getBundles()) {
            report.append(bundle.getSymbolicName()).append(':')
                    .append(stateOf(bundle.getState())).append(' ');
        }
        report.append("] tenantServices=");
        try {
            ServiceReference<?>[] stores = ctx.getAllServiceReferences(
                    "cloud.jengu.dbo.fhir.common.FhirStoreFacade", null);
            report.append(stores == null ? 0 : stores.length);
        } catch (org.osgi.framework.InvalidSyntaxException e) {
            report.append("unreadable");
        }
        // The manager's own account. No answer at all means it never started
        // — a different fault entirely from a tenant that would not come up,
        // and the one the bundle states cannot show, because the bundle is
        // ACTIVE either way.
        report.append(" runtimeState=").append(runtimeState());
        return report.toString();
    }

    /** What the container's own operator surface says, or why it said nothing. */
    private static String runtimeState() {
        try {
            HttpResponse<String> answer = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + httpPort + "/runtime/tenants"))
                            .header("Authorization", "Bearer " + OPS_TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return answer.statusCode() + " " + answer.body();
        } catch (Exception noAnswer) {
            return "no answer (" + noAnswer + ") — the manager may never have started";
        }
    }

    private static String stateOf(int state) {
        return switch (state) {
            case Bundle.UNINSTALLED -> "UNINSTALLED";
            case Bundle.INSTALLED -> "INSTALLED";
            case Bundle.RESOLVED -> "RESOLVED";
            case Bundle.STARTING -> "STARTING";
            case Bundle.STOPPING -> "STOPPING";
            case Bundle.ACTIVE -> "ACTIVE";
            default -> "state-" + state;
        };
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20_000);
        }
    }

    @Test
    // Longer than the liveness wait below, deliberately. The two used to be
    // the same number, so a slow bring-up tripped the JUnit timeout at the
    // very moment the loop's own assertion was about to speak — and a
    // timeout says nothing at all, where the assertion names what the
    // container was actually showing. Whichever fires, it should be the one
    // that carries a diagnosis.
    @Timeout(480)
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
        // Waits on the two answers separately, which is what the container's
        // own state makes possible. A tenant still COMING UP is a slow runner
        // and nothing else — this test says in its own words that how long
        // bring-up takes here is not its subject — so it is given room. A
        // tenant that reports FAILED will never come up however long anybody
        // waits, so waiting is only a slower way to say so.
        //
        // Both were one 180-second wait before, which made the fast answer
        // slow and the slow answer a false red: this failed twice on CI with
        // the tenant still coming up, and would have passed with more room.
        long deadline = System.currentTimeMillis() + 420_000;
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
            if (runtimeState().contains("\"state\":\"failed\"")) {
                break;
            }
            Thread.sleep(250);
        }
        assertEquals(200, status, "the tenant endpoint must come up from the spec file alone; "
                + inTheContainer(ctx));

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
