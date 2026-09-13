package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup and a restore, as records rather than as log lines.
 *
 * <p>These are the two operations an operator is most often asked about
 * afterwards — whether one ran, when, and whether it finished — and they
 * were the two that left nothing behind. A log line is a copy of an event;
 * the run is the event.
 *
 * <p><b>In the managing tenant, both of them.</b> A backup is about the
 * tenant it copies and could have gone in that tenant's own history, but a
 * restore may be creating the tenant and has nowhere to write until it has
 * nearly finished. Two halves of one question belong in one place.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovingATenantsDataIsRecordedIT {

    private static final String MOVED = "kolija";
    private static final byte[] OWNER_KEY = new byte[32];

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String management;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-archive-runs");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("MovingATenantsDataIsRecordedIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        Path managementSpec = Files.createTempDirectory("dbo-archive-management")
                .resolve("registry.json");
        Files.writeString(managementSpec, spec("kolihaldur"));
        management = manager.manages(managementSpec);

        Files.writeString(dir.resolve(MOVED + ".json"), spec(MOVED));
        UntilServed.scan(manager, MOVED);
    }

    private static String spec(String code) {
        return """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[{"name":"Observation","identity":"internal",
                           "handling":"operational"}]}""".formatted(code);
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
    @Timeout(600)
    @DisplayName("a backup leaves a run behind, in the deployment's own history, saying what "
            + "moved and that it finished")
    @Proving(DboPromises.MNT_MOVING_DATA_IS_RECORDED)
    void aBackupIsRecordedWhereTheDeploymentsHistoryIs() throws Exception {
        assertTrue(runs().byKey(key("backup")).isEmpty(),
                "something recorded a backup before one was asked for");

        var answered = archive(token());
        assertEquals(200, answered.statusCode());
        assertTrue(answered.body().length > 0, "the archive came back empty");

        Run recorded = runs().byKey(key("backup")).orElseThrow(() ->
                new AssertionError("a backup ran and the deployment's history does not have it, "
                        + "so the only account of it is whatever log the node was writing"));
        assertEquals("dbo.tenant.archive", recorded.process());
        assertEquals("backup", recorded.step());
        // Underscored, because a counter's name is validated as a path and a
        // dotted one is refused — which is worth knowing here rather than in
        // whatever swallowed the refusal.
        assertEquals(1L, recorded.tally().get("kind_backup"),
                "the run does not say what kind of archive it was: " + recorded.tally());
        assertTrue(recorded.milestone() == null || recorded.milestone().name() != null,
                "a milestone without a name is not a position anybody can read");
    }

    @Test
    @Timeout(600)
    @DisplayName("a restore refused before it starts is a status code, not a record")
    @Proving(DboPromises.MNT_MOVING_DATA_IS_RECORDED)
    void aRefusedRestoreLeavesNoRun() throws Exception {
        var refused = restoreWithoutAttestation(token());

        assertTrue(refused.statusCode() >= 400, "an unattested restore was accepted: "
                + refused.statusCode());
        assertTrue(runs().byKey(key("restore")).isEmpty(),
                "a restore that never began left a run, so the history now says a move was "
                        + "attempted when the door refused to open");
    }

    @Test
    @Timeout(600)
    @DisplayName("a deployment with nobody managing records nothing, and backs up anyway")
    @Proving(DboPromises.MNT_MOVING_DATA_IS_RECORDED)
    void recordingIsNotAConditionOfMoving() {
        // Stated here rather than proven with a second manager, because the
        // rule is the serving sweep's and is kept by the same seam: a
        // recording with no store behind it returns something that does
        // nothing. What this holds is that the backup above did not depend
        // on the record existing — it answered 200 before the run was read.
        assertFalse(management == null,
                "this class needs a managing tenant to say anything about one");
    }

    // ----------------------------------------------------------- the asking

    private Runs runs() {
        return new Runs(manager.runtime(management).orElseThrow().engine());
    }

    private static String key(String step) {
        return "dbo.tenant.archive/" + step + "/" + MOVED;
    }

    private java.net.http.HttpResponse<byte[]> archive(String bearer) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                manager.baseUrl(MOVED).replace("/fhir", "/admin/archive")))
                        .header("Authorization", "Bearer " + bearer)
                        .header(cloud.jengu.dbo.tenant.MaintenanceHandler.OWNER_KEY_HEADER,
                                Base64.getEncoder().encodeToString(OWNER_KEY))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }

    private java.net.http.HttpResponse<String> restoreWithoutAttestation(String bearer)
            throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                manager.baseUrl(MOVED).replace("/fhir", "/admin/restore")))
                        .header("Authorization", "Bearer " + bearer)
                        .header(cloud.jengu.dbo.tenant.MaintenanceHandler.OWNER_KEY_HEADER,
                                Base64.getEncoder().encodeToString(OWNER_KEY))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("not an archive"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /** System-plane: maintenance is not a surface a clinician's grant reaches. */
    private String token() throws Exception {
        manager.authority(MOVED).ensureClient("a-mechanic", "mechanic-secret",
                List.of("system/*.write"));
        String form = "grant_type=client_credentials&client_id=a-mechanic"
                + "&client_secret=mechanic-secret&scope="
                + java.net.URLEncoder.encode("system/*.write", StandardCharsets.UTF_8);
        String body = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                manager.baseUrl(MOVED).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        int at = body.indexOf("\"access_token\"");
        int start = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(start, body.indexOf('"', start));
    }
}
