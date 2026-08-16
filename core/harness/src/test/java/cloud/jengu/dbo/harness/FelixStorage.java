package cloud.jengu.dbo.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A Felix bundle cache that cleans up after itself.
 *
 * <p>The cache is a full copy of every installed bundle — with the HAPI
 * personalities that is ~500MB per framework — so a suite that starts
 * several frameworks leaks gigabytes per run into the temp directory. It
 * filled a 460GB disk (26GB of leftovers) mid-run, which presents as
 * containers dying and tests failing on refused connections rather than as
 * anything resembling a disk problem.
 *
 * <p>Deletion rides a shutdown hook rather than {@code @AfterAll}, because
 * the runs that leak most are the ones that failed before teardown.
 */
final class FelixStorage {

    private FelixStorage() {
    }

    static String directory(String prefix) throws IOException {
        Path storage = Files.createTempDirectory(prefix);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(storage)));
        return storage.toString();
    }

    private static void delete(Path storage) {
        try (Stream<Path> tree = Files.walk(storage)) {
            tree.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort — a leftover cache is not worth failing a run over
        }
    }
}
