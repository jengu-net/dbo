package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.identity.Candidate;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;
import cloud.jengu.dbo.core.api.identity.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#39: identification resolves to candidates, and a claim's strength bounds
 * what it can conclude.
 *
 * <p>The failure these prevent is silent: everything resolves, everything
 * works, and a number nobody checked has quietly attached one person's care to
 * another's record.
 */
class IdentityResolutionIT {

    private static final String EE = "https://eesti.ee/isikukood";
    private static final String LV = "https://latvija.lv/personas-kods";

    @Test
    @DisplayName("#39: an authenticated claim matching one subject resolves without a human")
    void aProvenClaimResolvesOnItsOwn() {
        IdentityClaim proven = IdentityClaim.authenticated(EE, "38001010021");

        Resolution resolution = Resolution.of(List.of(proven), Map.of(proven, List.of("person-1")));

        assertEquals("person-1", resolution.certain().orElseThrow());
        assertFalse(resolution.needsAdjudication());
    }

    @Test
    @DisplayName("#39: a number read off a document never resolves anybody by itself")
    void anUnverifiedClaimNeverResolvesAutomatically() {
        // the coma patient's driving licence, transcribed from a card
        IdentityClaim fromAPocket = IdentityClaim.asserted(
                "https://issuer.example/driving-licence", "K1234567");

        Resolution resolution = Resolution.of(List.of(fromAPocket),
                Map.of(fromAPocket, List.of("person-1")));

        assertTrue(resolution.certain().isEmpty(),
                "a transcription nobody checked must not attach this person's care to a record");
        assertTrue(resolution.needsAdjudication());
        assertEquals(Candidate.Confidence.POSSIBLE, resolution.candidates().get(0).confidence());
    }

    @Test
    @DisplayName("#39: claims pointing at different people destroy certainty rather than picking one")
    void ambiguityIsNotResolvedByChoosing() {
        IdentityClaim estonian = IdentityClaim.authenticated(EE, "38001010021");
        IdentityClaim latvian = IdentityClaim.authenticated(LV, "38001010021");

        Resolution resolution = Resolution.of(List.of(estonian, latvian),
                Map.of(estonian, List.of("person-1"), latvian, List.of("person-2")));

        assertTrue(resolution.certain().isEmpty(),
                "resolving one of two would be choosing, and choosing is what the human step is");
        assertEquals(2, resolution.candidates().size());
        assertTrue(resolution.needsAdjudication());
    }

    @Test
    @DisplayName("#39: no match is an ordinary answer — the person before their first visit")
    void noMatchIsNotAnError() {
        IdentityClaim unknown = IdentityClaim.authenticated(EE, "39912310099");

        Resolution resolution = Resolution.of(List.of(unknown), Map.of());

        assertTrue(resolution.certain().isEmpty());
        assertFalse(resolution.needsAdjudication(),
                "nothing to adjudicate: this is somebody the store has never seen, which is how "
                        + "everybody starts");
        assertEquals(List.of(), resolution.candidates());
    }

    @Test
    @DisplayName("#39: a revoked document does not resolve anybody — it is in somebody else's hands")
    void aRevokedClaimDoesNotMatch() {
        IdentityClaim stolen = new IdentityClaim("https://issuer.example/passport", "P9988776",
                IdentityClaim.Verification.CHECKED, IdentityClaim.Status.REVOKED);

        Resolution resolution = Resolution.of(List.of(stolen), Map.of(stolen, List.of("person-1")));

        assertTrue(resolution.certain().isEmpty(),
                "revocation is not expiry: a stolen document is an authentication path");
        assertEquals(Candidate.Confidence.POSSIBLE, resolution.candidates().get(0).confidence());
    }

    @Test
    @DisplayName("#39: a superseded number still finds the person its records refer to")
    void aSupersededClaimStillResolvesForHistory() {
        IdentityClaim old = new IdentityClaim(EE, "38001010021",
                IdentityClaim.Verification.CHECKED, IdentityClaim.Status.SUPERSEDED);

        Resolution resolution = Resolution.of(List.of(old), Map.of(old, List.of("person-1")));

        assertEquals(1, resolution.candidates().size(),
                "records written under the old number still refer to it, so it must find them");
        assertEquals(Candidate.Confidence.POSSIBLE, resolution.candidates().get(0).confidence(),
                "but not automatically — a replaced number is weaker evidence than the current one");
    }

    @Test
    @DisplayName("#39: a claim with no issuing system is refused — the same digits are two people")
    void aClaimWithoutAnIssuerIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> IdentityClaim.asserted("", "38001010021"));

        assertTrue(refused.getMessage().contains("namespace"), refused.getMessage());
    }

    /**
     * Two rules are easy to confuse, and only one applies here.
     *
     * <p>A <b>chain</b> is as strong as its weakest link: credential → person →
     * capacity bounds authorization by the weakest step, because every step has
     * to hold for the conclusion to.
     *
     * <p>Independent claims for one subject are <b>alternative evidence</b>, and
     * the best evidence wins. A proven national identity establishes the match
     * on its own; an unchecked number pointing at the same person adds nothing
     * and takes nothing away. Applying the chain rule here would mean somebody
     * could weaken a real login by mentioning a document — which is backwards,
     * and would reward presenting less.
     */
    @Test
    @DisplayName("#39: alternatives take the strongest claim; a weak one alongside changes nothing")
    void alternativeClaimsTakeTheStrongest() {
        IdentityClaim proven = IdentityClaim.authenticated(EE, "38001010021");
        IdentityClaim guessed = IdentityClaim.asserted(
                "https://issuer.example/driving-licence", "K1234567");

        Resolution resolution = Resolution.of(List.of(proven, guessed),
                Map.of(proven, List.of("person-1"), guessed, List.of("person-1")));

        assertEquals(Candidate.Confidence.CERTAIN,
                resolution.candidates().get(0).confidence(),
                "the proven claim establishes this on its own; the unchecked one neither helps "
                        + "nor hinders, and letting it reduce the answer would reward presenting "
                        + "less evidence");
        assertEquals("person-1", resolution.certain().orElseThrow());
        assertEquals(2, resolution.candidates().get(0).matched().size(),
                "both claims are recorded as having matched, even though one decided nothing");
    }
}
