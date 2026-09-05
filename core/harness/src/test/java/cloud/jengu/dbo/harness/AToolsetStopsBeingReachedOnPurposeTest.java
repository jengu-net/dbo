package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The characteristic bug of this codebase, made to fail.
 *
 * <p>A toolset can be built, proven and mounted by nothing, and every test
 * passes — because a harness <b>is</b> the container and constructs whatever
 * it needs. It has happened repeatedly, and {@code CLAUDE.md} says why it
 * keeps happening: the question that catches it is asked by a person,
 * deliberately, or not at all.
 *
 * <p>So it is asked by the build. {@link ReachLedger} records every production
 * class that no other production class names, each with a reason a person
 * wrote; this fails when that set moves, and fails separately when an entry
 * arrives with no reason. The second failure is the valuable one — a class
 * that quietly stopped being reachable is exactly the thing nobody notices.
 *
 * <p>It claims no promise, like the ledger it is modelled on. A promise is
 * something a tenant is owed; this is the repository keeping its own word to
 * itself, and dressing it as a requirement would put a build rule in a
 * catalogue of behaviour.
 */
class AToolsetStopsBeingReachedOnPurposeTest {

    @Test
    @DisplayName("what production names is what the ledger says it names — re-record, "
            + "never hand-edit")
    void theLedgerMatchesWhatProductionNames() throws Exception {
        Path ledger = ledger();
        Map<String, String> recorded = ReachLedger.existing(ledger);
        List<String> computed = List.copyOf(ReachLedger.unreached().keySet());

        List<String> appeared = new ArrayList<>(computed);
        appeared.removeAll(recorded.keySet());
        assertTrue(appeared.isEmpty(),
                "these are named by nothing in production and the ledger does not say so.\n"
                        + "If one of them is the defect this file exists to surface, that is "
                        + "worth knowing before it ships; if it is reached by something the "
                        + "ledger cannot see, teach the ledger.\n"
                        + "Re-record with ./gradlew :core:harness:reachLedger and give each a "
                        + "reason:\n  " + String.join("\n  ", appeared));

        List<String> gone = new ArrayList<>(recorded.keySet());
        gone.removeAll(computed);
        assertTrue(gone.isEmpty(),
                "the ledger lists these as reached by nothing and production now names them — "
                        + "good news, and the record has to say so or the next reader trusts a "
                        + "stale one.\nRe-record with ./gradlew :core:harness:reachLedger:\n  "
                        + String.join("\n  ", gone));
    }

    @Test
    @DisplayName("an entry says why it is there, because an unexplained one is the finding "
            + "rather than the record")
    void everyEntryCarriesItsReason() throws Exception {
        List<String> unexplained = new ArrayList<>();
        for (Map.Entry<String, String> entry : ReachLedger.existing(ledger()).entrySet()) {
            if (entry.getValue().isBlank() || entry.getValue().contains(ReachLedger.MISSING)) {
                unexplained.add(entry.getKey());
            }
        }
        assertTrue(unexplained.isEmpty(),
                "nothing in production names these and the ledger does not say whether that is "
                        + "a decision or a defect. Name the external consumer, or the mechanism "
                        + "that reaches it, or write NOT REACHED and mean it:\n  "
                        + String.join("\n  ", unexplained));
    }

    private static Path ledger() {
        String path = System.getProperty("dbo.reach.ledger");
        // Staged by the build, and absent means this test would pass over an
        // empty file — the shape of a ratchet that guards nothing.
        assertTrue(path != null, "no reach ledger staged: -Ddbo.reach.ledger");
        Path ledger = Path.of(path);
        assertTrue(Files.exists(ledger), "no reach ledger at " + ledger);
        return ledger;
    }
}
