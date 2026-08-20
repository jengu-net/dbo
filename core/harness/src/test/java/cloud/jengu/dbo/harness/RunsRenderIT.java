package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.fhir.element.R6FhirVersion;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.fhir.r5.R5FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Failure;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The face renders a run; the engine never spells one (#70).
 *
 * <p>Over real definitions, because the claim is that the document is valid in
 * every version this face serves — and a projection that is merely well-formed
 * JSON passes any test that does not ask a version about it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunsRenderIT {

    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("RunsRenderIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()));
    }

    private static DomainFace faceOf(String code) {
        return switch (code) {
            case "r4" -> R4FhirVersion.INSTANCE.face();
            case "r5" -> R5FhirVersion.INSTANCE.face();
            default -> new R6FhirVersion().face();
        };
    }

    @Test
    @DisplayName("a run and its items render as Tasks and outcomes, valid in every version served")
    void aRunRendersAsTasks() {
        for (String code : List.of("r4", "r5", "r6")) {
            Run ingest = runs.pipeline("dbo.terminology.ingest", "import",
                    "dbo.terminology.ingest/import/" + code, List.of(code));
            runs.item(ingest, "CodeSystem/colours", Failure.RECORD, "concept without a code");
            Run tallied = runs.tally(ingest, Map.of("read", 46L, "applied", 45L));
            Run held = runs.held(tallied, cloud.jengu.dbo.work.Holder.PERSON);

            DomainFace face = faceOf(code);
            String document = face.require(RecordProjection.class)
                    .project(runs.asRecord(held))
                    .orElseThrow(() -> new AssertionError(code + " renders nothing"));

            assertTrue(document.contains("\"resourceType\":\"Task\""), code + ": " + document);
            assertTrue(document.contains("\"code\":\"person\""),
                    code + ": the holder is the field an operator reads first — " + document);
            assertTrue(document.contains("\"resourceType\":\"OperationOutcome\""),
                    code + ": the item's failure is an outcome — " + document);
            assertTrue(document.contains("\"valueInteger\":46"),
                    code + ": the tally travels — " + document);
            assertTrue(document.contains("CodeSystem/colours"),
                    code + ": the item names what it was about — " + document);

            assertEquals(List.of(), validation(face, document),
                    code + " refused a document its own face built: " + document);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> validation(DomainFace face, String document) {
        Payloads payloads = face.require(Payloads.class);
        Object parsed = payloads.read("Bundle", document.getBytes(StandardCharsets.UTF_8));
        return payloads.validate("Bundle", parsed);
    }

    @Test
    @DisplayName("what ran a step, and what it was refused, are on the rendered run too")
    void theExecutorAndTheNoteRender() {
        for (String code : List.of("r4", "r5", "r6")) {
            Run running = runs.pipeline("dbo.claims.adjudication", "adjudicate",
                    "dbo.claims.adjudication/adjudicate/" + code, List.of(code));
            Run chosen = runs.selected(running, Scope.organisation("hogwarts"),
                    new Executor("ee-adjudicator", "2.1", "cloud.jengu.insurance",
                            Scope.zone("ee")),
                    "step dbo.claims.adjudication/adjudicate is not overridable, and "
                            + "organisation:hogwarts tried");

            DomainFace face = faceOf(code);
            String document = face.require(RecordProjection.class)
                    .project(runs.asRecord(chosen)).orElseThrow();

            assertTrue(document.contains("ee-adjudicator 2.1 (cloud.jengu.insurance)"),
                    code + ": which behaviour ran is the question a year later — " + document);
            assertTrue(document.contains("organisation:hogwarts tried"),
                    code + ": a refused override is a fact about somebody's rule — " + document);
            assertEquals(List.of(), validation(face, document),
                    code + " refused a document its own face built: " + document);
        }
    }

    @Test
    @DisplayName("a run over a domain this face does not claim renders nothing, and says so")
    void anUnclaimedDomainRendersNothing() {
        Run credentials = runs.sweep("dbo.identity.rotation", "rotate", "keys",
                List.of("identity"));
        assertFalse(faceOf("r4").require(RecordProjection.class)
                        .project(runs.asRecord(credentials)).isPresent(),
                "no face claims identity, so a run over it renders nowhere — an empty document "
                        + "would read like a run with nothing in it");
    }
}
