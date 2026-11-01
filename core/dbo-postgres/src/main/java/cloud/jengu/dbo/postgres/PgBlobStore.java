package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.core.api.BlobStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Blobs in the tenant's own database, which is what makes erasure reach them.
 *
 * <p>The fallback of the three tiers and the one every deployment has. It
 * needs nothing provisioned and no credential of its own: it writes through
 * the same data source the tenant's records go through, so there is no second
 * place holding somebody's content and no second secret to rotate, lose or
 * forget to revoke.
 *
 * <p><b>Erasure is not a sweep here.</b> Dropping a tenant is
 * {@code DROP DATABASE}, and these rows are in it — so they go for the same
 * reason the records do, rather than because something remembered to delete
 * them. A store that held them anywhere else would need erasure to reach a
 * second system, and reaching it would be a step that can fail quietly.
 *
 * <p>Held as {@code bytea} and handed back unexamined. Nothing here parses,
 * transcodes or normalises content: what a writer put in is what a reader
 * gets, byte for byte, because the point of keeping something whole is that
 * it is still the thing somebody signed.
 */
public final class PgBlobStore implements BlobStore {

    private final DataSource ds;

    public PgBlobStore(DataSource dataSource) {
        this.ds = dataSource;
    }

    /**
     * Beside the tenant's other state, deliberately. A schema of its own would
     * be one more thing a backup, an export or an erasure has to be told
     * about.
     */
    static void ensure(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                CREATE TABLE IF NOT EXISTS state.blob (
                  key         uuid PRIMARY KEY,
                  media       text NOT NULL,
                  content     bytea NOT NULL,
                  person      uuid,
                  written_at  timestamptz NOT NULL DEFAULT now())""")) {
            ps.execute();
        }
        // The kind says how long it is kept, so the sweep asks by kind and age.
        try (PreparedStatement ps = c.prepareStatement(
                "ALTER TABLE state.blob ADD COLUMN IF NOT EXISTS kind text")) {
            ps.execute();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "ALTER TABLE state.blob ADD COLUMN IF NOT EXISTS sealed_window date")) {
            ps.execute();
        }
        try (PreparedStatement ps = c.prepareStatement("CREATE INDEX IF NOT EXISTS blob_kind_age"
                + " ON state.blob (kind, written_at) WHERE kind IS NOT NULL")) {
            ps.execute();
        }
    }

    @Override
    public String put(byte[] content, String media, String person, String kind) {
        return kept(content, media, person, kind, null);
    }

    @Override
    public String putSealed(byte[] sealed, String media, String kind,
            java.time.LocalDate window) {
        if (kind == null || window == null) {
            throw new IllegalArgumentException("content sealed to a window names its kind "
                    + "and its day, or nothing could find the key to open it");
        }
        return kept(sealed, media, null, kind, window);
    }

    @Override
    public String put(byte[] content, String media, String person) {
        return kept(content, media, person, null, null);
    }

    @Override
    public String put(byte[] content, String media) {
        return kept(content, media, null, null, null);
    }

    private String kept(byte[] content, String media, String person, String kind,
            java.time.LocalDate window) {
        if (content == null) {
            throw new IllegalArgumentException("a blob is bytes; there are none here");
        }
        // The store's to choose, and time-ordered like every other id here, so
        // what a tenant holds reads in the order it arrived.
        String key = UuidV7.newId();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO state.blob (key, media, content, person, kind, sealed_window)"
                             + " VALUES (?::uuid, ?, ?, ?::uuid, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, media == null || media.isBlank()
                    ? "application/octet-stream" : media);
            ps.setBytes(3, content);
            // Whose it is, and nothing about what it is: this store keeps the
            // bytes it was handed, sealed or not, and the name of the person
            // they are about so whoever CAN unseal them knows which key to
            // ask for.
            ps.setString(4, person);
            ps.setString(5, kind);
            ps.setObject(6, window);
            ps.executeUpdate();
            return key;
        } catch (SQLException e) {
            throw failed("keeping a blob", e);
        }
    }

    @Override
    public void restore(String key, byte[] content, String media) {
        restore(key, content, media, null);
    }

    @Override
    public void restore(String key, byte[] content, String media, String kind) {
        if (!isKey(key)) {
            throw new IllegalArgumentException("not a key this store could have issued: " + key);
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO state.blob (key, media, content, kind) VALUES (?::uuid, ?, ?, ?)"
                             + " ON CONFLICT (key) DO UPDATE SET media = EXCLUDED.media,"
                             + " content = EXCLUDED.content, kind = EXCLUDED.kind")) {
            // Idempotent, because a restore is retried: an import that failed
            // half way is run again, and the second pass must land on the same
            // content rather than refuse what it already put there.
            ps.setString(1, key);
            ps.setString(2, media == null || media.isBlank()
                    ? "application/octet-stream" : media);
            ps.setBytes(3, content);
            ps.setString(4, kind);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failed("putting a blob back", e);
        }
    }

    @Override
    public Optional<Blob> get(String key) {
        if (!isKey(key)) {
            // Not found rather than refused: a key this store did not issue
            // names nothing here, and telling the two apart would say whether
            // somebody else's key is well formed.
            return Optional.empty();
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT media, content, person::text, kind, sealed_window FROM state.blob"
                             + " WHERE key = ?::uuid")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Blob(key, rs.getString(1), rs.getBytes(2),
                                rs.getString(3), rs.getString(4),
                                rs.getObject(5, java.time.LocalDate.class)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw failed("reading a blob", e);
        }
    }

    @Override
    public boolean drop(String key) {
        if (!isKey(key)) {
            return false;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM state.blob WHERE key = ?::uuid")) {
            ps.setString(1, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failed("forgetting a blob", e);
        }
    }

    @Override
    public long count() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM state.blob");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw failed("counting blobs", e);
        }
    }

    private static boolean isKey(String key) {
        return key != null && key.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /** The SQLSTATE travels, and the statement does not — see PgObjectStore. */
    private static IllegalStateException failed(String what, SQLException e) {
        String state = e.getSQLState();
        return new IllegalStateException(state == null || state.isBlank()
                ? what + " failed"
                : what + " failed [SQLSTATE " + state + "]", e);
    }
}
