package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Participation;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How work reaches whoever does it (#77).
 *
 * <p>Over a real store and a real feed, because every claim in this issue is
 * about what two processes do to one row: at-most-one holder comes from a
 * conditional write, and a fake store would prove the arithmetic rather than
 * the property.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParticipantsPullAndClaimIT {

    private static final String PROCESS = "dbo.lab.result";
    private static final String MINE = "validate";
    private static final String SOMEBODY_ELSE = "dispatch";

    static PgObjectStore store;
    static PGSimpleDataSource ds;
    static Runs runs;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ParticipantsPullAndClaimIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, WorkModel.registrations());
        runs = new Runs(store);
    }

    private static Participation participant(String name, String step) {
        return new Participation(runs, new PgChangeFeed(ds, WorkModel.DOMAIN), name,
                Set.of(step), new Executor(name, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    private static Run work(String step, String key) {
        return runs.pipeline(PROCESS, step, PROCESS + "/" + step + "/" + key,
                List.of(WorkModel.DOMAIN));
    }

    @Test
    @DisplayName("a participant sees the work of the steps it holds, and no other")
    void aParticipantSeesOnlyItsOwnSteps() {
        work(MINE, "mine-1");
        work(SOMEBODY_ELSE, "theirs-1");

        List<Run> seen = participant("validator-a", MINE).poll(50);

        assertTrue(seen.stream().anyMatch(run -> run.key().endsWith("/mine-1")));
        assertTrue(seen.stream().noneMatch(run -> run.step().equals(SOMEBODY_ELSE)),
                "another step's work is not this participant's to see: " + seen);
    }

    @Test
    @DisplayName("two participants racing one run produce one holder and one actor")
    void twoParticipantsRacingProduceOneHolder() throws Exception {
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            // Several rounds, and a barrier in each: a race that happens to
            // serialise proves the guard rather than the conditional write, and
            // the claim has to hold when both claimants genuinely read the run
            // as free.
            for (int round = 0; round < 5; round++) {
                Run contested = work(MINE, "contested-" + round);
                java.util.concurrent.CyclicBarrier ready =
                        new java.util.concurrent.CyclicBarrier(racers);
                AtomicInteger won = new AtomicInteger();
                List<Callable<Optional<Run>>> attempts = new java.util.ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    String name = "racer-" + i;
                    attempts.add(() -> {
                        ready.await();
                        Optional<Run> claimed = participant(name, MINE)
                                .claim(contested, Duration.ofMinutes(5));
                        claimed.ifPresent(run -> won.incrementAndGet());
                        return claimed;
                    });
                }
                for (Future<Optional<Run>> attempt : pool.invokeAll(attempts)) {
                    attempt.get();
                }

                assertEquals(1, won.get(),
                        "you scale by adding claimants, never by relaxing the claim");
                Run after = runs.byKey(contested.key()).orElseThrow();
                assertTrue(after.claimed(Instant.now()));
                assertTrue(after.assignment().executor().name().startsWith("racer-"),
                        "and the record names which one: " + after.assignment());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a run seen twice is claimed once")
    void aRunSeenTwiceIsClaimedOnce() {
        Run twice = work(MINE, "twice");
        Participation participant = participant("validator-b", MINE);

        assertTrue(participant.claim(twice, Duration.ofMinutes(5)).isPresent());
        assertFalse(participant.claim(twice, Duration.ofMinutes(5)).isPresent(),
                "delivery is at-least-once, so the claim is what dedups — not the "
                        + "participant's own bookkeeping");
    }

    @Test
    @DisplayName("a participant killed mid-claim releases the work by deadline, and the run "
            + "says released rather than done")
    void aLapsedClaimIsReleased() {
        Run abandoned = work(MINE, "abandoned");
        Participation dying = participant("validator-c", MINE);
        assertTrue(dying.claim(abandoned, Duration.ofMillis(1)).isPresent());
        // and now it stops answering, which is the case a deadline exists for

        assertTrue(Participation.releaseLapsed(runs) >= 1);

        Run after = runs.byKey(abandoned.key()).orElseThrow();
        assertFalse(after.claimed(Instant.now()), "nobody holds it now");
        assertTrue(after.assignment().note().contains("released"),
                "a run that said done because whoever held it stopped answering is the "
                        + "failure a deadline exists to prevent: " + after.assignment());
        assertTrue(participant("validator-d", MINE).claim(after, Duration.ofMinutes(5)).isPresent(),
                "and somebody else can take it");
    }

    @Test
    @DisplayName("progress extends a claim, and a tick does not")
    void checkpointsExtendTheClaim() {
        Run long_ = work(MINE, "long-running");
        Participation participant = participant("validator-e", MINE);
        Run claimed = participant.claim(long_, Duration.ofSeconds(1)).orElseThrow();

        Run extended = participant.checkpoint(claimed, Map.of("read", 200L),
                Duration.ofMinutes(10));

        assertEquals(200L, extended.tally().get("read"),
                "what extends a claim is evidence of progress, and it is on the record anyway");
        assertTrue(extended.assignment().until().isAfter(Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("how far behind a participant is, is readable per participant")
    @Proving(DboPromises.FEED_NAMED_CONSUMERS)
    void lagIsReadable() {
        Participation late = participant("validator-late", MINE);
        work(MINE, "backlog-1");
        work(MINE, "backlog-2");

        assertTrue(late.lag() >= 2, "a participant that has read nothing is behind by what "
                + "there is: " + late.lag());
        late.poll(100);
        assertEquals(0, late.lag(), "and having read it, it is not");
    }
}
