package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness class builds a runtime of its own only for a reason the
 * shared-world rule allows, and the number that predate the rule only falls.
 *
 * <p>{@link WorldsLedger} records the classes; this fails when the record and
 * the sources disagree, when an entry has no reason, and when the undecided
 * count rises above what the ledger allows. Like the ledgers it is modelled
 * on it claims no promise: it is the repository keeping its own word about
 * how long its suite takes.
 */
class AWorldOfItsOwnIsADecisionTest {

    private static final Path HARNESS = Path.of("src/test/java/cloud/jengu/dbo/harness");

    @Test
    @DisplayName("the classes that build a runtime are the ones the ledger says do — "
            + "re-record, never hand-edit")
    void theLedgerMatchesTheSources() throws Exception {
        Map<String, String> recorded = WorldsLedger.existing(ledger());
        TreeSet<String> computed = WorldsLedger.ownWorlds(harness());

        List<String> appeared = new ArrayList<>(computed);
        appeared.removeAll(recorded.keySet());
        assertTrue(appeared.isEmpty(),
                "these classes build a runtime of their own and the ledger does not say so. "
                        + "A shared tenant is the default; a world of its own needs one of "
                        + "the ledger's reasons.\nRe-record with ./gradlew "
                        + ":core:harness:worldsLedger and give each a reason:\n  "
                        + String.join("\n  ", appeared));

        List<String> gone = new ArrayList<>(recorded.keySet());
        gone.removeAll(computed);
        assertTrue(gone.isEmpty(),
                "the ledger lists these as building a runtime and they no longer do — good "
                        + "news, and the record has to say so.\nRe-record with ./gradlew "
                        + ":core:harness:worldsLedger:\n  " + String.join("\n  ", gone));
    }

    @Test
    @DisplayName("an entry says why it needs a world of its own")
    void everyEntryCarriesItsReason() throws Exception {
        List<String> unexplained = new ArrayList<>();
        for (Map.Entry<String, String> entry : WorldsLedger.existing(ledger()).entrySet()) {
            if (entry.getValue().isBlank() || entry.getValue().contains(WorldsLedger.MISSING)) {
                unexplained.add(entry.getKey());
            }
        }
        assertTrue(unexplained.isEmpty(),
                "these build a runtime of their own and the ledger does not say why. Write "
                        + "lifecycle, container, tampering, first boot, sweep, whole plane or "
                        + "deployment, or "
                        + "take a shared "
                        + "tenant instead:\n  " + String.join("\n  ", unexplained));
    }

    @Test
    @DisplayName("the number of undecided worlds only falls")
    void theUndecidedCountOnlyFalls() throws Exception {
        long undecided = WorldsLedger.undecided(WorldsLedger.existing(ledger()));
        int allowed = WorldsLedger.allowance(ledger());
        assertTrue(undecided <= allowed,
                undecided + " classes are UNDECIDED and the ledger allows " + allowed + ". "
                        + "UNDECIDED is what predates the ledger, so a new class cannot take "
                        + "it: give the new one a reason, or a shared tenant.");
    }

    private static Path ledger() {
        String path = System.getProperty("dbo.worlds.ledger");
        assertTrue(path != null, "no worlds ledger staged: -Ddbo.worlds.ledger");
        Path ledger = Path.of(path);
        assertTrue(Files.exists(ledger), "no worlds ledger at " + ledger);
        return ledger;
    }

    /** The harness sources, wherever the test happens to be run from. */
    private static Path harness() {
        if (Files.isDirectory(HARNESS)) {
            return HARNESS;
        }
        Path fromRoot = Path.of("core/harness").resolve(HARNESS);
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("the harness sources are not where this expected: "
                + HARNESS.toAbsolutePath() + " nor " + fromRoot.toAbsolutePath());
    }
}
