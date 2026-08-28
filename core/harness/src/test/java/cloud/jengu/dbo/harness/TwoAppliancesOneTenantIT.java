package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.sync.LaneModel;
import cloud.jengu.dbo.sync.Lanes;
import cloud.jengu.dbo.sync.PlacementModel;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tenant on two appliances, and the lane between them (#80, ADR 0062).
 *
 * <p>Two real stores, because every claim here is about what one appliance does
 * with what the other sent: applying twice, applying out of order, applying a
 * position from a lane that no longer exists. A single store would prove the
 * arithmetic and none of the properties.
 *
 * <p>There is no channel in this test, deliberately — a batch is handed from
 * one side to the other by a variable, which is exactly what a connector does
 * with a socket in the middle.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoAppliancesOneTenantIT {

    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "validate";
    private static final Set<String> TRAVELS = Set.of(PROCESS);

    static PgObjectStore cloudStore;
    static PgObjectStore edgeStore;
    static Runs cloudRuns;
    static Runs edgeRuns;
    static Lanes cloud;
    static Lanes edge;
    static PGSimpleDataSource cloudDs;
    static PGSimpleDataSource edgeDs;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("TwoAppliancesOneTenantIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE appliance_cloud");
            st.execute("CREATE DATABASE appliance_edge");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        cloudDs = ds(base + "appliance_cloud");
        edgeDs = ds(base + "appliance_edge");

        // The same tenant's declarations on both appliances: same types, same
        // handling, same face. Only the local settings differ.
        List<cloud.jengu.dbo.core.api.TypeRegistration> declarations =
                new ArrayList<>(new R4Personality(List.of(
                        FhirTypeConfig.internal("Patient"))).registrations());
        declarations.addAll(WorkModel.registrations());
        declarations.addAll(LaneModel.registrations());
        declarations.addAll(PlacementModel.registrations());

        cloudStore = new PgObjectStore(cloudDs, declarations);
        edgeStore = new PgObjectStore(edgeDs, declarations);
        cloudRuns = new Runs(cloudStore);
        edgeRuns = new Runs(edgeStore);
        cloud = new Lanes(cloudStore, new PgChangeFeed(cloudDs, WorkModel.DOMAIN), cloudRuns,
                "cloud");
        edge = new Lanes(edgeStore, new PgChangeFeed(edgeDs, WorkModel.DOMAIN), edgeRuns, "edge");
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }

    private static String patient(String name) {
        return "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"" + name + "\"}]}";
    }

    @Test
    @DisplayName("work travels with the data it names, data first, and nothing else goes with it")
    @Proving(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY)
    void workTravelsWithWhatItNames() {
        PutResult subject = cloudStore.put(PutRequest.create("Patient",
                patient("Kask").getBytes(StandardCharsets.UTF_8)));
        PutResult bystander = cloudStore.put(PutRequest.create("Patient",
                patient("Tamm").getBytes(StandardCharsets.UTF_8)));
        Run work = cloudRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/one",
                List.of(WorkModel.DOMAIN));
        cloudRuns.item(work, "Patient/" + subject.id(), Failure.RECORD, "needs a second read");
        // and the appliance's own housekeeping, which is nobody else's business
        cloudRuns.sweep("dbo.tenant.serving", "serve", "deployment", List.of(WorkModel.DOMAIN));

        Lanes.Batch batch = cloud.outbound("edge", 500, TRAVELS);
        Lanes.Applied applied = edge.apply("cloud", batch);
        cloud.sent("edge", batch);

        assertTrue(applied.applied() > 0, applied.toString());
        assertTrue(edgeStore.get("Patient", subject.id()).isPresent(),
                "the patient the work is about travelled with it");
        assertTrue(edgeStore.get("Patient", bystander.id()).isEmpty(),
                "and the one it is not about did not — 'all Patients to every bench' is the "
                        + "outcome this exists to prevent");
        assertTrue(batch.items().stream().filter(item -> !item.work()).findFirst().isPresent());
        assertFalse(batch.items().get(0).work(),
                "data before work, so nothing arrives pointing at something absent");
        assertTrue(edgeStore.select(Criteria.of(WorkModel.TYPE)).stream()
                        .map(stored -> new String(stored.payload(), StandardCharsets.UTF_8))
                        .noneMatch(payload -> payload.contains("dbo.tenant.serving")),
                "an appliance's account of its own bring-up is not the other side's business");
    }

    @Test
    @DisplayName("a re-sent batch applies once, and a late one does not put the old version back")
    @Proving({DboPromises.FEED_IDEMPOTENT_DELIVERY,
            DboPromises.PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE})
    void replayAndReorderAreSafe() {
        PutResult subject = cloudStore.put(PutRequest.create("Patient",
                patient("Esimene").getBytes(StandardCharsets.UTF_8)));
        Run work = cloudRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/two",
                List.of(WorkModel.DOMAIN));
        cloudRuns.item(work, "Patient/" + subject.id(), Failure.RECORD, "first");
        Lanes.Batch first = cloud.outbound("edge", 500, TRAVELS);
        edge.apply("cloud", first);
        cloud.sent("edge", first);

        // the same batch again, as a connector re-sends after a link drop
        Lanes.Applied again = edge.apply("cloud", first);
        assertEquals(0, again.applied(), "a re-sent batch applies once: " + again);

        // a newer version, then the older one arriving late behind it
        cloudStore.put(new PutRequest("Patient", subject.id(), subject.versionId(),
                patient("Teine").getBytes(StandardCharsets.UTF_8)));
        Run second = cloudRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/three",
                List.of(WorkModel.DOMAIN));
        cloudRuns.item(second, "Patient/" + subject.id(), Failure.RECORD, "second");
        Lanes.Batch newer = cloud.outbound("edge", 500, TRAVELS);
        edge.apply("cloud", newer);

        edge.apply("cloud", first); // the old one, arriving after the new one

        assertTrue(new String(edgeStore.get("Patient", subject.id()).orElseThrow().payload(),
                        StandardCharsets.UTF_8).contains("Teine"),
                "a batch arriving out of order must not regress a record");
    }

    @Test
    @DisplayName("a peer resuming a position from a restored copy is refused, and the refusal "
            + "names the epoch")
    @Proving(DboPromises.PROC_LANE_EPOCH)
    void aRestoredPeerIsRefused() {
        Lanes.Batch batch = cloud.outbound("edge", 500, TRAVELS);
        Lanes.Batch fromAnotherLife = new Lanes.Batch("01a00000-0000-7000-8000-000000000000",
                batch.from(), batch.cursor(), batch.items());

        Lanes.Applied refused = edge.apply("cloud", fromAnotherLife);

        assertEquals(0, refused.applied());
        assertTrue(refused.refused().get(0).contains("epoch"), refused.toString());
        assertTrue(refused.refused().get(0).contains("resumed from a copy"),
                "the refusal has to say what happened, or somebody restores again: " + refused);
    }

    @Test
    @DisplayName("a mirrored run is filed under the appliance that authored it, beside the "
            + "local one of the same name")
    @Proving(DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE)
    void aMirroredRunDoesNotReplaceTheLocalOne() {
        // both appliances run the same task, which is the point of one tenant
        cloudRuns.sweep(PROCESS, "apply", "zone/ee", List.of(WorkModel.DOMAIN));
        edgeRuns.sweep(PROCESS, "apply", "zone/ee", List.of(WorkModel.DOMAIN));

        Lanes.Batch batch = cloud.outbound("edge", 500, TRAVELS);
        edge.apply("cloud", batch);
        cloud.sent("edge", batch);

        assertTrue(edgeRuns.byKey(PROCESS + "/apply/zone/ee").isPresent(),
                "the edge's own run of the task is untouched");
        assertTrue(edgeRuns.byKey("cloud" + Lanes.MIRROR_SEPARATOR + PROCESS + "/apply/zone/ee")
                        .isPresent(),
                "and the cloud's arrives beside it, filed under the appliance that authored it");
    }

    @Test
    @DisplayName("what came with a task leaves when the task closes, and what other work still "
            + "needs stays")
    @Proving(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY)
    void closingTheTaskRemovesWhatCameWithIt() {
        PutResult leaving = cloudStore.put(PutRequest.create("Patient",
                patient("Lahkuja").getBytes(StandardCharsets.UTF_8)));
        PutResult staying = cloudStore.put(PutRequest.create("Patient",
                patient("Jaaja").getBytes(StandardCharsets.UTF_8)));
        Run closing = cloudRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/closing",
                List.of(WorkModel.DOMAIN));
        cloudRuns.item(closing, "Patient/" + leaving.id(), Failure.RECORD, "one");
        cloudRuns.item(closing, "Patient/" + staying.id(), Failure.RECORD, "two");
        Run staying_ = cloudRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/staying",
                List.of(WorkModel.DOMAIN));
        cloudRuns.item(staying_, "Patient/" + staying.id(), Failure.RECORD, "still open");

        Lanes.Batch batch = cloud.outbound("edge", 500, TRAVELS);
        edge.apply("cloud", batch);
        cloud.sent("edge", batch);
        assertTrue(edgeStore.get("Patient", leaving.id()).isPresent());

        // the work is done — the cards too, because a card still open is
        // somebody still needing the record it names
        cloudRuns.items(cloudRuns.byKey(closing.key()).orElseThrow())
                .forEach(card -> cloudRuns.closed(card));
        cloudRuns.closed(cloudRuns.byKey(closing.key()).orElseThrow());
        Lanes.Batch closure = cloud.outbound("edge", 500, TRAVELS);
        edge.apply("cloud", closure);
        cloud.sent("edge", closure);

        Lanes.Revoked revoked = edge.revoke();

        assertTrue(revoked.removed() >= 1, revoked.toString());
        assertTrue(edgeStore.get("Patient", leaving.id()).isEmpty(),
                "work-driven arrival implies work-driven expiry, or a bench accumulates a "
                        + "register one task at a time");
        assertTrue(edgeStore.get("Patient", staying.id()).isPresent(),
                "and what another open run still names stays: " + revoked);
    }

    /**
     * A weekend's absence, converged in bounded rounds (#80).
     *
     * <p>Every other test here hands over one batch big enough to hold
     * everything, which proves what a batch contains and nothing about
     * catching up. An appliance that was away comes back to a feed longer
     * than any one batch, and most of what accumulated is the other side's
     * own housekeeping — so the interesting question is whether it converges
     * at all, and whether it does so without dragging over what it holds no
     * work for.
     *
     * <p>The hazard this is really aimed at: a lane filters non-travelling
     * work <em>after</em> reading the feed, so a stretch of housekeeping
     * longer than the batch bound yields a batch with no items in it. A
     * connector that stopped there would never move its cursor past the
     * housekeeping and would re-read the same stretch for ever — an idle lane
     * and a stalled one look identical from outside.
     */
    @Test
    @DisplayName("a peer that was away for a weekend converges in bounded rounds, and brings "
            + "over nothing it holds no work for")
    @Proving(DboPromises.PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY)
    void anAbsentPeerConverges() {
        // A long stretch of the cloud's own housekeeping — more of it in a row
        // than one batch can hold — with real work scattered through it.
        List<String> subjects = new ArrayList<>();
        for (int weekend = 0; weekend < 6; weekend++) {
            for (int housekeeping = 0; housekeeping < 4; housekeeping++) {
                cloudRuns.sweep("dbo.tenant.serving", "serve", "deployment-" + weekend + "-"
                        + housekeeping, List.of(WorkModel.DOMAIN));
            }
            PutResult subject = cloudStore.put(PutRequest.create("Patient",
                    patient("Weekend-" + weekend).getBytes(StandardCharsets.UTF_8)));
            subjects.add(subject.id());
            Run work = cloudRuns.pipeline(PROCESS, STEP,
                    PROCESS + "/" + STEP + "/weekend-" + weekend, List.of(WorkModel.DOMAIN));
            cloudRuns.item(work, "Patient/" + subject.id(), Failure.RECORD, "away");
        }

        // The peer comes back and drains, a small batch at a time. `sent` is
        // called on every round including an empty one, because accepting an
        // empty batch is how a lane gets past what it does not carry.
        int rounds = 0;
        long appliedTotal = 0;
        // Caught up is "nothing more is arriving", and it has to be measured
        // that way: the cursor is no help, because the lane's own bookkeeping
        // — its Lane record, the peer's Placement rows — is registered in the
        // very domain whose feed it reads, so every round writes an event into
        // its own input and the cursor never stops moving. An empty batch is
        // no help either: a stretch of the other side's housekeeping produces
        // batches with no items while the lane is still genuinely draining. So
        // a connector drains until nothing has arrived for a few rounds, which
        // is what this does.
        int quiet = 0;
        for (; rounds < 60 && quiet < 3; rounds++) {
            Lanes.Batch batch = cloud.outbound("edge", 5, TRAVELS);
            long arrived = batch.isEmpty() ? 0 : edge.apply("cloud", batch).applied();
            cloud.sent("edge", batch);
            appliedTotal += arrived;
            quiet = arrived == 0 ? quiet + 1 : 0;
        }

        assertTrue(rounds < 60, "the lane drained rather than re-reading for ever, in "
                + rounds + " rounds");
        assertTrue(appliedTotal > 0, "and something actually arrived: " + appliedTotal);
        for (String subject : subjects) {
            assertTrue(edgeStore.get("Patient", subject).isPresent(),
                    "every subject the accumulated work names arrived: " + subject);
        }
        assertTrue(edgeStore.select(Criteria.of(WorkModel.TYPE)).stream()
                        .map(stored -> new String(stored.payload(), StandardCharsets.UTF_8))
                        .noneMatch(payload -> payload.contains("dbo.tenant.serving")),
                "and a weekend of the other side's housekeeping stayed at home");

        // Converged means converged: nothing is dragged over a second time.
        Lanes.Batch nothingLeft = cloud.outbound("edge", 500, TRAVELS);
        assertEquals(0, edge.apply("cloud", nothingLeft).applied(),
                "a caught-up peer replays nothing, even asked for a batch big enough to carry "
                        + "the whole weekend again: " + nothingLeft.items().size() + " items");
    }

    @Test
    @DisplayName("both sides keep what the other said it had reached")
    void markersAreEchoed() {
        Lanes.Batch batch = cloud.outbound("edge", 500, TRAVELS);
        edge.apply("cloud", batch);
        cloud.mark("edge", batch.cursor());

        assertEquals(batch.cursor(), cloud.lane("edge").orElseThrow().theirMarker(),
                "where the far side is has to be a store fact rather than the channel's "
                        + "opinion about its own delivery");
    }
}
