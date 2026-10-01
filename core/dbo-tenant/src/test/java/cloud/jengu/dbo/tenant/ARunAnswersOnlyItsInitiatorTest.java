package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority.AuthContext;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who a run's own address answers: the client that asked for the run, and
 * nobody else — who is told the run is not there.
 *
 * <p>The rule, asked directly, because every case of it is a refusal that
 * reads the same from outside: a test over HTTP could not tell a 404 for
 * another client from a 404 for a run nobody started, and that sameness is the
 * point.
 */
class ARunAnswersOnlyItsInitiatorTest {

    private static final Optional<Run> ASKED_BY_THE_WORKER =
            Optional.of(run("sample-worker"));

    @Test
    @DisplayName("the client that asked for a run is answered with it")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void theInitiatorIsAnswered() {
        assertEquals(200, StepSurface.reach(asking("sample-worker", Scopes.WORK),
                ASKED_BY_THE_WORKER));
    }

    @Test
    @DisplayName("another client that may act in work is told the run is not there, never "
            + "that it may not see it")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void anotherClientIsToldNothingIsThere() {
        assertEquals(404, StepSurface.reach(asking("another-worker", Scopes.WORK),
                ASKED_BY_THE_WORKER));
    }

    @Test
    @DisplayName("the initiator's own client id on a credential that may not act in work is "
            + "told the run is not there")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void aCredentialWithoutWorkIsToldNothingIsThere() {
        assertEquals(404, StepSurface.reach(asking("sample-worker", "system/*.read"),
                ASKED_BY_THE_WORKER));
    }

    @Test
    @DisplayName("a run nobody asked for at the step door — authored on the records surface, "
            + "by a lane, in process — answers nobody, and an unknown run answers alike")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void aRunWithNoInitiatorAnswersNobody() {
        assertEquals(404, StepSurface.reach(asking("sample-worker", Scopes.WORK),
                Optional.of(run(null))));
        assertEquals(404, StepSurface.reach(asking("sample-worker", Scopes.WORK),
                Optional.empty()));
    }

    @Test
    @DisplayName("no credential is told so, which says nothing about any run")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void noCredentialIsUnauthorised() {
        assertEquals(401, StepSurface.reach(Optional.empty(), ASKED_BY_THE_WORKER));
    }

    private static Optional<AuthContext> asking(String clientId, String... scopes) {
        return Optional.of(new AuthContext(clientId, null, null, List.of(scopes), null,
                List.of()));
    }

    private static Run run(String requester) {
        return new Run("run-1", 1, "hogwarts.admission.admit/one", "hogwarts.admission",
                "admit", RunKind.PIPELINE, Holder.NOBODY, null, null, null, Map.of(), null,
                List.of(), null, Run.Produced.NOTHING, "1", Map.of(), null, requester);
    }
}
