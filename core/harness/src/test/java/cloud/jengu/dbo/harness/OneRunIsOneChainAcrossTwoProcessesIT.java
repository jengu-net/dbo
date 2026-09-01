package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.telemetry.Label;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run claimed in one process and performed in another is one causal chain,
 * and the work is the only thing that can join it.
 *
 * <p>Nothing else can. The far end is a process this store does not run, so no
 * workflow engine knows both halves; and the telemetry seam carries counters
 * and durations, which cannot express causality at all. So the context travels
 * beside the work, the way a correlation from another system already does.
 *
 * <p><b>Carried, never minted.</b> A store that invented a context whenever it
 * saw none would root a second chain that looks authoritative and is not,
 * detaching every run from the trace its caller already had. Absence is
 * therefore an answer, not a gap to fill.
 *
 * <p>Driven over a real HTTP lane rather than in-process, because the claim is
 * precisely that it survives the boundary — and the wire is walked from the
 * record's own components, so a field that fails to travel fails here.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneRunIsOneChainAcrossTwoProcessesIT {

    /** A W3C traceparent: the shape a collector expects, opaque to this store. */
    private static final String UPSTREAM =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private static final String TENANT = "ahel";
    private static final String PROCESS = "dbo.lab";
    private static final String STEP = "validate-traced";

    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static Runs runs;
    static Lane remote;

    @BeforeAll
    void up() throws Exception {
        Path dir = Files.createTempDirectory("dbo-trace-lane");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("OneRunIsOneChainAcrossTwoProcessesIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        runs = new Runs(manager.runtime(TENANT).orElseThrow().engine());

        String secret = "bench-secret";
        manager.authority(TENANT).ensureClient("bench", secret, List.of("work/" + PROCESS + "." + STEP));
        remote = HttpLane.to(laneUri, () -> token("bench", secret), TENANT, "bench",
                new Executor("bench", "1.0", "cloud.jengu.test", Scope.BASELINE));
        // Nothing installed this step, so it reaches the catalogue the way a
        // remote participant's does — as an introduction over the lane.
        remote.introduce(cloud.jengu.dbo.core.process.StepDeclaration.of(
                PROCESS + "." + STEP, "1.0", WorkModel.DOMAIN));
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

    @Test
    @DisplayName("one poll, two runs: the traced one arrives with its context and the "
            + "untraced one arrives without a context invented for it")
    @Proving(DboPromises.PROC_TRACE_RIDES_THE_LANE)
    void theContextCrossesAndAbsenceSurvivesToo() {
        Run traced = runs.traced(runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/traced",
                List.of(WorkModel.DOMAIN)), UPSTREAM);
        runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/untraced",
                List.of(WorkModel.DOMAIN));
        assertEquals(UPSTREAM, traced.traceContext().orElseThrow(),
                "the store did not record the context it was handed");

        // One poll for both, because a participant's cursor is its own: two
        // polls would leave the second asserting over work the first had
        // already taken, and passing for the wrong reason.
        List<Run> arrived = remote.poll(Set.of(STEP), 50);

        Run overTheWire = arrived.stream().filter(run -> run.key().endsWith("/traced"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the traced run never reached the lane; arrived: " + arrived));
        assertEquals(UPSTREAM, overTheWire.traceContext().orElseThrow(),
                "the far end was handed work with no context, so its spans have nothing to "
                        + "hang under and the chain ends at the process boundary");

        Run untraced = arrived.stream().filter(run -> run.key().endsWith("/untraced"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the untraced run never reached the lane; arrived: " + arrived));
        assertTrue(untraced.traceContext().isEmpty(),
                "a run nobody traced came back carrying a context, so one was minted here — "
                        + "and a caller's real chain would have been replaced by a root of "
                        + "our own that looks authoritative and is not");
    }

    @Test
    @DisplayName("a trace context is an identifier, so it is never a metric dimension")
    @Proving(DboPromises.PROC_TRACE_RIDES_THE_LANE)
    void itIsNeverALabel() {
        for (Label label : Label.values()) {
            String name = label.name().toLowerCase(Locale.ROOT);
            assertTrue(!name.contains("trace") && !name.contains("correlation")
                            && !name.contains("key"),
                    "the telemetry labels gained '" + label.name() + "'. An identifier as a "
                            + "metric dimension gives every run its own time series — which "
                            + "is why a run's key and a foreign correlation are absent too. "
                            + "It belongs on a span's id fields and nowhere near a counter.");
        }
    }

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            HttpResponse<String> issued = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + manager.port()
                                    + "/t/" + TENANT + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(issued.body());
            if (!m.find()) {
                throw new IllegalStateException("no token: " + issued.body());
            }
            return m.group(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
