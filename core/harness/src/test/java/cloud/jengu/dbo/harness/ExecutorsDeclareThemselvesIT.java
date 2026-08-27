package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.ExecutorCandidate;
import cloud.jengu.dbo.work.ExecutorResolution;
import cloud.jengu.dbo.work.Participation;
import cloud.jengu.dbo.work.Resolution;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.ScopeClass;
import cloud.jengu.dbo.work.StepGrant;
import cloud.jengu.dbo.work.Work;
import cloud.jengu.dbo.work.WorkModel;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant is a store plus the participants that hold its steps (#78).
 *
 * <p>Over a real store and feed, because the claim is that resolution sees a
 * participant nothing installed here knows about, and stops seeing it when its
 * cursor goes quiet — and a cursor is the one thing a fake cannot honestly
 * have.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutorsDeclareThemselvesIT {

    private static final String PROCESS = "dbo.lab.result";
    private static final String STEP = "validate";

    /**
     * A step per test. Declarations are records in one store, so a participant
     * declared by one test is a candidate in every later one — which is true of
     * the system and unhelpful in a test that is about which candidate wins.
     */
    private static String stepFor(String test) {
        return STEP + "-" + test;
    }
    private static final Scope EE = Scope.zone("ee");
    private static final List<Scope> CHAIN = List.of(Scope.BASELINE, EE,
            Scope.organisation("hogwarts"));

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static Runs runs;
    static PgChangeFeed feed;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ExecutorsDeclareThemselvesIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
        feed = new PgChangeFeed(ds, WorkModel.DOMAIN);
    }

    private static Declarations declarations(Duration patience) {
        return new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN), patience);
    }

    private static Declarations.Declared remote(String step, String name, Scope scope,
            String consumer) {
        return new Declarations.Declared(PROCESS, step, name, "3.2", "the.hospital", scope,
                consumer);
    }

    private static Work work(String step) {
        return Work.of(PROCESS, step, "DiagnosticReport/1");
    }

    @Test
    @DisplayName("a participant nothing installed here knows about is a candidate, because it "
            + "said so")
    @Proving(DboPromises.PROC_EXECUTOR_DECLARES_ITSELF)
    void aRemoteParticipantIsACandidate() {
        String step = stepFor("remote");
        Declarations declarations = declarations(Duration.ofMinutes(5));
        declarations.declare(remote(step, "their-lis", EE, "participant.their-lis"));

        Resolution resolution = new ExecutorResolution(declarations::candidates)
                .resolve(StepGrant.of(PROCESS, step).overridableBy(ScopeClass.ZONE),
                        CHAIN, List.of(), work(step));

        assertTrue(resolution.automated(), "a tenant is a store plus the participants that "
                + "hold its steps, and nothing was installed here for this one");
        assertEquals("their-lis", resolution.executor().name());
        assertEquals("the.hospital", resolution.executor().provider(),
                "and the provider, because a provider can vanish");
    }

    @Test
    @DisplayName("a local implementation and a remote participant resolve deterministically, "
            + "and the run says which and why")
    @Proving(DboPromises.PROC_RUN_NAMES_WHAT_RAN_IT)
    void localAndRemoteResolveByTheChain() {
        String step = stepFor("both");
        Declarations declarations = declarations(Duration.ofMinutes(5));
        declarations.declare(remote(step, "their-lis", EE, "participant.their-lis"));
        Executor local = new Executor("built-in", "1.0", "cloud.jengu.dbo", Scope.BASELINE);

        Resolution resolution = new ExecutorResolution(() -> {
            List<ExecutorCandidate> candidates = new ArrayList<>(declarations.candidates());
            candidates.add(new ExecutorCandidate() {
                @Override
                public Executor executor() {
                    return local;
                }

                @Override
                public boolean willTake(Work work) {
                    return true;
                }
            });
            return candidates;
        }).resolve(StepGrant.of(PROCESS, step).overridableBy(ScopeClass.ZONE), CHAIN,
                List.of(), work(step));

        assertEquals("their-lis", resolution.executor().name(),
                "the zone is more local than the baseline, and the step admits a zone");

        Run run = runs.pipeline(PROCESS, step, PROCESS + "/" + step + "/report-1",
                List.of(WorkModel.DOMAIN));
        Run recorded = runs.selected(run, Scope.organisation("hogwarts"), resolution.executor());
        assertEquals("the.hospital",
                runs.byKey(recorded.key()).orElseThrow().assignment().executor().provider(),
                "per-zone behaviour has to be explicable a year later");
    }

    @Test
    @DisplayName("a declaration whose participant has gone quiet stops being a candidate, and "
            + "a caught-up one does not")
    @Proving(DboPromises.PROC_PRESENCE_IS_DERIVED)
    void silenceWithWorkWaitingIsAbsence() throws Exception {
        String step = stepFor("quiet");
        String consumer = "participant.gone-away";
        Declarations declarations = declarations(Duration.ofMillis(1));
        Declarations.Declared quiet = remote(step, "gone-away", EE, consumer);
        declarations.declare(quiet);
        Participation participant = new Participation(runs, feed, consumer, Set.of(step),
                quiet.executor());
        participant.poll(500); // read to the head: this is what caught up means

        assertTrue(declarations.present(quiet));
        assertTrue(declarations.present(quiet),
                "a caught-up participant's cursor does not move either, and silence with "
                        + "nothing waiting is not absence");

        // Now there is work, and it stays unread.
        runs.pipeline(PROCESS, step, PROCESS + "/" + step + "/unread", List.of(WorkModel.DOMAIN));
        declarations.present(quiet); // the sighting the patience runs from

        // Polled, not slept. Absence needs three things to be true at once —
        // the cursor has not moved, there IS work unread, and the patience has
        // elapsed — and only the third is about time. The second waits on the
        // pipeline's write reaching the feed, which a fixed sleep races: this
        // failed on CI and passed here, which is what that race looks like.
        //
        // Polling converges rather than resetting the clock, because a call
        // re-sights only when the cursor MOVES, and a quiet participant's does
        // not.
        boolean absent = false;
        for (long deadline = System.currentTimeMillis() + 5_000;
                System.currentTimeMillis() < deadline; Thread.sleep(10)) {
            if (!declarations.present(quiet)) {
                absent = true;
                break;
            }
        }
        assertTrue(absent,
                "declared and not answering is a different sentence from nothing declared");
        assertTrue(new ExecutorResolution(declarations::candidates)
                        .resolve(StepGrant.of(PROCESS, step).overridableBy(ScopeClass.ZONE),
                                CHAIN, List.of(), work(step))
                        .reason().contains("no executor"),
                "and resolution stops seeing it");

        // It comes back by doing what a participant does, not by saying so.
        participant.poll(500);
        assertTrue(declarations.present(quiet), "presence is the cursor moving, and it moved");
    }

    @Test
    @DisplayName("an operator can tell nothing-declared from declared-and-not-answering")
    void theTwoSilencesAreDifferent() throws Exception {
        Declarations declarations = declarations(Duration.ofMillis(1));
        assertTrue(declarations.known().stream().noneMatch(known ->
                known.declared().name().equals("never-declared")));

        String step = stepFor("silences");
        Declarations.Declared silent = remote(step, "silent-one", EE, "participant.silent-one");
        declarations.declare(silent);
        runs.pipeline(PROCESS, step, PROCESS + "/" + step + "/waiting", List.of(WorkModel.DOMAIN));
        declarations.present(silent);
        Thread.sleep(20);

        assertTrue(declarations.known().stream().anyMatch(known ->
                        known.declared().name().equals("silent-one") && !known.present()),
                "the map has to answer for participants, including the ones not answering: "
                        + declarations.known());
    }
}
