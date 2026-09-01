package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Participation;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runner;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The participant that automates nothing.
 *
 * <p>Its whole job is to carry work to wherever the work is actually done and
 * carry the result back, so the interesting claim is that the run afterwards
 * reads as it would if dbo had done the work itself — an integration is not a
 * second kind of history.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AReferenceRunnerCarriesWorkIT {

    private static final String PROCESS = "dbo.lab.result";

    static PGSimpleDataSource ds;
    static Runs runs;
    static Declarations declarations;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AReferenceRunnerCarriesWorkIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        runs = new Runs(new PgObjectStore(ds, WorkModel.registrations()));
        declarations = new Declarations(store(), new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofMinutes(5));
    }

    private static PgObjectStore store() {
        return new PgObjectStore(ds, WorkModel.registrations());
    }

    private static Runner runner(String name, String step, Duration hold) {
        return new Runner(runs, new PgChangeFeed(ds, WorkModel.DOMAIN), declarations,
                new Declarations.Declared(PROCESS, step, name, "1.0", "the.hospital",
                        Scope.zone("ee"), "participant." + name),
                hold).declare();
    }

    private static Run work(String step, String key) {
        return runs.pipeline(PROCESS, step, PROCESS + "/" + step + "/" + key,
                List.of(WorkModel.DOMAIN));
    }

    @Test
    @DisplayName("it carries work to another system and the result back, and the run reads "
            + "like any other")
    void itCarriesWorkOutAndTheResultBack() {
        String step = "validate-carry";
        ConcurrentLinkedQueue<String> theirSystem = new ConcurrentLinkedQueue<>();
        work(step, "carried");
        Runner runner = runner("their-lis", step, Duration.ofMinutes(5));

        List<Run> handled = runner.runOnce(50, run -> {
            // "their system" — a call out, which is the whole of what a
            // transport-only participant does
            theirSystem.add(run.key());
            return Runner.Outcome.done(Map.of("validated", 1L));
        });

        assertEquals(1, handled.size());
        assertEquals(1, theirSystem.size(), "the work went out");
        Run after = runs.byKey(PROCESS + "/" + step + "/carried").orElseThrow();
        assertEquals(1L, after.tally().get("validated"), "and the account came back");
        assertFalse(after.open(), "nothing is owed, and nobody holds it");
        assertEquals(Holder.NOBODY, after.holder());
    }

    @Test
    @DisplayName("what the other system could not do becomes somebody's card, with its reason")
    void aProblemBecomesACard() {
        String step = "validate-problem";
        work(step, "rejected");
        Runner runner = runner("their-lis-2", step, Duration.ofMinutes(5));

        runner.runOnce(50, run -> new Runner.Outcome(Map.of("read", 3L),
                List.of(new Runner.Problem("Specimen/7", Failure.RECORD,
                        "the specimen was received unlabelled")), true));

        Run after = runs.byKey(PROCESS + "/" + step + "/rejected").orElseThrow();
        assertTrue(after.needsAPerson());
        assertTrue(runs.items(after).stream().anyMatch(item ->
                        item.item().reference().equals("Specimen/7")),
                "the run says what somebody has to act on: " + runs.items(after));
    }

    @Test
    @DisplayName("a system that was merely unavailable is handed back, not put in front of "
            + "a person")
    void anUnavailableSystemIsHandedBack() {
        String step = "validate-down";
        work(step, "later");
        Runner runner = runner("their-lis-3", step, Duration.ofMinutes(5));

        runner.runOnce(50, run -> {
            throw new java.io.IOException("connection refused");
        });

        Run after = runs.byKey(PROCESS + "/" + step + "/later").orElseThrow();
        assertFalse(after.claimed(Instant.now()), "it is not being held by anybody now");
        assertFalse(after.needsAPerson(),
                "a queue that collects 'their server was down' stops being read: " + after);
        assertTrue(runner("their-lis-4", step, Duration.ofMinutes(5))
                        .open(after.key()).isPresent(),
                "and somebody can take it again");
    }

    @Test
    @DisplayName("a workplace is the same participant with a person inside it")
    void aWorkplaceIsTheSameClient() {
        String step = "validate-by-hand";
        Run waiting = work(step, "by-hand");
        Runner workplace = runner("the-bench", step, Duration.ofMinutes(30));

        // somebody opens it
        Run opened = workplace.open(waiting.key()).orElseThrow();
        assertTrue(opened.claimed(Instant.now()), "two people opening one thing is what a "
                + "claim is for, and a person is no more entitled to hold it twice");
        assertTrue(workplace.open(waiting.key()).isEmpty());

        // ... and finishes it, through the same report a service uses
        Run done = workplace.report(opened, Runner.Outcome.done(Map.of("checked", 1L)));

        assertEquals(Holder.NOBODY, runs.byKey(done.key()).orElseThrow().holder());
        assertEquals(1L, runs.byKey(done.key()).orElseThrow().tally().get("checked"));
    }

    @Test
    @DisplayName("killing it mid-work loses nothing: the claim lapses and the run says released")
    void killingItMidWorkLosesNothing() {
        String step = "validate-killed";
        work(step, "in-flight");
        Runner dying = runner("their-lis-5", step, Duration.ofMillis(1));

        dying.runOnce(50, run -> {
            // taken, and then the process stops existing
            return Runner.Outcome.handedOn();
        });

        assertTrue(Participation.releaseLapsed(runs) >= 1);
        Run after = runs.byKey(PROCESS + "/" + step + "/in-flight").orElseThrow();
        assertFalse(after.claimed(Instant.now()));
        assertTrue(after.assignment().note().contains("released"),
                "released is not done, and the difference is the whole point of a deadline");
        assertTrue(runner("their-lis-6", step, Duration.ofMinutes(5))
                        .open(after.key()).isPresent());
    }
}
