package cloud.jengu.dbo.tenant;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A tenant owner's configuration repository, read at a commit.
 *
 * <p><b>From git's objects, never a working tree.</b> Nothing is checked out,
 * so nothing a commit says about the file system — a symlink, a submodule, a
 * filter, a hook — is ever acted on: the repository is input from outside the
 * deployment, and the only thing taken from it is the bytes of the files it
 * declares, as git holds them.
 *
 * <p>Read and never fetched: keeping the repository current is the
 * deployment's, and the store reaches no remote.
 */
final class ConfigRepository implements AutoCloseable {

    /** Larger than any declaration somebody wrote by hand, and far from a data dump. */
    static final long LARGEST_FILE = 4L * 1024 * 1024;

    /** More files than an organisation's configuration, and a bound on a pass. */
    static final int MOST_FILES = 20_000;

    /** A commit as a person would name it: its id, who made it, and why. */
    record Commit(String id, String author, String message) {
    }

    /** A file the commit declares, by the blob that holds it. */
    record File(String path, String blob) {
    }

    private final Repository repository;

    private ConfigRepository(Repository repository) {
        this.repository = repository;
    }

    /**
     * The repository at a path: a bare one, or a clone whose objects are
     * beside its working tree, which is never read.
     *
     * @throws IllegalArgumentException when there is no repository there, or
     *                                  it borrows objects from elsewhere
     */
    static ConfigRepository open(Path path) {
        Path gitDir = Files.isDirectory(path.resolve(".git")) ? path.resolve(".git") : path;
        if (!Files.isDirectory(gitDir.resolve("objects"))) {
            throw new IllegalArgumentException("no git repository at " + path);
        }
        // An alternates file makes a repository read objects from another
        // directory, which may be anywhere the deployment can read — so a
        // repository the owner wrote could name files the owner may not.
        if (Files.exists(gitDir.resolve("objects/info/alternates"))) {
            throw new IllegalArgumentException("the repository at " + path + " borrows "
                    + "objects from elsewhere (objects/info/alternates), and is read only "
                    + "from its own");
        }
        try {
            return new ConfigRepository(new FileRepositoryBuilder().setGitDir(gitDir.toFile())
                    .setMustExist(true).setup().build());
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalArgumentException("the repository at " + path
                    + " cannot be read: " + unreadable.getMessage(), unreadable);
        }
    }

    /**
     * A ref resolved to its commit: a branch, a tag, or a commit id.
     *
     * @throws IllegalArgumentException when it names no commit here
     */
    Commit resolve(String ref) {
        try {
            ObjectId id = repository.resolve(ref + "^{commit}");
            if (id == null) {
                throw new IllegalArgumentException("'" + ref + "' names no commit in the "
                        + "tenant's configuration repository");
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(id);
                return new Commit(commit.getName(), commit.getAuthorIdent().getName() + " <"
                        + commit.getAuthorIdent().getEmailAddress() + ">",
                        commit.getFullMessage().strip());
            }
        } catch (IOException | org.eclipse.jgit.errors.RevisionSyntaxException unreadable) {
            throw new IllegalArgumentException("'" + ref + "' cannot be resolved: "
                    + unreadable.getMessage(), unreadable);
        }
    }

    /**
     * The files a commit declares: every JSON file outside a hidden directory,
     * by path and blob. A file that is not one — a link, a submodule — and
     * claims to be a declaration is refused by name rather than followed.
     */
    List<File> files(Commit commit) {
        List<File> files = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(ObjectId.fromString(commit.id())).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (!path.endsWith(".json") || hidden(path)) {
                    continue;
                }
                FileMode mode = tree.getFileMode(0);
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                    throw new IllegalArgumentException(path + " in commit " + commit.id()
                            + " is a link or a submodule, not a file, and a declaration is "
                            + "read only from a file");
                }
                if (files.size() == MOST_FILES) {
                    throw new IllegalArgumentException("commit " + commit.id() + " declares "
                            + "more than " + MOST_FILES + " files");
                }
                files.add(new File(path, tree.getObjectId(0).getName()));
            }
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("commit " + commit.id() + " cannot be read: "
                    + unreadable.getMessage(), unreadable);
        }
        return files;
    }

    /**
     * One file's bytes, as git holds them.
     *
     * @throws IllegalArgumentException when it is larger than a declaration
     *                                  is, or a pointer to content kept
     *                                  elsewhere
     */
    byte[] read(File file) {
        try (ObjectReader reader = repository.newObjectReader()) {
            ObjectLoader loader = reader.open(ObjectId.fromString(file.blob()),
                    Constants.OBJ_BLOB);
            if (loader.getSize() > LARGEST_FILE) {
                throw new IllegalArgumentException(file.path() + " is " + loader.getSize()
                        + " bytes, and a declaration is at most " + LARGEST_FILE);
            }
            byte[] bytes = loader.getBytes();
            // Large File Storage keeps the content on a server and only this
            // pointer in the repository, and the store reaches no server.
            if (new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.UTF_8)
                    .startsWith("version https://git-lfs.github.com/spec/")) {
                throw new IllegalArgumentException(file.path() + " is a pointer to content "
                        + "kept by Large File Storage, which is never fetched");
            }
            return bytes;
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(file.path() + " cannot be read: "
                    + unreadable.getMessage(), unreadable);
        }
    }

    /**
     * One file at a commit by its path — the repository's own settings, such
     * as {@code .sops.yaml}, which are never declarations — or empty where the
     * commit has none.
     */
    java.util.Optional<byte[]> read(Commit commit, String path) {
        try (RevWalk walk = new RevWalk(repository)) {
            TreeWalk found = TreeWalk.forPath(repository, path,
                    walk.parseCommit(ObjectId.fromString(commit.id())).getTree());
            if (found == null) {
                return java.util.Optional.empty();
            }
            try (found) {
                if (found.getFileMode(0) != FileMode.REGULAR_FILE) {
                    throw new IllegalArgumentException(path + " in commit " + commit.id()
                            + " is not a file");
                }
                return java.util.Optional.of(read(new File(path,
                        found.getObjectId(0).getName())));
            }
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(path + " cannot be read: "
                    + unreadable.getMessage(), unreadable);
        }
    }

    /** Under a directory whose name starts with a dot: tooling, not configuration. */
    private static boolean hidden(String path) {
        for (String segment : path.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        repository.close();
    }
}
