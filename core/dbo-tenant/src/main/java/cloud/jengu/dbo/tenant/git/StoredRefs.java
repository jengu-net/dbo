package cloud.jengu.dbo.tenant.git;

import org.eclipse.jgit.internal.storage.dfs.DfsRefDatabase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.util.RefList;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The repository's refs, one row each.
 *
 * <p>An update names the value it replaces in its own WHERE clause, so it lands
 * only if nobody moved the ref since it was read: the database decides, not a
 * lock held in one process, and a node beside this one is held to the same.
 */
final class StoredRefs extends DfsRefDatabase {

    private final DataSource ds;

    StoredRefs(StoredRepository repository, DataSource ds) {
        super(repository);
        this.ds = ds;
    }

    @Override
    protected RefCache scanAllRefs() throws IOException {
        RefList.Builder<Ref> ids = new RefList.Builder<>();
        RefList.Builder<Ref> symbolic = new RefList.Builder<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, target, symbolic, peeled, is_peeled FROM %s.ref"
                             .formatted(StoredRepository.SCHEMA));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Ref ref = ref(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5));
                if (ref.isSymbolic()) {
                    symbolic.add(ref);
                }
                ids.add(ref);
            }
        } catch (SQLException e) {
            throw new IOException("the repository's refs could not be read", e);
        }
        ids.sort();
        symbolic.sort();
        return new RefCache(ids.toRefList(), symbolic.toRefList());
    }

    private static Ref ref(String name, String target, String symbolic, String peeled,
            boolean isPeeled) {
        if (symbolic != null) {
            return new SymbolicRef(name,
                    new ObjectIdRef.Unpeeled(Ref.Storage.NEW, symbolic, null));
        }
        ObjectId id = ObjectId.fromString(target);
        if (!isPeeled) {
            return new ObjectIdRef.Unpeeled(Ref.Storage.PACKED, name, id);
        }
        return peeled == null
                ? new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, name, id)
                : new ObjectIdRef.PeeledTag(Ref.Storage.PACKED, name, id,
                        ObjectId.fromString(peeled));
    }

    @Override
    protected boolean compareAndPut(Ref oldRef, Ref newRef) throws IOException {
        String target = newRef.isSymbolic() ? null : newRef.getObjectId().name();
        String symbolic = newRef.isSymbolic() ? newRef.getTarget().getName() : null;
        String peeled = newRef.getPeeledObjectId() == null ? null
                : newRef.getPeeledObjectId().name();
        try (Connection c = ds.getConnection()) {
            if (oldRef == null || oldRef.getStorage() == Ref.Storage.NEW) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO %s.ref (name, target, symbolic, peeled, is_peeled)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (name) DO NOTHING"""
                        .formatted(StoredRepository.SCHEMA))) {
                    ps.setString(1, newRef.getName());
                    ps.setString(2, target);
                    ps.setString(3, symbolic);
                    ps.setString(4, peeled);
                    ps.setBoolean(5, newRef.isPeeled());
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE %s.ref SET target = ?, symbolic = ?, peeled = ?, is_peeled = ?
                    WHERE name = ? AND %s""".formatted(StoredRepository.SCHEMA,
                    oldRef.isSymbolic() ? "symbolic = ?" : "target = ?"))) {
                ps.setString(1, target);
                ps.setString(2, symbolic);
                ps.setString(3, peeled);
                ps.setBoolean(4, newRef.isPeeled());
                ps.setString(5, newRef.getName());
                ps.setString(6, was(oldRef));
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IOException("the ref " + newRef.getName() + " could not be written", e);
        }
    }

    @Override
    protected boolean compareAndRemove(Ref oldRef) throws IOException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM %s.ref WHERE name = ? AND %s".formatted(
                             StoredRepository.SCHEMA,
                             oldRef.isSymbolic() ? "symbolic = ?" : "target = ?"))) {
            ps.setString(1, oldRef.getName());
            ps.setString(2, was(oldRef));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IOException("the ref " + oldRef.getName() + " could not be removed", e);
        }
    }

    /**
     * No ref keeps a log here. Who moved the applied branch, to what and why
     * is the apply's run; a second account beside it would be one nobody
     * reads.
     */
    @Override
    public org.eclipse.jgit.lib.ReflogReader getReflogReader(Ref ref) {
        return new org.eclipse.jgit.lib.ReflogReader() {
            @Override
            public org.eclipse.jgit.lib.ReflogEntry getLastEntry() {
                return null;
            }

            @Override
            public java.util.List<org.eclipse.jgit.lib.ReflogEntry> getReverseEntries() {
                return java.util.List.of();
            }

            @Override
            public org.eclipse.jgit.lib.ReflogEntry getReverseEntry(int number) {
                return null;
            }

            @Override
            public java.util.List<org.eclipse.jgit.lib.ReflogEntry> getReverseEntries(int max) {
                return java.util.List.of();
            }
        };
    }

    /** What a ref was read as: the ref it names, or the object it points at. */
    private static String was(Ref ref) {
        if (ref.isSymbolic()) {
            return ref.getTarget().getName();
        }
        return ref.getObjectId() == null ? null : ref.getObjectId().name();
    }
}
