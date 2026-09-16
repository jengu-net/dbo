package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A host holds a lane over the store's own stream by installing a bundle,
 * and writes no code to do it.
 *
 * <p>Both ends of this carrier were built, proven and shipped, and nothing in
 * any deployment ever constructed the client half: the tenant mounted the
 * door, and the lane that speaks to it existed only where a test built one.
 * That is the state this repository's characteristic failure arrives in — the
 * carrier reads as whole from either end read on its own, because the door is
 * mounted and the lane passes its tests.
 *
 * <p>So this stands where a host stands. A real framework, the runner and the
 * stream bundle installed into it, a driver bundle contributing a step, and
 * the deployment's configuration — the substrate it already shares with the
 * store, the tenant it holds a lane into, the participant it enrolled as and
 * the private halves of that enrolment. Nothing here constructs a lane,
 * attaches one, or names {@code StreamLane} at all. If the work is performed
 * and lands on the tenant's own store, the only thing that can have carried
 * it is a lane the container built from what it was told.
 *
 * <p>The driver's own lane is switched off, deliberately. It carries a
 * standing in-memory one so the whiteboard can be proved with no store within
 * reach; left on here it would answer first and the carrier under test would
 * never be asked.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AHostHoldsALaneByInstallingABundleIT {

    private static final String TENANT = "streamhostbundle";
    /** The step the driver bundle contributes, declared here by the tenant. */
    private static final String STEP = "probe.assay.report";
    private static final StepDeclaration REPORT =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen");
    private static final String PERFORMED = "dbo.probe.performed";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static Framework framework;
    static ObjectStore engine;
    static Runs runs;
    static Run offered;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        System.clearProperty(PERFORMED);
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-stream-host");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AHostHoldsALaneByInstallingABundleIT"),
                postgres.getUsername(), postgres.getPassword());
        String substrateUrl =
                SharedPostgres.urlFor("AHostHoldsALaneByInstallingABundleIT_substrate");
        PGSimpleDataSource substrate = new PGSimpleDataSource();
        substrate.setUrl(substrateUrl);
        substrate.setUser(postgres.getUsername());
        substrate.setPassword(postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.substrate(substrate);
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);

        // The participant this host enrolled as, and the work it is entitled
        // to. The private halves go to the container as configuration and
        // never leave it.
        KeyPair sealing = KeyWrap.newParticipantKeyPair();
        KeyPair signing = SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("assayer", "assayer-secret",
                List.of("work/" + STEP), ParticipantKey.of(sealing.getPublic()),
                SigningKey.of(signing.getPublic()));
        // The declaration arrives the way declarations arrive — over a lane,
        // from whoever introduced the step. Not the host's job, and not this
        // test pretending it is.
        manager.authority(TENANT).ensureClient("courier", "courier-secret",
                List.of("work/" + STEP));
        URI laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        HttpLane.to(laneUri, () -> token("courier", "courier-secret"), TENANT, "courier",
                new Executor("courier", "1.0", "cloud.jengu.test", Scope.BASELINE))
                .introduce(REPORT);
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"specimen\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        offered = runs.of(REPORT, RunKind.PIPELINE, "held-by-a-host",
                Map.of("specimen", "Basic/" + specimen));

        Map<String, String> config = new HashMap<>();
        config.put("org.osgi.framework.storage", FelixStorage.directory("dbo-stream-host-felix"));
        config.put("org.osgi.framework.storage.clean", "onFirstInit");
        config.put("dbo.runner.poll.millis", "50");
        // What a host is told, and the whole of it. The substrate under the
        // name the serving side reads it by; the rest says who is asking.
        config.put("dbo.substrate.url", substrateUrl);
        config.put("dbo.substrate.user", postgres.getUsername());
        config.put("dbo.substrate.password", postgres.getPassword());
        config.put("dbo.lane.tenants", TENANT);
        config.put("dbo.lane.participant", "assayer");
        config.put("dbo.lane.sealing.key",
                Base64.getEncoder().encodeToString(sealing.getPrivate().getEncoded()));
        config.put("dbo.lane.signing.key",
                Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()));
        config.put("dbo.lane.executor.version", "1.0");
        config.put("dbo.lane.executor.provider", "cloud.jengu.test");
        // The driver keeps its step and gives up its lane: see the class note.
        config.put("dbo.probe.lane", "false");

        framework = ServiceLoader.load(FrameworkFactory.class).findFirst().orElseThrow()
                .newFramework(config);
        framework.start();
        BundleContext ctx = framework.getBundleContext();
        // The distribution's logging arrangement, in its order.
        ctx.installBundle("file:" + System.getProperty("spifly.jar"));
        ctx.installBundle("file:" + System.getProperty("slf4j.api.jar"));
        ctx.installBundle("file:" + System.getProperty("dbo.logging.jar")).start();
        // A host, and nothing more. No store bundle, no face, no personality:
        // what is absent is part of the claim, because a participant reaching
        // the store over the substrate holds none of them.
        for (String jar : List.of("dbo.core.jar", "dbo.work.jar", "dbo.telemetry.jar",
                "dbo.runner.jar", "dbo.stream.jar")) {
            ctx.installBundle("file:" + System.getProperty(jar)).start();
        }
        ctx.installBundle("file:" + System.getProperty("dbo.step.probe.jar")).start();
    }

    @AfterAll
    void down() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(30_000);
        }
        System.clearProperty(PERFORMED);
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("a container told where the substrate is, which tenant it holds a lane into "
            + "and who it enrolled as performs that tenant's work — with no code of its own, "
            + "and no connection opened into the tenant")
    @Proving({DboPromises.PROC_A_LANE_OVER_THE_STREAM,
            DboPromises.PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS})
    void installingTheBundleIsTheWholeHost() {
        // Its own wait. Nothing nudges the runner: it polls on its own thread
        // over a lane the container built, and a nudge from here would be the
        // test driving the thing it is claiming the container drives.
        String performed = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (performed == null && System.nanoTime() < deadline) {
            performed = System.getProperty(PERFORMED);
            if (performed == null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        assertNotNull(performed,
                "the step never ran, so a container configured as a host holds no lane — and "
                        + "the carrier's client half is still something only a test builds");
        assertEquals(offered.key(), performed,
                "something ran, but not the work this tenant offered: " + performed);

        // And it landed on the tenant's own store, which is the half a
        // property cannot tell us: the run closed, on the head the lane left.
        Run closed = null;
        deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            closed = runs.byKey(offered.key()).orElseThrow();
            if (!closed.open()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(Holder.NOBODY, closed.holder(), "still held: " + closed);
        assertEquals(Map.of("reported", 1L), closed.tally(),
                "the outcome the driver reported came home over the same channel: " + closed);
    }

    @Test
    @DisplayName("the host container holds the runner and the carrier and no store, so work "
            + "that did not arrive would be a carrier that does not carry rather than a "
            + "bundle that never started")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void theContainerIsWhatAHostIs() {
        Map<String, Integer> states = new java.util.TreeMap<>();
        for (Bundle bundle : framework.getBundleContext().getBundles()) {
            if (bundle.getBundleId() != 0) {
                states.put(bundle.getSymbolicName(), bundle.getState());
            }
        }
        states.forEach((name, state) -> assertTrue(
                state == Bundle.ACTIVE || state == Bundle.RESOLVED,
                name + " neither resolved nor started: " + states));
        assertEquals(Bundle.ACTIVE, states.get("cloud.jengu.dbo.stream"), states.toString());
        assertEquals(Bundle.ACTIVE, states.get("cloud.jengu.dbo.runner"), states.toString());
        assertEquals(Bundle.ACTIVE, states.get("cloud.jengu.dbo.probe"), states.toString());
        assertTrue(states.keySet().stream().noneMatch(name -> name.contains("postgres")
                        || name.contains("fhir") || name.contains("tenant")),
                "a host holds no store and no face; this container holds " + states.keySet());
    }

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + manager.port()
                                            + "/t/" + TENANT + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
            assertTrue(m.find(), body);
            return m.group(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
