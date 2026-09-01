package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A change to what a consumer compiles against is a change somebody made on
 * purpose.
 *
 * <p>`Bundle-Version` comes from the project version, so it moves when a
 * release moves and says nothing about what changed inside. Remove a method
 * from an exported package and the version claims a compatibility the bundle
 * no longer has: nothing in the build objects, and the failure arrives at a
 * consumer as a {@code NoSuchMethodError} at runtime rather than as a refusal
 * at build time. That is the exact failure class the OSGi machinery exists to
 * prevent, and this store already uses the tool that prevents it without the
 * part that does.
 *
 * <p>This is not bnd baselining and does not pretend to be. Baselining answers
 * "what version would this change require", which needs a previously published
 * artifact to compare against and means something only once a version is one
 * somebody is entitled to rely on. At {@code 0.1.0-SNAPSHOT}, with every module
 * sharing one version, that answer has no audience. The question that does have
 * one is narrower and available today: <b>did the surface change, and did
 * anybody notice?</b>
 *
 * <p>So the surface is recorded and the recording is reviewed. Removing a line
 * is allowed — it is normal before a release anybody relies on — and it shows
 * up in the diff, with a message that says which signatures went and what that
 * costs a consumer. What it can no longer be is invisible.
 */
class TheExportedApiChangesOnPurposeTest {

    private static final Path LEDGER = Path.of(System.getProperty("dbo.api.ledger",
            "config/api-ledger.txt"));

    @Test
    @DisplayName("the exported surface matches what was recorded — re-record, never hand-edit")
    void theSurfaceIsWhatTheLedgerSays() throws Exception {
        List<String> now = ApiLedger.signatures();
        assertTrue(now.size() > 500,
                "the scan found almost no exported API, so it is guarding nothing: "
                        + now.size() + " signatures");
        assertTrue(Files.exists(LEDGER),
                "no api ledger at " + LEDGER.toAbsolutePath().normalize()
                        + " — record it with ./gradlew :core:harness:apiLedger");

        List<String> recorded = Files.readAllLines(LEDGER, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .toList();

        List<String> removed = ApiLedger.missingFrom(recorded, now);
        List<String> added = ApiLedger.missingFrom(now, recorded);

        assertTrue(removed.isEmpty() && added.isEmpty(),
                report(removed, added));
    }

    private static String report(List<String> removed, List<String> added) {
        StringBuilder message = new StringBuilder("the exported API is not what the ledger "
                + "records. Re-record with ./gradlew :core:harness:apiLedger and let the diff "
                + "be reviewed.\n");
        if (!removed.isEmpty()) {
            message.append("\nGONE — anything compiled against these stops linking, and "
                    + "the bundle version does not say so:\n");
            removed.forEach(line -> message.append("  - ").append(line.trim()).append('\n'));
        }
        if (!added.isEmpty()) {
            message.append("\nNEW — compatible for callers; an added interface method is "
                    + "still a break for anybody implementing it:\n");
            added.forEach(line -> message.append("  + ").append(line.trim()).append('\n'));
        }
        return message.toString();
    }
}
