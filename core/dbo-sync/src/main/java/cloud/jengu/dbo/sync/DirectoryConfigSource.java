package cloud.jengu.dbo.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A directory of files, read as a declared set.
 *
 * <p>The plainest source there is, and in cluster it is two: a ConfigMap
 * mounted into a pod <i>is</i> a directory, so the operator's declarations and
 * an administrator's checked-out files arrive by the same reader rather than by
 * two that have to agree about what a partial read means.
 *
 * <p>Each file is one declaration, named by its file name — what somebody has
 * to open to fix it, which is what a card has to be able to say.
 */
public final class DirectoryConfigSource implements ConfigSource {

    private final Path directory;
    private final String typeName;
    private final String suffix;

    /**
     * @param typeName what a file in this directory declares. The directory
     *                 does not know and cannot be asked: a name is a name, and
     *                 the caller is the one that put them there.
     */
    public DirectoryConfigSource(Path directory, String typeName, String suffix) {
        this.directory = directory;
        this.typeName = typeName;
        this.suffix = suffix;
    }

    @Override
    public Fetch fetch() {
        List<ConfigApplication.Declared> declarations = new ArrayList<>();
        // Sorted, so the marker is a property of the content rather than of
        // the order a filesystem happened to answer in — otherwise an
        // unchanged directory would read as changed on somebody else's disk.
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(suffix))
                    .sorted().toList()) {
                declarations.add(new ConfigApplication.Declared(typeName,
                        file.getFileName().toString(), Files.readAllBytes(file)));
            }
        } catch (IOException unreadable) {
            // Never an empty read. A directory that cannot be listed and a
            // directory with nothing in it are the same answer to whoever then
            // has to decide what is missing, and deciding that wrongly is how
            // an unreadable mount becomes a withdrawal of everything.
            throw new UncheckedIOException("the declaration directory could not be read: "
                    + directory, unreadable);
        }
        return new Fetch(declarations, ConfigSource.markerOf(declarations));
    }
}
