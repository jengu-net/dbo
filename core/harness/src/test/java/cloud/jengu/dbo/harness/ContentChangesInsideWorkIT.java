package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.HandlingRefusedException;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import cloud.jengu.dbo.work.WorkScopedStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content changes inside a piece of work, and the work says which versions it
 * made (#82).
 *
 * <p>The point is not that a change is visible — history has it and audit names
 * who. It is that the change belongs to something, so the account of what
 * happened is one record rather than an assembly job over two that were never
 * designed to agree.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentChangesInsideWorkIT {

    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "record";

    static PgObjectStore engine;
    static ObjectStore store;
    static Runs runs;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ContentChangesInsideWorkIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        EnvelopeExtractor nothing = (type, payload) -> new cloud.jengu.dbo.core.api.Envelope();
        List<TypeRegistration> declarations = new ArrayList<>(List.of(
                // a clinical type, declared as changing only inside work
                new TypeRegistration("Observation", "r4", IdentityClass.INTERNAL, Set.of(),
                        Handling.operational().underARun(), nothing, List.of()),
                // and one that is written on its own account, as configuration is
                new TypeRegistration("Setting", "r4", IdentityClass.INTERNAL, Set.of(),
                        Handling.operational(), nothing, List.of())));
        declarations.addAll(WorkModel.registrations());
        engine = new PgObjectStore(ds, declarations);
        runs = new Runs(engine);
        store = new WorkScopedStore(engine, runs);
    }

    @AfterEach
    void down() {
        Caller.clear();
    }

    private static byte[] observation(String value) {
        return ("{\"resourceType\":\"Observation\",\"valueString\":\"" + value + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Run work(String key) {
        return runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/" + key,
                List.of(WorkModel.DOMAIN));
    }

    @Test
    @DisplayName("a change that belongs to nothing is refused, and the refusal says why")
    @Proving(DboPromises.PROC_CONTENT_CHANGES_INSIDE_WORK)
    void aChangeOutsideWorkIsRefused() {
        HandlingRefusedException refused = assertThrows(HandlingRefusedException.class,
                () -> store.put(PutRequest.create("Observation", observation("orphan"))));

        assertTrue(refused.getMessage().contains("under-a-run"), refused.getMessage());
        assertTrue(refused.getMessage().contains("belongs to a piece of work"),
                "the refusal has to name the rule, or it reads as the store being broken: "
                        + refused.getMessage());

        // and a type written on its own account is unaffected: configuration,
        // credentials and bookkeeping do not belong to anybody's task
        assertEquals(1, store.put(PutRequest.create("Setting",
                "{\"quiet\":true}".getBytes(StandardCharsets.UTF_8))).versionId());
    }

    @Test
    @DisplayName("a run names the versions it produced, so the change and the work are one record")
    @Proving(DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED)
    void aRunNamesWhatItProduced() {
        Run work = work("named");
        Caller.setRun(work.key());
        PutResult first = store.put(PutRequest.create("Observation", observation("first")));
        PutResult second = store.put(PutRequest.create("Observation", observation("second")));

        Run after = runs.byKey(work.key()).orElseThrow();
        assertEquals(2, after.produced().counted());
        assertTrue(after.produced().complete(), "it named everything it made");
        assertTrue(after.produced().versions().contains("Observation/" + first.id() + "/1"),
                after.produced().toString());
        assertTrue(after.produced().versions().contains("Observation/" + second.id() + "/1"));
    }

    @Test
    @DisplayName("a run too large to enumerate keeps a high-water mark and says it is not "
            + "complete")
    @Proving(DboPromises.PROC_A_RUN_NAMES_WHAT_IT_PRODUCED)
    void aLargeRunKeepsAWatermark() {
        Run work = work("large");
        Caller.setRun(work.key());
        int made = Runs.NAMED_VERSIONS + 5;
        for (int i = 0; i < made; i++) {
            store.put(PutRequest.create("Observation", observation("bulk-" + i)));
        }

        Run after = runs.byKey(work.key()).orElseThrow();
        assertEquals(made, after.produced().counted(), "it counted everything");
        assertEquals(Runs.NAMED_VERSIONS, after.produced().versions().size(),
                "and named what a reader can page through");
        assertFalse(after.produced().complete(),
                "a run that stopped naming has to say so, or a far side reading only the "
                        + "named ones would believe it had everything");
        assertTrue(after.produced().watermark().containsKey("Observation"),
                "past the cap it keeps the high-water mark, which a cursor can start from: "
                        + after.produced());
    }

    @Test
    @DisplayName("a bulk path is a run rather than an exemption")
    @Proving(DboPromises.PROC_CONTENT_CHANGES_INSIDE_WORK)
    void aBulkPathIsARun() {
        // What an import or a replication apply does: open a run, write under
        // it, and appear in the same list as everything else.
        Run importing = work("import");
        Caller.setRun(importing.key());
        store.put(PutRequest.create("Observation", observation("imported")));
        Caller.clearRun();
        runs.closed(runs.byKey(importing.key()).orElseThrow());

        assertTrue(runs.matching(PROCESS, STEP, null, 50).stream()
                        .anyMatch(run -> run.key().endsWith("/import")),
                "an import that is a run appears where every other run appears");
        assertEquals(1, runs.byKey(importing.key()).orElseThrow().produced().counted());
    }
}
