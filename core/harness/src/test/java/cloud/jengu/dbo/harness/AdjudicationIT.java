package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.identity.Adjudication;
import cloud.jengu.dbo.core.api.identity.Candidate;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;
import cloud.jengu.dbo.core.api.identity.Resolution;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person's decision about who somebody is, kept — including when the
 * answer was "not this one".
 *
 * <p>Without the negative half, the next person to meet the same near-match
 * starts from nothing and reaches the same conclusion again, forever. With it,
 * a wrong conclusion is also examinable, which is why a rejected candidate is
 * marked rather than hidden.
 */
class AdjudicationIT {

    private static final String EE = "https://eesti.ee/isikukood";
    private static final String LICENCE = "https://issuer.example/driving-licence";
    private static final Instant WHEN = Instant.parse("2026-08-17T09:15:00Z");

    @Test
    @DisplayName("a decision names who made it, when, and what they were looking at")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void aDecisionCarriesItsProvenance() {
        IdentityClaim presented = IdentityClaim.asserted(LICENCE, "K1234567");

        Adjudication decision = Adjudication.bound("person-1", List.of("person-2"),
                List.of(presented), "reception-desk-7", WHEN,
                "licence photograph matches the record");

        assertEquals(Adjudication.Outcome.BOUND, decision.outcome());
        assertEquals("person-1", decision.subjectId());
        assertEquals(List.of("person-2"), decision.rejected(),
                "who was considered and declined is the half that saves the next person's time");
        assertEquals("reception-desk-7", decision.decidedBy());
    }

    @Test
    @DisplayName("an anonymous decision is refused — it can never be questioned")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void aDecisionWithoutADeciderIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Adjudication.created("person-9", List.of(), List.of(),
                        "  ", WHEN, "new patient"));

        assertTrue(refused.getMessage().contains("revisited"), refused.getMessage());
    }

    @Test
    @DisplayName("nobody has decided yet, and that is a state rather than a failure")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void deferredNeedsNobody() {
        Adjudication pending = Adjudication.deferred(
                List.of(IdentityClaim.asserted(LICENCE, "K1234567")));

        assertEquals(Adjudication.Outcome.DEFERRED, pending.outcome());
        assertEquals(1, pending.presented().size(),
                "what was presented is kept, so whoever picks it up later starts where this left off");
    }

    @Test
    @DisplayName("a candidate somebody already declined comes back marked, not hidden")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void aRejectedCandidateIsMarkedNotHidden() {
        IdentityClaim claim = IdentityClaim.authenticated(EE, "38001010021");

        Resolution again = Resolution.of(List.of(claim), Map.of(claim, List.of("person-2")),
                Set.of("person-2"));

        assertEquals(1, again.candidates().size(),
                "hiding it would make a wrong decision permanent and invisible — the right match "
                        + "would never be offered again and nobody would know why");
        assertTrue(again.candidates().get(0).previouslyRejected());
    }

    @Test
    @DisplayName("a machine does not silently reverse a person's conclusion")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void aRejectedCandidateNeverResolvesAutomatically() {
        IdentityClaim proven = IdentityClaim.authenticated(EE, "38001010021");

        Resolution fresh = Resolution.of(List.of(proven), Map.of(proven, List.of("person-2")));
        Resolution afterRejection = Resolution.of(List.of(proven),
                Map.of(proven, List.of("person-2")), Set.of("person-2"));

        assertEquals("person-2", fresh.certain().orElseThrow(),
                "with nothing known against it, a proven claim resolves on its own");
        assertTrue(afterRejection.certain().isEmpty(),
                "once somebody has said these are different people, the same evidence must go "
                        + "back to a person rather than quietly overriding them");
        assertTrue(afterRejection.needsAdjudication());
        assertEquals(Candidate.Confidence.PROBABLE,
                afterRejection.candidates().get(0).confidence());
    }

    @Test
    @DisplayName("a candidate nobody has judged is not marked")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void anUnjudgedCandidateCarriesNoMark() {
        IdentityClaim proven = IdentityClaim.authenticated(EE, "38001010021");

        Resolution resolution = Resolution.of(List.of(proven),
                Map.of(proven, List.of("person-1")), Set.of("person-2"));

        assertFalse(resolution.candidates().get(0).previouslyRejected(),
                "a rejection of somebody else says nothing about this one");
        assertEquals("person-1", resolution.certain().orElseThrow());
    }
}
