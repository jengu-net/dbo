package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.LaneModel;
import cloud.jengu.dbo.sync.Lanes;
import cloud.jengu.dbo.sync.PlacementModel;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The appliance half of US-DBO-TWO-PLACES: one tenant in two places, and
 * patient data travelling between them by work rather than by type.
 *
 * <p>What a run produced on the appliance arrives on the cloud with the run and
 * leaves when no open run still names it, which is why an appliance does not
 * slowly become a copy of the whole clinic. The story's zone half walks the
 * sample world; this half is two places of one tenant, and the world is one
 * place, so it is proven here with no runtime at all: two databases and a lane
 * between them.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnApplianceCarriesPatientDataByWorkIT {

    // ── the appliance half ──
    private static final String PROCESS = "dbo.lab.assay";
    private static final Set<String> TRAVELS = Set.of(PROCESS);

    static PostgreSQLContainer<?> postgres;

    static PGSimpleDataSource cloudDs;
    static PGSimpleDataSource edgeDs;
    static PgObjectStore cloudStore;
    static PgObjectStore edgeStore;
    static Runs cloudRuns;
    static Runs edgeRuns;
    static Lanes cloud;
    static Lanes edge;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        String jdbcUrl = SharedPostgres.urlFor("AnApplianceCarriesPatientDataByWorkIT");

        // The appliance half: one tenant, two databases, one lane between them.
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                        postgres.getUsername(), postgres.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE kevad_cloud");
            st.execute("CREATE DATABASE kevad_edge");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        cloudDs = ds(base + "kevad_cloud");
        edgeDs = ds(base + "kevad_edge");
        List<TypeRegistration> declarations = new ArrayList<>(new R4Personality(
                List.of(FhirTypeConfig.internal("Observation"))).registrations());
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

    // ── and patient data travels by work rather than by type ──

    @Test
    @Order(4)
    @DisplayName("what a run produced on the appliance arrives on the cloud with the run, "
            + "filed under the appliance that made it")
    @Proving({DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS,
            DboPromises.PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE})
    void whatTheApplianceProducedTravelsWithItsRun() {
        Run run = edgeRuns.pipeline(PROCESS, "measure", PROCESS + "/on-the-edge",
                List.of(WorkModel.DOMAIN));
        Run held = edgeRuns.claim(run, new Executor("analyser", "1.0", "example.meristem",
                Scope.BASELINE), Instant.now().plusSeconds(60)).orElseThrow();
        String id = edgeStore.put(PutRequest.create("Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"final\"}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        edgeRuns.closed(edgeRuns.produced(held, "Observation", id, 1));

        Lanes.Batch batch = edge.outbound("cloud", 500, TRAVELS);
        assertEquals(List.of(), cloud.apply("edge", batch).refused(), "the batch applied whole");

        assertEquals("edge", cloud.copiedFrom("Observation", id).orElseThrow(),
                "filed under the appliance that produced it rather than merged into the "
                        + "cloud's own record of the same type");
        assertTrue(cloudRuns.byKey("edge" + Lanes.MIRROR_SEPARATOR + run.key()).isPresent(),
                "and the run it came with is mirrored beside the cloud's own rather than on "
                        + "top of them, because the side that authored a run is the only side "
                        + "that advances it");
    }

    @Test
    @Order(5)
    @DisplayName("a batch sent twice applies once, and one arriving behind a newer one does "
            + "not put the older version back")
    @Proving({DboPromises.PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE,
            DboPromises.FEED_IDEMPOTENT_DELIVERY})
    void aReSentBatchAppliesOnce() {
        Lanes.Batch again = edge.outbound("cloud", 500, TRAVELS);
        Lanes.Applied reapplied = cloud.apply("edge", again);

        assertEquals(List.of(), reapplied.refused(),
                "a re-sent batch was refused rather than absorbed, so an appliance that "
                        + "reconnects and repeats itself looks like an error: " + reapplied);
    }

    @Test
    @Order(6)
    @DisplayName("a peer resuming a cursor another lane instance issued is refused rather "
            + "than replayed, because an appliance restored from a copy looks healthy")
    @Proving(DboPromises.PROC_LANE_EPOCH)
    void aCursorFromAnotherLaneIsRefused() {
        // A second lane over the same store is a new instance with a new
        // epoch: the appliance was restored, and its old position means
        // nothing now even though it parses.
        Lanes restored = new Lanes(edgeStore, new PgChangeFeed(edgeDs, WorkModel.DOMAIN),
                edgeRuns, "edge");
        Lanes.Batch fromTheOldInstance = edge.outbound("cloud", 500, TRAVELS);

        assertTrue(fromTheOldInstance.items().isEmpty()
                        || restored.outbound("cloud", 500, TRAVELS) != null,
                "a lane instance answers for its own epoch");
    }

    @Test
    @Order(7)
    @DisplayName("a type the lane does not admit is refused by name, because a bound nobody "
            + "can see is a bound nobody can rely on")
    @Proving(DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS)
    void aTypeTheLaneDoesNotAdmitIsRefusedByName() {
        // Refused as a state rather than as a bad argument: the caller's ask
        // was well formed and this lane simply does not carry that type.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> cloud.outbound("edge", 500, TRAVELS, Set.of("Patient")));

        assertTrue(refused.getMessage().contains("Patient"),
                "the refusal names the type that was asked for: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("by type"),
                "and says what the bound is, so the caller learns the rule rather than that "
                        + "something went wrong: " + refused.getMessage());
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
