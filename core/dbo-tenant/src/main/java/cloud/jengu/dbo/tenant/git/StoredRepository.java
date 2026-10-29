package cloud.jengu.dbo.tenant.git;

import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A tenant's configuration repository, kept in the tenant's own database.
 *
 * <p>Through JGit's storage for repositories that are not on a file system:
 * what git calls the object database is a list of packs, each a few files, and
 * each file is kept in {@link #CHUNK}-sized rows, because a pack is read at
 * random positions and a file kept as one value would be read whole for every
 * object looked up. A pack is listed only once it is committed, and committing
 * one is a single transaction that adds it and takes away whatever it replaces.
 *
 * <p>Refs are rows of their own, updated by comparing the old value in the
 * update itself, so two writers anywhere update a ref one after the other or
 * the second is refused: never both.
 *
 * <p>Opened per use and cheap to open. What it caches, the pack list and the
 * refs, lasts as long as the instance, so a use that has to see another
 * writer's change opens it again.
 */
public final class StoredRepository extends DfsRepository {

    /** The schema the repository is kept in, inside the tenant's database. */
    public static final String SCHEMA = "config_git";

    /**
     * The size of a stored piece of a file, and the block git reads in: one
     * row per block read, never a whole file.
     */
    static final int CHUNK = 64 * 1024;

    /** The branch a new repository's HEAD names. */
    public static final String DEFAULT_BRANCH = Constants.R_HEADS + "main";

    private final StoredObjects objects;
    private final StoredRefs refs;

    private StoredRepository(Builder builder, DataSource ds) {
        super(builder);
        this.objects = new StoredObjects(this, ds);
        this.refs = new StoredRefs(this, ds);
    }

    /**
     * The repository kept in this database, made empty there if it is not yet.
     *
     * @param name what the repository is called while it is open, which keeps
     *             the blocks JGit caches apart from another tenant's
     */
    public static StoredRepository open(DataSource ds, String name) throws IOException {
        ensureSchema(ds);
        StoredRepository repository = new StoredRepository(
                new Builder().setRepositoryDescription(new DfsRepositoryDescription(name)), ds);
        if (repository.exactRef(Constants.HEAD) == null) {
            // Two openers of a new repository may both get here; one names
            // HEAD and the other's update is refused, which is the same end.
            RefUpdate.Result linked = repository.updateRef(Constants.HEAD, true)
                    .link(DEFAULT_BRANCH);
            repository.refs.refresh();
            if (repository.exactRef(Constants.HEAD) == null) {
                throw new IOException("the repository's HEAD could not be named: " + linked);
            }
        }
        return repository;
    }

    @Override
    public StoredObjects getObjectDatabase() {
        return objects;
    }

    @Override
    public StoredRefs getRefDatabase() {
        return refs;
    }

    private static void ensureSchema(DataSource ds) throws IOException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS %s.pack (
                      name text PRIMARY KEY,
                      source text NOT NULL,
                      last_modified bigint NOT NULL,
                      object_count bigint NOT NULL,
                      delta_count bigint NOT NULL,
                      index_version int NOT NULL,
                      min_update_index bigint NOT NULL,
                      max_update_index bigint NOT NULL
                    )""".formatted(SCHEMA));
            s.execute("""
                    CREATE TABLE IF NOT EXISTS %s.file (
                      pack text NOT NULL,
                      ext text NOT NULL,
                      size bigint NOT NULL,
                      PRIMARY KEY (pack, ext)
                    )""".formatted(SCHEMA));
            s.execute("""
                    CREATE TABLE IF NOT EXISTS %s.chunk (
                      pack text NOT NULL,
                      ext text NOT NULL,
                      n int NOT NULL,
                      bytes bytea NOT NULL,
                      PRIMARY KEY (pack, ext, n)
                    )""".formatted(SCHEMA));
            s.execute("""
                    CREATE TABLE IF NOT EXISTS %s.ref (
                      name text PRIMARY KEY,
                      target text,
                      symbolic text,
                      peeled text,
                      is_peeled boolean NOT NULL DEFAULT false,
                      CHECK ((target IS NULL) <> (symbolic IS NULL))
                    )""".formatted(SCHEMA));
        } catch (SQLException e) {
            throw new IOException("the configuration repository's tables could not be made", e);
        }
    }

    private static final class Builder extends DfsRepositoryBuilder<Builder, StoredRepository> {

        @Override
        public StoredRepository build() {
            throw new UnsupportedOperationException("opened with StoredRepository.open");
        }
    }
}
