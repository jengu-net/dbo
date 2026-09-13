package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No tenant code is declared by two harness classes.
 *
 * <p>The rule was written down in {@link SharedPostgres}'s own documentation
 * and broken anyway, which is what a convention with nothing enforcing it
 * eventually is: two classes both opened a tenant called {@code meristem},
 * and a full run failed in whichever of them lost the race — as
 * {@code invalid_client}, four tests deep, in a class that had nothing to do
 * with the change under test.
 *
 * <p>This is the enforcement the convention lacked. It reads the sources
 * rather than the run, so the answer arrives before a suite is spent finding
 * it, and it does not depend on the two classes landing in one JVM.
 */
class ATenantCodeBelongsToOneClassTest {

    private static final Path HARNESS = Path.of("src/test/java/cloud/jengu/dbo/harness");

    @Test
    @DisplayName("no tenant code is opened by two harness classes, because they would be "
            + "one database on the shared server")
    void oneCodeBelongsToOneClass() throws Exception {
        TenantCodes.Reading reading = TenantCodes.read(harness());

        assertTrue(reading.declared().size() > 50,
                "only " + reading.declared().size() + " tenant declarations found, so the "
                        + "scan has stopped reading the suite rather than the suite having "
                        + "stopped declaring tenants");

        Map<String, Set<String>> shared = reading.shared();
        assertTrue(shared.isEmpty(),
                "a tenant code is declared by more than one class, and a tenant's database "
                        + "name comes from its code, so both address one database on the "
                        + "shared server: whichever runs first stores its bootstrap secret "
                        + "and the other is refused as invalid_client, in a full run only. "
                        + "Give one of them a code of its own. " + shared);
    }

    @Test
    @DisplayName("every spec a class writes names its tenant somewhere this can read, "
            + "because a scan that skips what it cannot read stops covering it")
    void everyDeclarationIsReadable() throws Exception {
        Map<String, java.util.List<String>> unreadable =
                TenantCodes.read(harness()).unreadable();
        assertTrue(unreadable.isEmpty(),
                "a tenant spec is written under a name this check cannot resolve, so nothing "
                        + "would notice a second class taking that code. Write it where it can "
                        + "be seen — a literal, a field, a local, or an argument at the call "
                        + "site: " + unreadable);
    }

    /** The harness sources, wherever the test happens to be run from. */
    private static Path harness() {
        Path fromModule = HARNESS;
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("core/harness").resolve(HARNESS);
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("the harness sources are not where this expected: "
                + fromModule.toAbsolutePath() + " nor " + fromRoot.toAbsolutePath());
    }
}
