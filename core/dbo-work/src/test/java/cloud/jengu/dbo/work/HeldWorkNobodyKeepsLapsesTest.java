package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session somebody signed in to, as the work that lasts until it ends.
 *
 * <p>Over the work domain's own records, in a map: what the authority does
 * with a session is its own, and what is decided here is when the work says
 * nobody kept it.
 */
class HeldWorkNobodyKeepsLapsesTest {

    private static final String PROCESS = "dbo.auth";
    private static final String STEP = "sign-in";

    private final Runs runs = new Runs(new WorkInMemory().store());

    @Test
    @DisplayName("a session nobody keeps past its deadline is overdue and ends as timed out; "
            + "one kept, or ended, is not")
    @Proving(DboPromises.AUTH_A_SESSIONS_END_IS_ON_THE_TRAIL)
    void aSessionNobodyKeepsTimesOut() {
        Instant now = Instant.now();
        Run unkept = runs.held(PROCESS, STEP, "unkept", "person", "Person/1", "portal",
                now.plus(Duration.ofHours(1)));
        Run kept = runs.held(PROCESS, STEP, "kept", "person", "Person/2", "portal",
                now.plus(Duration.ofHours(1)));
        Run signedOut = runs.held(PROCESS, STEP, "signed-out", "person", "Person/3",
                "portal", now.plus(Duration.ofHours(1)));
        assertEquals(unkept.key(), runs.held(PROCESS, STEP, "unkept", "person", "Person/1",
                "portal", now).key(), "the same session was opened twice");
        assertTrue(runs.overdue(PROCESS, STEP, now).isEmpty(),
                "a session was overdue before its deadline");

        assertTrue(runs.kept(kept, now.plus(Duration.ofHours(3))).isPresent());
        runs.ended(signedOut, Status.COMPLETED, "Person/3", "signed out");
        Instant later = now.plus(Duration.ofHours(2));
        List<String> overdue = runs.overdue(PROCESS, STEP, later).stream()
                .map(Run::key).toList();
        assertEquals(List.of(unkept.key()), overdue,
                "what is overdue is not exactly the session nobody kept");
        assertTrue(runs.lapsed(later).isEmpty(),
                "a session was handed back as a claim that lapsed");
        assertTrue(runs.kept(signedOut, later).isEmpty(),
                "a session that ended was kept");

        Run timedOut = runs.ended(runs.overdue(PROCESS, STEP, later).get(0), Status.COMPLETED,
                null, "timed out");
        assertEquals(Status.COMPLETED, timedOut.status());
        assertTrue(runs.overdue(PROCESS, STEP, later).isEmpty(),
                "a session that timed out is still overdue");
    }

    @Test
    @DisplayName("a sign-in turned away is over as it is written, with why")
    @Proving(DboPromises.AUTH_A_REFUSED_SIGN_IN_IS_ON_THE_TRAIL)
    void aSignInTurnedAwayIsOver() {
        Run refused = runs.turnedAway(PROCESS, STEP, "portal", "wrong secret");
        assertEquals(Status.REJECTED, refused.status());
        assertTrue(refused.lasting(), "a refused sign-in is not the work it was asked for");
        assertTrue(runs.overdue(PROCESS, STEP, Instant.now().plus(Duration.ofDays(1)))
                .isEmpty(), "a refused sign-in has a session to lapse");
    }
}
