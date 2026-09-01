package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work nobody can render is still work somebody is holding.
 *
 * <p>A run over {@code identity}, {@code audit} or a configuration domain
 * renders to nothing on purpose, which means the surface that shows
 * clinical work cannot show operational work at all. The console reads the
 * records instead — this is the query underneath it, and the claim it has to
 * make is that a card is findable precisely where a face has nothing to say.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnprojectedWorkIsVisibleIT {

    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("UnprojectedWorkIsVisibleIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()));
    }

    @Test
    @DisplayName("a card in an unprojected domain is invisible to every face and findable by "
            + "the console")
    void whatNoFaceRendersIsStillFound() {
        Run rotation = runs.sweep("dbo.identity.rotation", "rotate", "keys", List.of("identity"));
        runs.item(rotation, "credential:hub-signing", Failure.RECORD,
                "the signing key is past its rotation date and nothing can rotate it here");
        Run held = runs.held(rotation, Holder.PERSON);

        assertFalse(R4FhirVersion.INSTANCE.face().require(RecordProjection.class)
                        .project(runs.asRecord(held)).isPresent(),
                "no face claims identity, so a FHIR client cannot be shown this at all");

        List<Run> waiting = runs.matching(null, null, Holder.PERSON, 50);
        assertTrue(waiting.stream().anyMatch(run -> run.key().equals(held.key())),
                "and it is exactly the work an operator opens the console for: " + waiting);
    }

    @Test
    @DisplayName("the filters narrow, and what they narrow to is what somebody asked for")
    void filtersNarrow() {
        runs.held(runs.pipeline("dbo.terminology.ingest", "import",
                "dbo.terminology.ingest/import/console"), Holder.PERSON);
        runs.pipeline("dbo.terminology.ingest", "publish",
                "dbo.terminology.ingest/publish/console");

        assertEquals(1, runs.matching("dbo.terminology.ingest", "import", Holder.PERSON, 50).size());
        assertTrue(runs.matching("dbo.terminology.ingest", null, null, 50).size() >= 2);
        assertTrue(runs.matching("dbo.nothing.at.all", null, null, 50).isEmpty(),
                "a process nobody ran is an empty list, not everything");
    }
}
