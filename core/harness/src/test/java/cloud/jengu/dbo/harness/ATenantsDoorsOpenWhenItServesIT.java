package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantState;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's doors answer once it is serving, and serving is not taken back.
 *
 * <p>A bring-up mounted its surfaces as it went, so a tenant answered 200 at
 * {@code /fhir/metadata} — all a readiness wait asks — while it still had its
 * face to drain and its vocabularies to publish, any of which could fail and
 * roll it back. A client that had started then wrote into a tenant that
 * vanished under it, and read {@code 404 No context found} where a moment ago
 * there had been a tenant.
 *
 * <p>A world of its own, because what is asked is how a bring-up looks from
 * outside while it happens: it is held part-way, at the point its surfaces
 * are mounted, which no shared world could allow.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATenantsDoorsOpenWhenItServesIT {

    private static final String TOKEN = "ATenantsDoorsOpenWhenItServesIT";
    private static final String HELD = "pooratud";
    private static final String ANNOUNCED = "teatatud";
    private static final String ZONE = "vald";
    private static final String MEMBER = "valla-kliinik";

    private Path dir;
    private LocalDatabasePerTenantProvisioner provisioner;
    private TenantRuntimeManager manager;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Holds a bring-up once its surfaces are mounted, for the tenant named here. */
    private final CountDownLatch surfacesMounted = new CountDownLatch(1);
    private final CountDownLatch letItFinish = new CountDownLatch(1);

    /** The host refuses the first announcement of this tenant, and takes the next. */
    private final AtomicBoolean refusedOnce = new AtomicBoolean();

    @BeforeAll
    void up() throws Exception {
        dir = Files.createTempDirectory("dbo-doors");
        provisioner = new LocalDatabasePerTenantProvisioner(SharedPostgres.urlFor(TOKEN),
                SharedPostgres.username(), SharedPostgres.password());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0,
                new TenantRuntimeManager.Listener() {
                    @Override
                    public void tenantUp(TenantRuntimeManager.TenantRuntime runtime) {
                        if (runtime.spec().code().equals(ANNOUNCED)
                                && refusedOnce.compareAndSet(false, true)) {
                            throw new IllegalStateException("the host could not take it yet");
                        }
                    }

                    @Override
                    public void tenantDown(String code) {
                    }
                },
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.addLifecycleListener(TenantPoint.SURFACES,
                "(" + cloud.jengu.dbo.tenant.api.TenantFacts.CODE + "=" + HELD + ")",
                "holds the bring-up part-way", (point, facts) -> {
                    surfacesMounted.countDown();
                    try {
                        letItFinish.await(2, TimeUnit.MINUTES);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    @AfterAll
    void down() {
        letItFinish.countDown();
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("a tenant coming up answers 503 with Retry-After at every door, and 200 once "
            + "it serves")
    @Proving(DboPromises.TEN_A_DOOR_OPENS_WHEN_ITS_TENANT_SERVES)
    void aTenantComingUpSaysSoAtItsDoors() throws Exception {
        declare(HELD, null);
        CompletableFuture<java.util.Set<String>> scanning =
                CompletableFuture.supplyAsync(manager::scanOnce);
        try {
            assertTrue(surfacesMounted.await(2, TimeUnit.MINUTES),
                    "the bring-up never reached its surfaces: " + manager.troubles());
            for (String door : java.util.List.of("/fhir/metadata", "/oidc/.well-known/jwks.json")) {
                HttpResponse<String> meanwhile = get(HELD, door);
                assertEquals(503, meanwhile.statusCode(), door + " answered a tenant still "
                        + "coming up: " + meanwhile.statusCode());
                assertTrue(meanwhile.headers().firstValue("Retry-After").isPresent(),
                        door + " said 503 without saying when to ask again");
            }
            assertNotEquals(TenantState.State.SERVING, stateOf(HELD),
                    "the tenant is reported serving while its doors say it is not");
        } finally {
            letItFinish.countDown();
        }
        assertTrue(scanning.get(2, TimeUnit.MINUTES).contains(HELD),
                "the tenant did not come up: " + manager.troubles());
        assertEquals(200, get(HELD, "/fhir/metadata").statusCode());
    }

    @Test
    @DisplayName("a step after a tenant serves that fails leaves it serving, degraded with the "
            + "reason, and is retried until it takes")
    @Proving(DboPromises.TEN_SERVING_IS_NOT_TAKEN_BACK_BY_A_LATER_STEP)
    void aLaterStepDegradesRatherThanUnmounts() throws Exception {
        declare(ANNOUNCED, null);
        manager.scanOnce();

        assertTrue(refusedOnce.get(), "the host was never asked, so nothing here failed");
        assertEquals(TenantState.State.DEGRADED, stateOf(ANNOUNCED),
                "a tenant whose announcement failed is not reported as what it is");
        String why = manager.troubles().get(ANNOUNCED);
        assertTrue(why != null && why.contains("the host could not take it yet"),
                "the reason is not beside the state: " + why);
        // Its doors and its storage are the ones it came up with: it answers,
        // and a write lands.
        assertEquals(200, get(ANNOUNCED, "/fhir/metadata").statusCode(),
                "the degraded tenant lost its door");
        manager.runtime(ANNOUNCED).orElseThrow().store().create(
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Teatatud\"}]}");

        manager.scanOnce();
        assertEquals(TenantState.State.SERVING, stateOf(ANNOUNCED),
                "the step it was owed was not retried");
        assertFalse(manager.troubles().containsKey(ANNOUNCED),
                "the reason outlived the step it was about");
    }

    @Test
    @DisplayName("a member of a zone that is not serving waits with nothing built, and comes "
            + "up once the zone does")
    @Proving(DboPromises.TEN_A_DOOR_OPENS_WHEN_ITS_TENANT_SERVES)
    void aMemberWaitsForItsZoneWithNothingBuilt() throws Exception {
        declare(MEMBER, ZONE);
        manager.scanOnce();

        assertEquals(TenantState.State.COMING_UP, stateOf(MEMBER),
                "a member waiting on its zone is reported as something other than waiting: "
                        + manager.troubles().get(MEMBER));
        assertTrue(manager.databaseOf(MEMBER).isEmpty(),
                "the member built its storage before its zone was there to build it from");
        assertEquals(404, get(MEMBER, "/fhir/metadata").statusCode(),
                "the member mounted a door while it waited");

        Files.writeString(dir.resolve(ZONE + ".json"), """
                {"code":"%s","face":"r4","zoneRoot":true,"audit":{"level":"none"},
                 "types":[{"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(ZONE));
        UntilServed.scan(manager, ZONE, MEMBER);
        assertEquals(200, get(MEMBER, "/fhir/metadata").statusCode());
    }

    // ---------------------------------------------------------------- helpers

    private void declare(String code, String zone) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4",%s"audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, zone == null ? "" : "\"zone\":\"" + zone + "\","));
    }

    private TenantState.State stateOf(String code) {
        return manager.tenantStates().stream().filter(state -> state.code().equals(code))
                .map(TenantState::state).findFirst().orElse(null);
    }

    private HttpResponse<String> get(String code, String door) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + manager.port()
                        + "/t/" + code + door)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
