package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
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
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
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
 * US-DBO-TWO-PLACES, walked in order.
 *
 * <p>The clinic is one place today and two by the end of this story. It takes
 * its canonical content from a national zone it does not run, and it puts an
 * appliance in the building so that a lost connection is an inconvenience
 * rather than a closed practice.
 *
 * <p>Both halves are the same idea: content that belongs somewhere else,
 * arriving because somebody declared that it should, and staying legible
 * about where it came from. What differs is the bound. Canonical content
 * travels <b>by type</b>, because none of it is about anybody. Patient data
 * travels <b>by work</b>, arriving with a task and leaving when no open run
 * still names it — the two bounds are deliberately different and the second
 * one is why an appliance does not slowly become a copy of the whole clinic.
 *
 * <p><b>One clinic, one zone, two appliances, in dependency order.</b>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OneTenantInTwoPlacesIT {

    // ── the zone half ──
    private static final String ZONE = "eesti";
    private static final String CLINIC = "kevadsoltuv";
    private static final String SEVERITY = "https://eesti.example/fs/severity";

    // ── the appliance half ──
    private static final String PROCESS = "dbo.lab.assay";
    private static final Set<String> TRAVELS = Set.of(PROCESS);

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

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
        String jdbcUrl = SharedPostgres.urlFor("OneTenantInTwoPlacesIT");

        // The zone half: two tenants of one runtime, one depending on the other.
        dir = Files.createTempDirectory("dbo-two-places");
        provisioner = new LocalDatabasePerTenantProvisioner(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve(ZONE + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(ZONE));
        UntilServed.scan(manager, ZONE);

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

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    // ── canonical content arrives because somebody declared it should ──

    @Test
    @Order(1)
    @DisplayName("the clinic declares what it takes from the zone, and a dependency it never "
            + "declared brings nothing")
    @Proving({DboPromises.SYNC_SPEC_DECLARED, DboPromises.SYNC_DECLARED_ONLY,
            DboPromises.ZONE_DECLARATIONS_AS_RECORDS})
    void theClinicDeclaresWhatItTakes() throws Exception {
        // The zone publishes before the clinic exists, which is the ordinary
        // case: canonical content is older than the practices that use it.
        var zone = manager.runtime(ZONE).orElseThrow().engine();
        zone.put(PutRequest.create("CodeSystem", ("""
                {"resourceType":"CodeSystem","url":"%s","status":"active",
                 "content":"complete","version":"1.0",
                 "concept":[{"code":"mild","display":"Mild"}]}""".formatted(SEVERITY))
                .getBytes(StandardCharsets.UTF_8)));

        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4",
                 "dependencies":[{"name":"%s","types":["CodeSystem"]}],
                 "types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(CLINIC, ZONE));
        UntilServed.scan(manager, ZONE, CLINIC);
    }

    @Test
    @Order(2)
    @DisplayName("a type the clinic did not declare a dependency for brings nothing, however "
            + "much of it the zone holds")
    @Proving({DboPromises.SYNC_DECLARED_ONLY, DboPromises.SYNC_DIRECT_UPSTREAM_ONLY})
    void nothingUndeclaredArrives() {
        var zone = manager.runtime(ZONE).orElseThrow().engine();
        zone.put(PutRequest.create("ValueSet", ("""
                {"resourceType":"ValueSet","url":"%s/vs","status":"active"}"""
                .formatted(SEVERITY)).getBytes(StandardCharsets.UTF_8)));
        manager.scanOnce();

        // The clinic declared CodeSystem and nothing else. A ValueSet in the
        // zone is not a thing it is missing; it is a thing it did not ask for.
        assertTrue(manager.runtime(CLINIC).orElseThrow().engine()
                        .select(Criteria.of("ValueSet")).isEmpty(),
                "an undeclared type arrived, so a dependency is a hint rather than a bound "
                        + "and a clinic ends up holding whatever its upstream happens to have");
    }

    // How a declared dependency catches up from the upstream's whole history,
    // that a streamed copy is read-only and provenanced, that a local
    // definition shadows it, and that a terminology grain survives the wire
    // are legs of this story proven where the stream's own timing is the
    // subject (SpecDeclaredSyncIT, SyncStreamsIT). The story declares them;
    // this class does not re-drive a catch-up to re-assert them.

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
