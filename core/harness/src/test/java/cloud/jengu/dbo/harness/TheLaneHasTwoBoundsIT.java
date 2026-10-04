package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lane's two bounds, deliberately different: declarations by type, and
 * patient data by work — with what a run produced travelling beside it.
 *
 * <p>Two real runtimes of one tenant, as the by-work proof has them. A
 * declaration written on the cloud arrives on the worker byte for byte, filed
 * under its source and never revoked; an observation produced inside a run
 * on the worker arrives on the cloud the same way; a type not asked for does
 * not cross; and a type the lane does not admit — a person — is refused by
 * name, which is what a worker holding no register of people rests on.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheLaneHasTwoBoundsIT {

    private static final String PROCESS = "dbo.lab";
    private static final String STEP = "assay";
    private static final Set<String> TRAVELS = Set.of(PROCESS);
    private static final Set<String> DECLARATIONS = Set.of("CodeSystem", "Observation");

    static PgObjectStore cloudStore;
    static PgObjectStore workerStore;
    static Runs cloudRuns;
    static Runs workerRuns;
    static Lanes cloud;
    static Lanes worker;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("TheLaneHasTwoBoundsIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE bounds_cloud");
            st.execute("CREATE DATABASE bounds_worker");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        PGSimpleDataSource cloudDs = ds(base + "bounds_cloud");
        PGSimpleDataSource workerDs = ds(base + "bounds_worker");
        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.internal("Patient"),
                FhirTypeConfig.internal("CodeSystem"),
                FhirTypeConfig.internal("Observation")));
        List<cloud.jengu.dbo.core.api.TypeRegistration> declarations =
                new ArrayList<>(personality.registrations());
        declarations.addAll(WorkModel.registrations());
        declarations.addAll(LaneModel.registrations());
        declarations.addAll(PlacementModel.registrations());
        cloudStore = new PgObjectStore(cloudDs, declarations);
        workerStore = new PgObjectStore(workerDs, declarations);
        cloudRuns = new Runs(cloudStore);
        workerRuns = new Runs(workerStore);
        // Both content feeds: a declared set spans them, since a code system
        // travels on the one a face moves on and an observation does not.
        String defs = Domains.DEFINITIONS;
        cloud = new Lanes(cloudStore, new PgChangeFeed(cloudDs, WorkModel.DOMAIN), cloudRuns,
                "cloud", null, new PgChangeFeed(cloudDs, R4Personality.DOMAIN),
                new PgChangeFeed(cloudDs, defs), DECLARATIONS);
        worker = new Lanes(workerStore, new PgChangeFeed(workerDs, WorkModel.DOMAIN), workerRuns,
                "worker", null, new PgChangeFeed(workerDs, R4Personality.DOMAIN),
                new PgChangeFeed(workerDs, defs), DECLARATIONS);
    }

    @Test
    @DisplayName("a declaration on the cloud arrives on the worker byte for byte, filed under its "
            + "source, and a type not asked for does not cross")
    @Proving(DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS)
    void declarationsTravelByType() {
        byte[] codeSystem = ("{\"resourceType\":\"CodeSystem\",\"url\":\"urn:meristem:assays\","
                + "\"status\":\"active\",\"content\":\"complete\",\"concept\":[{\"code\":\"hba1c\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        String id = cloudStore.put(PutRequest.create("CodeSystem", codeSystem)).id();
        String patient = cloudStore.put(PutRequest.create("Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Kask\"}]}"
                        .getBytes(StandardCharsets.UTF_8))).id();

        Lanes.Batch batch = cloud.outbound("worker", 500, TRAVELS, Set.of("CodeSystem"));
        assertTrue(batch.items().stream().anyMatch(i -> i.copy() && "CodeSystem".equals(i.typeName())),
                "the declaration travels as a copy: " + batch.items());
        assertTrue(batch.items().stream().noneMatch(i -> "Patient".equals(i.typeName())),
                "a type not asked for does not cross");
        Lanes.Applied applied = worker.apply("cloud", batch);
        assertEquals(List.of(), applied.refused());
        cloud.sent("worker", batch);

        StoredObject arrived = workerStore.get("CodeSystem", id).orElseThrow();
        assertArrayEquals(codeSystem, arrived.payload(), "byte for byte");
        assertEquals("cloud", worker.copiedFrom("CodeSystem", id).orElseThrow(),
                "filed under its source: why this worker holds it is answerable");
        assertTrue(workerStore.get("Patient", patient).isEmpty(), "the person did not cross");

        // Never revoked by work: a declaration is not work-bound.
        Lanes.Revoked revoked = worker.revoke();
        assertTrue(workerStore.get("CodeSystem", id).isPresent(), "kept: " + revoked);

        // The lane resumes from where it left off on the content feed: a
        // second ask carries nothing new.
        Lanes.Batch again = cloud.outbound("worker", 500, TRAVELS, Set.of("CodeSystem"));
        assertTrue(again.items().stream().noneMatch(Lanes.Item::copy), "nothing new: " + again.items());
    }

    @Test
    @DisplayName("a type the lane does not admit is refused by name — a worker holding no "
            + "register of people rests on it")
    @Proving(DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS)
    void aTypeNotAdmittedIsRefusedByName() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> cloud.outbound("worker", 500, TRAVELS, Set.of("Patient")));
        assertTrue(refused.getMessage().contains("does not carry 'Patient' by type")
                        && refused.getMessage().contains("CodeSystem"),
                "named, with what it does admit: " + refused.getMessage());
    }

    @Test
    @DisplayName("an observation produced inside a run on the worker arrives on the cloud "
            + "with the run, filed under the worker, and outlives the run")
    @Proving({DboPromises.PROC_THE_LANE_HAS_TWO_BOUNDS,
            DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED})
    void producedVersionsTravelWithTheirRun() {
        Run run = workerRuns.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/produced-here",
                List.of(WorkModel.DOMAIN));
        Run held = workerRuns.claim(run, new Executor("assayer", "1.0", "cloud.jengu.test",
                Scope.BASELINE), Instant.now().plusSeconds(60)).orElseThrow();
        byte[] observation = ("{\"resourceType\":\"Observation\",\"status\":\"final\","
                + "\"code\":{\"text\":\"HbA1c\"},\"valueQuantity\":{\"value\":6.1}}")
                .getBytes(StandardCharsets.UTF_8);
        String id = workerStore.put(PutRequest.create("Observation", observation)).id();
        Run named = workerRuns.produced(held, "Observation", id, 1);
        assertEquals(List.of("Observation/" + id + "/1"), named.produced().versions());
        workerRuns.closed(named);

        Lanes.Batch batch = worker.outbound("cloud", 500, TRAVELS);
        assertTrue(batch.items().stream().anyMatch(i -> i.copy()
                        && "Observation".equals(i.typeName()) && id.equals(i.id())),
                "what the run produced travels with it: " + batch.items());
        Lanes.Applied applied = cloud.apply("worker", batch);
        assertEquals(List.of(), applied.refused());

        StoredObject arrived = cloudStore.get("Observation", id).orElseThrow();
        assertArrayEquals(observation, arrived.payload());
        assertEquals("worker", cloud.copiedFrom("Observation", id).orElseThrow(),
                "filed under the worker that produced it");
        assertTrue(cloudRuns.byKey("worker" + Lanes.MIRROR_SEPARATOR + run.key()).isPresent(),
                "and the run it came with is mirrored beside it");
        cloud.revoke();
        assertTrue(cloudStore.get("Observation", id).isPresent(),
                "the run is closed and the observation stays: what a run produced outlives it");
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }
}
