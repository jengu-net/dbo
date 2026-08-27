package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.element.R6FhirVersion;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.fhir.r5.R5FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long-running step says where it is (#150).
 *
 * <p>Milestones ride the checkpoint the way events ride a tracing span: the
 * executor asserts only the name, the store derives the position over the
 * step's own declared order, the record keeps it replaced-never-accumulated
 * across release and retake, and the face says it in {@code businessStatus}
 * beside the holder. A service that reports nothing behaves exactly as today.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MilestonesOnTheCheckpointIT {

    private static StepDeclaration ingest(String domain) {
        return StepDeclaration.of("lab.result.ingest", "1.0", domain)
                .reaching("parsed", "validated", "signed");
    }

    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("MilestonesOnTheCheckpointIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()),
                Steps.of(ingest(WorkModel.DOMAIN)));
    }

    @Test
    @DisplayName("the store derives the position over the declared order, refuses a stranger "
            + "by name, and the milestone survives release")
    @Proving({DboPromises.PROC_MILESTONES_ARE_DECLARED,
            DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE})
    void positionIsDerivedAndTheNameIsHeldToTheDeclaration() {
        Run run = runs.of(ingest(WorkModel.DOMAIN), RunKind.PIPELINE, "derives");

        Run reported = runs.milestone(run, "validated", Map.of("read", 12L),
                Instant.now().plusSeconds(60));
        assertEquals(new Run.Milestone("validated", 2, 3), reported.milestone(),
                "the executor asserted only the name; 2 of 3 is the store's derivation");
        assertEquals(12L, reported.tally().get("read"),
                "a milestone is a checkpoint — the counts land exactly as they always did");

        Runs.NotAMilestone refused = assertThrows(Runs.NotAMilestone.class,
                () -> runs.milestone(reported, "polished", Map.of(),
                        Instant.now().plusSeconds(60)));
        assertTrue(refused.getMessage().contains("polished")
                        && refused.getMessage().contains("validated"),
                "a name outside the declared order is refused naming both sides: "
                        + refused.getMessage());

        Run released = runs.released(runs.byKey(run.key()).orElseThrow(), "shift ended");
        assertEquals("validated", released.milestone().name(),
                "released keeps the milestone — the next taker resumes from a fact");

        Run replaced = runs.milestone(released, "signed", Map.of(),
                Instant.now().plusSeconds(60));
        assertEquals(new Run.Milestone("signed", 3, 3), replaced.milestone(),
                "replaced, never accumulated");
    }

    @Test
    @DisplayName("a step that declared no milestones is not narrowed, and one that reports "
            + "nothing carries nothing")
    @Proving({DboPromises.PROC_MILESTONES_ARE_DECLARED,
            DboPromises.PROC_PROGRESS_NAMES_THE_MILESTONE})
    void emptyMeansHasNotSaid() {
        Run bare = runs.pipeline("lab.result", "archive", "lab.result/archive/free",
                List.of(WorkModel.DOMAIN));
        Run reported = runs.milestone(bare, "somewhere", Map.of(),
                Instant.now().plusSeconds(60));
        assertEquals(new Run.Milestone("somewhere", 0, 0), reported.milestone(),
                "recorded verbatim with no position — a completeness nobody declared "
                        + "cannot be derived, only invented");

        Run quiet = runs.of(ingest(WorkModel.DOMAIN), RunKind.PIPELINE, "quiet");
        Run checkpointed = runs.checkpoint(quiet, Map.of("read", 1L),
                Instant.now().plusSeconds(60));
        assertNull(checkpointed.milestone(),
                "a service that reports nothing behaves exactly as today");
    }

    @Test
    @DisplayName("businessStatus says where the work is — holder and milestone, with the "
            + "derived position as its text — valid in every version served")
    @Proving(DboPromises.PROC_TASK_SAYS_WHERE_THE_WORK_IS)
    void theTaskSaysWhereTheWorkIs() {
        for (String code : List.of("r4", "r5", "r6")) {
            Run run = runs.of(ingest(code), RunKind.PIPELINE, "renders-" + code);
            Run reported = runs.milestone(run, "validated", Map.of(),
                    Instant.now().plusSeconds(60));

            DomainFace face = switch (code) {
                case "r4" -> R4FhirVersion.INSTANCE.face();
                case "r5" -> R5FhirVersion.INSTANCE.face();
                default -> new R6FhirVersion().face();
            };
            String document = face.require(RecordProjection.class)
                    .project(runs.asRecord(reported))
                    .orElseThrow(() -> new AssertionError(code + " renders nothing"));

            assertTrue(document.contains("urn:dbo:run:milestone")
                            && document.contains("\"code\":\"validated\"")
                            && document.contains("\"text\":\"validated, 2 of 3\""),
                    code + ": one concept, two codings, and the derived position for the "
                            + "human reader — " + document);

            Payloads payloads = face.require(Payloads.class);
            Object parsed = payloads.read("Bundle", document.getBytes(StandardCharsets.UTF_8));
            assertEquals(List.of(), payloads.validate("Bundle", parsed),
                    code + " refused a document its own face built: " + document);
        }
    }
}
