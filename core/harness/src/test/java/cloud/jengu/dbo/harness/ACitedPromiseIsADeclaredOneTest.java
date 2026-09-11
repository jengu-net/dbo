package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A promise cited in prose is a string, and a string does not fail to compile.
 *
 * <p>{@code @Proving} is checked by the compiler and needs no help from here.
 * The mentions in javadoc and in the specification are not: rename a promise
 * and they go on naming something that has stopped existing, silently, which
 * is the shape this repository distrusts everywhere else. The first run of
 * this found six codes that had never existed at all — not renamed, invented —
 * which is why the ledger records a reason rather than the scan guessing one.
 *
 * <p>It claims no promise, like the ledger it is modelled on. A promise is
 * about the store; this is about whether the tree still means what it says.
 */
class ACitedPromiseIsADeclaredOneTest {

    @Test
    @DisplayName("a code spelled out in the tree is one a catalogue declares, or the "
            + "ledger says why not — re-record with ./gradlew :core:harness:promiseCitations")
    void everyCitedCodeIsDeclaredOrRecorded() throws Exception {
        Path ledger = Path.of(System.getProperty("dbo.promise.citations"));
        Path root = Path.of(System.getProperty("dbo.repo.root"));
        Map<String, String> recorded = PromiseCitations.existing(ledger);
        Map<String, String> now = PromiseCitations.unresolved(root, getClass().getClassLoader());

        List<String> appeared = new ArrayList<>();
        now.forEach((code, where) -> {
            if (!recorded.containsKey(code)) {
                appeared.add("  " + code + "  first in " + where);
            }
        });
        assertTrue(appeared.isEmpty(),
                "these name a promise no catalogue declares, and the ledger does not say so.\n"
                        + "Cite a code that exists, or record why this one cannot be:\n"
                        + String.join("\n", appeared));

        List<String> gone = new ArrayList<>(recorded.keySet());
        gone.removeAll(now.keySet());
        assertTrue(gone.isEmpty(),
                "the ledger records citations the tree no longer has — re-record it:\n  "
                        + String.join("\n  ", gone));

        List<String> unexplained = new ArrayList<>();
        recorded.forEach((code, reason) -> {
            if (reason.isBlank() || reason.equals("REASON MISSING")) {
                unexplained.add("  " + code);
            }
        });
        assertTrue(unexplained.isEmpty(),
                "recorded with no reason — say which of the kinds at the top of the ledger "
                        + "each one is:\n" + String.join("\n", unexplained));
    }
}
