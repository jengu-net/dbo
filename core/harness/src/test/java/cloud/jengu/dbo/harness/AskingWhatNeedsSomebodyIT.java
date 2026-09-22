package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.asking.Asking;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
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

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The questions an operator asks, answered by the store rather than by the
 * caller.
 *
 * <p>Every question here is scoped to a correlation this class invented, so
 * what it counts is its own work and not whatever else the suite has left in
 * this database. That is the discipline a shared world asks for and it is also
 * the honest shape of the question: a product asks about a case, a batch or a
 * day, never about everything.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskingWhatNeedsSomebodyIT {

    private static final String PROCESS = "asking.example";
    private static final String STEP = "asking.example.weigh";

    /** This class's own thread through the work, so nothing counts a stranger. */
    private static final String CASE = "asking-" + java.util.UUID.randomUUID();

    static Runs runs;
    static Asking asking;

    private static StepDeclaration weigh() {
        return StepDeclaration.of(STEP, "1", WorkModel.DOMAIN);
    }

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AskingWhatNeedsSomebodyIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        ObjectStore store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store, Steps.of(weigh()));
        asking = Asking.at(store);

        // Three runs of one case: one a person has to look at, one an
        // automation is holding, and one finished with.
        Run needsSomebody = runs.correlated(
                runs.pipeline(PROCESS, STEP, CASE + "/a", List.of(WorkModel.DOMAIN)), CASE);
        runs.held(needsSomebody, Holder.PERSON);

        Run running = runs.correlated(
                runs.pipeline(PROCESS, STEP, CASE + "/b", List.of(WorkModel.DOMAIN)), CASE);
        runs.claim(running, new Executor("weigher", "1", "example", Scope.BASELINE),
                java.time.Instant.now().plusSeconds(600));

        Run done = runs.correlated(
                runs.pipeline(PROCESS, STEP, CASE + "/c", List.of(WorkModel.DOMAIN)), CASE);
        runs.closed(runs.claim(done, new Executor("weigher", "1", "example", Scope.BASELINE),
                java.time.Instant.now().plusSeconds(600)).orElseThrow());
    }

    @Test
    @DisplayName("what needs somebody is one question, and the store answers it")
    @Proving(DboPromises.PROC_RUN_HAS_A_RECORD)
    void whatNeedsSomebody() {
        try (Stream<Run> waiting = asking.work().correlated(CASE).heldBy(Holder.PERSON).stream()) {
            List<Run> found = waiting.toList();
            assertEquals(1, found.size(),
                    "the one run automation could not finish is what an operator came for, "
                            + "and a list of runs that worked silently omits it: " + found);
            assertEquals(CASE + "/a", found.get(0).key());
        }
    }

    @Test
    @DisplayName("open is everything not finished with, which is a negation the store can take")
    void openIsEverythingNotDoneWith() {
        try (Stream<Run> open = asking.work().correlated(CASE).open().stream()) {
            List<String> keys = open.map(Run::key).sorted().toList();
            assertEquals(List.of(CASE + "/a", CASE + "/b"), keys,
                    "a closed run came back as open, or an open one did not: " + keys);
        }
    }

    @Test
    @DisplayName("counting does not fetch, and counts the same question")
    void countingDoesNotFetch() {
        Asking.Work thisCase = asking.work().correlated(CASE);

        assertEquals(3, thisCase.count(), "the case has three runs");
        assertEquals(2, thisCase.open().count(), "two of them are still owed by somebody");

        // The question is immutable: narrowing it above did not change it.
        assertEquals(3, thisCase.count(),
                "asking a narrowed question changed the question it was narrowed from");
    }

    @Test
    @DisplayName("nobody is watching until somebody asks to, and then they see the shape of "
            + "the question and never what it was about")
    void theSeamCarriesNamesAndNeverValues() {
        java.util.List<cloud.jengu.dbo.asking.Watching.Asked> seen =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        // Off by default: this one is asked with nobody watching.
        asking.work().correlated(CASE).count();
        assertTrue(seen.isEmpty(), "something was reported to a watcher nobody attached");

        cloud.jengu.dbo.asking.Asking watched = asking.watching(seen::add);

        // A count reports at once; there is nothing to walk.
        assertEquals(3, watched.work().correlated(CASE).count());
        assertEquals(1, seen.size(), "a count did not report");
        assertEquals("work.count", seen.get(0).question());
        assertEquals(List.of("correlated"), seen.get(0).narrowedBy());
        assertEquals(3, seen.get(0).members());

        // A stream reports at the CLOSE, with what it actually produced —
        // which for a caller who stopped early is not what matched.
        try (Stream<Run> some = watched.work().correlated(CASE).open().stream()) {
            assertEquals(1, some.limit(1).count());
        }
        cloud.jengu.dbo.asking.Watching.Asked walked = seen.get(seen.size() - 1);
        assertEquals("work.stream", walked.question());
        assertEquals(List.of("correlated", "open"), walked.narrowedBy());
        assertEquals(1, walked.members(),
                "the walk reported what matched rather than what the caller took");

        // The assertion this seam exists for. The case is an invented id
        // here; in a real screen the same narrowing is a person's number,
        // and a watcher wired to a log would write it down.
        for (cloud.jengu.dbo.asking.Watching.Asked asked : seen) {
            assertTrue(asked.narrowedBy().stream().noneMatch(name -> name.contains(CASE)),
                    "a narrowing's VALUE reached the watcher: " + asked);
            assertTrue(!asked.toString().contains(CASE),
                    "what was asked about is recoverable from what the watcher was told: "
                            + asked);
        }
    }

    @Test
    @DisplayName("and a stranger's work is not in the answer")
    void aStrangersWorkIsNotInTheAnswer() {
        Run elsewhere = runs.correlated(
                runs.pipeline(PROCESS, STEP, "asking-elsewhere-" + java.util.UUID.randomUUID(),
                        List.of(WorkModel.DOMAIN)), "asking-somebody-elses-case");
        assertTrue(elsewhere.key() != null);

        assertEquals(3, asking.work().correlated(CASE).count(),
                "a run filed under another correlation was counted here");
    }
}
