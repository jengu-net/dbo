package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.Lanes;
import cloud.jengu.dbo.sync.http.HttpLanes;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A host that is not the container drives replication.
 *
 * <p>The asymmetry {@code HttpLane} answered for the participation verbs, one
 * layer up: {@code Lanes} takes the store's internals, and the cloud — the
 * side that must produce outbound batches, because declarations flow toward
 * the appliance — is the side whose dbo is a separate deployment. Pull-not-push
 * does not move it, since whoever pulls, the cloud still builds the batch.
 *
 * <p>What matters is that the verbs land on the tenant's <b>real</b> lanes and
 * not on something the surface keeps of its own: an epoch that came back once
 * and was forgotten, or a cursor the store never saw, would pass a shallower
 * test and fail the first time an appliance reconnected.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplicationDrivenOverHttpIT {

    private static final String TENANT = "replhost";
    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "validate";
    private static final Set<String> TRAVELS = Set.of(PROCESS);

    static PGSimpleDataSource ds;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static HttpLanes replication;
    static Runs runs;

    @BeforeAll
    void up() throws Exception {
        dir = Files.createTempDirectory("dbo-tenants-replication");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ReplicationDrivenOverHttpIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);

        String token = token("tenant-bootstrap", provisioner.bootstrapClientSecret(TENANT));
        replication = HttpLanes.to(base(), () -> token, TENANT);
        runs = new Runs(manager.runtime(TENANT).orElseThrow().engine());
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

    private static URI base() {
        return URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/replication");
    }

    @Test
    @DisplayName("the lane a host opens over HTTP is the tenant's own, epoch and cursor kept "
            + "in the store rather than by the surface")
    @Proving(DboPromises.PROC_LANE_EPOCH)
    void theLaneIsTheTenantsOwn() {
        Lanes.Lane opened = replication.open("edge");
        assertNotNull(opened.epoch(), "opening mints an epoch");

        // The same lane, not a new one: an epoch minted per call would let a
        // restored appliance's cursor look valid for ever, which is the one
        // thing the epoch exists to catch.
        assertEquals(opened.epoch(), replication.open("edge").epoch());
        assertEquals(opened.epoch(), replication.lane("edge").orElseThrow().epoch());

        replication.mark("edge", "they-said-here");
        assertEquals("they-said-here", replication.lane("edge").orElseThrow().theirMarker(),
                "where the far side said it had reached is a store fact, not the "
                        + "channel's opinion");
    }

    @Test
    @DisplayName("a batch built over HTTP carries the work the tenant actually holds, and "
            + "accepting it advances the tenant's own cursor")
    @Proving(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY)
    void aBatchIsBuiltFromTheTenantsStore() {
        cloud.jengu.dbo.core.api.PutResult subject =
                manager.runtime(TENANT).orElseThrow().engine().put(
                        cloud.jengu.dbo.core.api.PutRequest.create("Basic",
                                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8)));
        Run work = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/over-http",
                List.of(WorkModel.DOMAIN));
        runs.item(work, "Basic/" + subject.id(), Failure.RECORD, "needs a second read");

        Lanes.Batch batch = replication.outbound("bench", 500, TRAVELS);
        assertFalse(batch.isEmpty(), "the tenant's own work is in it");
        assertTrue(batch.items().stream().anyMatch(item -> item.work()
                        && new String(item.payload(), StandardCharsets.UTF_8)
                                .contains(work.key())),
                "the run this tenant holds travelled: " + batch.items().size() + " items");
        assertEquals(replication.lane("bench").orElseThrow().epoch(), batch.epoch(),
                "carrying the lane's epoch, so a cursor cannot outlive the lane that "
                        + "issued it");

        replication.sent("bench", batch);
        assertEquals(batch.cursor(), replication.lane("bench").orElseThrow().ourCursor(),
                "accepting it moved the tenant's cursor, not a number the surface kept");
    }

    @Test
    @DisplayName("what a peer sends is applied to the tenant's store, filed under the "
            + "appliance that authored it")
    @Proving(DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE)
    void whatAPeerSendsIsApplied() {
        String key = PROCESS + "/" + STEP + "/from-the-bench";
        Lanes.Batch fromElsewhere = new Lanes.Batch(
                replication.open("bench").epoch(), "bench", null,
                List.of(Lanes.Item.work("11111111-1111-7111-8111-111111111111", 1L,
                        Instant.parse("2026-08-30T08:00:00Z"),
                        ("{\"key\":\"" + key + "\",\"process\":\"" + PROCESS + "\",\"step\":\""
                                + STEP + "\",\"kind\":\"pipeline\",\"holder\":\"automation\","
                                + "\"domains\":[\"work\"]}").getBytes(StandardCharsets.UTF_8))));

        Lanes.Applied applied = replication.apply("bench", fromElsewhere);

        assertTrue(applied.refused().isEmpty(), "nothing refused: " + applied.refused());
        assertEquals(1, applied.applied(), applied.toString());
        assertTrue(runs.byKey("bench" + WorkModel.AUTHOR_SEPARATOR + key).isPresent(),
                "filed under the appliance that authored it, in this tenant's own store");

        // Idempotent under replay, through the surface exactly as beneath it.
        assertEquals(0, replication.apply("bench", fromElsewhere).applied(),
                "a re-sent batch applies once");
    }

    @Test
    @DisplayName("replication is the tenant's own act: a credential bounded to steps is "
            + "refused, and one with no participation scope reaches nothing")
    @Proving(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED)
    void replicationIsTheTenantsOwnAct() throws Exception {
        HttpLanes bounded = lanesAs("bench-bounded", "work/" + PROCESS + "." + STEP);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bounded.open("edge"));
        assertTrue(refused.getMessage().contains("bounded to steps"),
                "refused by name rather than served a smaller batch, which would read as a "
                        + "lane that had caught up: " + refused.getMessage());

        HttpLanes reader = lanesAs("reads-only", "system/*.read");
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> reader.open("edge"));
        assertTrue(none.getMessage().contains("participation scope"), none.getMessage());
    }

    private static HttpLanes lanesAs(String clientId, String... scopes) throws Exception {
        String secret = clientId + "-secret";
        manager.authority(TENANT).ensureClient(clientId, secret, List.of(scopes));
        String token = token(clientId, secret);
        return HttpLanes.to(base(), () -> token, TENANT);
    }

    private static String token(String clientId, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + TENANT + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
