package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.ArchiveProvenance;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attestation, said in the reader's vocabulary — a `Provenance` carrying
 * FHIR's `Signature` (§11, #34).
 *
 * <p>The proof that matters is not that the JSON looks right: it is that a
 * real personality parses and validates it. A rendering nobody can check is
 * the thing this whole arrangement exists to avoid, and hand-built JSON that
 * is merely plausible fails in somebody else's tooling rather than here.
 */
class ArchiveProvenanceIT {

    private static final String ROOT =
            "9f2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809";

    private static String rendered() {
        return ArchiveProvenance.render(ROOT, "2026-08-18T21:00:00Z", List.of(
                new ArchiveProvenance.Signer("vendor", "aa".repeat(32), "c2lnbmF0dXJl"),
                new ArchiveProvenance.Signer("tenant", "bb".repeat(32), "Y291bnRlcnNpZ24=")));
    }

    @Test
    @Timeout(300)
    @DisplayName("the rendered attestation is a valid Provenance, checked by a real personality")
    @Proving(DboPromises.MNT_ATTESTATION_READS_AS_FHIR)
    void theRenderingIsAValidProvenance() {
        R4Personality personality = new R4Personality(List.of());
        List<String> errors = personality.validate(rendered()).stream()
                .filter(issue -> issue.startsWith("ERROR") || issue.startsWith("FATAL"))
                .toList();
        assertEquals(List.of(), errors, "the rendering does not conform");
    }

    @Test
    @DisplayName("it names the archive by its root and each signer by key, never by name")
    @Proving(DboPromises.MNT_ATTESTATION_READS_AS_FHIR)
    void itNamesTheRootAndTheKeys() {
        String json = rendered();

        assertTrue(json.contains("\"value\":\"" + ROOT + "\""),
                "the root is what the signatures are over, so it is what the target names");
        assertTrue(json.contains("\"data\":\"c2lnbmF0dXJl\"")
                        && json.contains("\"data\":\"Y291bnRlcnNpZ24=\""),
                "both signatures travel, or the reader can check one party and not the other");
        // fingerprints, not keys, and no human names anywhere: a customer's
        // auditor learns which key signed, not who was at the keyboard
        assertTrue(json.contains("urn:dbo:signing-key"), json);
        assertTrue(json.contains("\"text\":\"vendor\"") && json.contains("\"text\":\"tenant\""),
                "the two parties are named as the arrangement names them: " + json);
    }
}
