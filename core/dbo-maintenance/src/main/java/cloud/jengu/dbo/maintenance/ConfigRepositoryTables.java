package cloud.jengu.dbo.maintenance;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Where a tenant's configuration repository is kept in its database, written
 * down once for the repository that keeps it and the restore that brings it
 * back.
 *
 * <p>The repository is the store's to keep and is kept nowhere else, so a
 * backup that left it out would restore a tenant without the configuration
 * that made it what it is, and nothing would say so. Its tables are therefore
 * carried by every backup that finds them, and made by every restore that
 * brings them, from this one description rather than two that could differ.
 */
public final class ConfigRepositoryTables {

    /** The schema the repository is kept in, inside the tenant's database. */
    public static final String SCHEMA = "config_git";

    /** Its tables: packs, their files, the files' chunks, and refs. */
    public static final List<String> TABLES = List.of("pack", "file", "chunk", "ref");

    private ConfigRepositoryTables() {
    }

    /** Makes the schema and its tables where they are not yet. */
    public static void ensure(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
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
        }
    }
}
