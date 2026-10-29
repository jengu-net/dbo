package cloud.jengu.dbo.tenant.git;

import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The repository's objects: packs, each a few files kept in chunks.
 *
 * <p>A file is written whole before it is kept, and kept before its pack is
 * listed: what a writer has not committed is in the chunk rows and nowhere a
 * reader looks, and a rollback takes it away again.
 */
final class StoredObjects extends DfsObjDatabase {

    /**
     * The largest file a pack may have. A configuration repository is an
     * organisation's records, one per file; a pack this size is somebody
     * pushing something that is not one, and is refused before it fills the
     * memory a file is written through.
     */
    static final long LARGEST_PACK_FILE = 256L * 1024 * 1024;

    private final DataSource ds;

    StoredObjects(StoredRepository repository, DataSource ds) {
        super(repository, new DfsReaderOptions());
        this.ds = ds;
    }

    @Override
    protected List<DfsPackDescription> listPacks() throws IOException {
        Map<String, StoredPack> packs = new LinkedHashMap<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT name, source, last_modified, object_count, delta_count, index_version,
                           min_update_index, max_update_index
                    FROM %s.pack""".formatted(StoredRepository.SCHEMA));
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StoredPack pack = new StoredPack(getRepository().getDescription(),
                            rs.getString(1), PackSource.valueOf(rs.getString(2)));
                    pack.setLastModified(rs.getLong(3));
                    pack.setObjectCount(rs.getLong(4));
                    pack.setDeltaCount(rs.getLong(5));
                    pack.setIndexVersion(rs.getInt(6));
                    pack.setMinUpdateIndex(rs.getLong(7));
                    pack.setMaxUpdateIndex(rs.getLong(8));
                    packs.put(pack.getPackName(), pack);
                }
            }
            if (packs.isEmpty()) {
                return List.of();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT pack, ext, size FROM %s.file WHERE pack = ANY(?)"""
                    .formatted(StoredRepository.SCHEMA))) {
                ps.setArray(1, c.createArrayOf("text", packs.keySet().toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        StoredPack pack = packs.get(rs.getString(1));
                        PackExt ext = PackExt.valueOf(rs.getString(2));
                        pack.addFileExt(ext);
                        pack.setFileSize(ext, rs.getLong(3));
                        pack.setBlockSize(ext, StoredRepository.CHUNK);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("the repository's packs could not be listed", e);
        }
        return new ArrayList<>(packs.values());
    }

    @Override
    public long getApproximateObjectCount() {
        try {
            long count = 0;
            for (DfsPackDescription pack : listPacks()) {
                count += pack.getObjectCount();
            }
            return count;
        } catch (IOException unreadable) {
            return -1;
        }
    }

    @Override
    protected DfsPackDescription newPack(PackSource source) {
        return new StoredPack(getRepository().getDescription(),
                "pack-" + UUID.randomUUID() + "-" + source.name(), source);
    }

    @Override
    protected void commitPackImpl(Collection<DfsPackDescription> desc,
            Collection<DfsPackDescription> replace) throws IOException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO %s.pack (name, source, last_modified, object_count,
                                             delta_count, index_version, min_update_index,
                                             max_update_index)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".formatted(StoredRepository.SCHEMA))) {
                    long now = System.currentTimeMillis();
                    for (DfsPackDescription pack : desc) {
                        if (pack.getLastModified() == 0) {
                            pack.setLastModified(now);
                        }
                        ps.setString(1, pack.getPackName());
                        ps.setString(2, pack.getPackSource().name());
                        ps.setLong(3, pack.getLastModified());
                        ps.setLong(4, pack.getObjectCount());
                        ps.setLong(5, pack.getDeltaCount());
                        ps.setInt(6, pack.getIndexVersion());
                        ps.setLong(7, pack.getMinUpdateIndex());
                        ps.setLong(8, pack.getMaxUpdateIndex());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                if (replace != null && !replace.isEmpty()) {
                    remove(c, replace);
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("the pack could not be committed", e);
        }
        clearCache();
    }

    @Override
    protected void rollbackPack(Collection<DfsPackDescription> desc) {
        try (Connection c = ds.getConnection()) {
            remove(c, desc);
        } catch (SQLException e) {
            // Never listed, so nothing reads it: what is left is rows, and the
            // next garbage collection's to take away.
        }
    }

    private static void remove(Connection c, Collection<DfsPackDescription> packs)
            throws SQLException {
        Object[] names = packs.stream().map(DfsPackDescription::getPackName).toArray();
        for (String table : List.of("chunk", "file", "pack")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM %s.%s WHERE %s = ANY(?)".formatted(StoredRepository.SCHEMA,
                            table, "pack".equals(table) ? "name" : "pack"))) {
                ps.setArray(1, c.createArrayOf("text", names));
                ps.executeUpdate();
            }
        }
    }

    @Override
    protected ReadableChannel openFile(DfsPackDescription desc, PackExt ext)
            throws IOException {
        if (!desc.hasFileExt(ext)) {
            throw new FileNotFoundException(desc.getFileName(ext));
        }
        return new Chunks(desc.getPackName(), ext, desc.getFileSize(ext));
    }

    @Override
    protected DfsOutputStream writeFile(DfsPackDescription desc, PackExt ext) {
        return new Written(desc, ext);
    }

    /** One file, read a chunk at a time from wherever it is asked to be read. */
    private final class Chunks implements ReadableChannel {

        private final String pack;
        private final PackExt ext;
        private final long size;
        private long position;
        private boolean open = true;
        private int held = -1;
        private byte[] chunk;

        Chunks(String pack, PackExt ext, long size) {
            this.pack = pack;
            this.ext = ext;
            this.size = size;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (position >= size) {
                return -1;
            }
            int n = (int) (position / StoredRepository.CHUNK);
            if (n != held) {
                chunk = load(n);
                held = n;
            }
            int offset = (int) (position % StoredRepository.CHUNK);
            int length = Math.min(dst.remaining(), chunk.length - offset);
            if (length <= 0) {
                throw new IOException(pack + "." + ext.getExtension() + " ends at chunk " + n
                        + " before the " + size + " bytes it was kept as");
            }
            dst.put(chunk, offset, length);
            position += length;
            return length;
        }

        private byte[] load(int n) throws IOException {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT bytes FROM %s.chunk WHERE pack = ? AND ext = ? AND n = ?"""
                         .formatted(StoredRepository.SCHEMA))) {
                ps.setString(1, pack);
                ps.setString(2, ext.name());
                ps.setInt(3, n);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new FileNotFoundException(pack + "." + ext.getExtension()
                                + " has no chunk " + n);
                    }
                    return rs.getBytes(1);
                }
            } catch (SQLException e) {
                throw new IOException("a chunk of " + pack + " could not be read", e);
            }
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public void position(long newPosition) {
            position = newPosition;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int blockSize() {
            return StoredRepository.CHUNK;
        }

        @Override
        public void setReadAheadBytes(int bufferSize) {
            // Each block is one row, asked for when it is read.
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /** One file being written, kept in chunks when it is closed. */
    private final class Written extends DfsOutputStream {

        private final DfsPackDescription desc;
        private final PackExt ext;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean kept;

        Written(DfsPackDescription desc, PackExt ext) {
            this.desc = desc;
            this.ext = ext;
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            if (bytes.size() + (long) len > LARGEST_PACK_FILE) {
                throw new IOException("a pack file larger than " + LARGEST_PACK_FILE
                        + " bytes is not a configuration repository's");
            }
            bytes.write(buf, off, len);
        }

        @Override
        public int read(long position, ByteBuffer buf) {
            byte[] written = bytes.toByteArray();
            int n = Math.min(buf.remaining(), written.length - (int) position);
            if (n <= 0) {
                return -1;
            }
            buf.put(written, (int) position, n);
            return n;
        }

        @Override
        public int blockSize() {
            return StoredRepository.CHUNK;
        }

        @Override
        public void close() throws IOException {
            if (kept) {
                return;
            }
            kept = true;
            byte[] written = bytes.toByteArray();
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO %s.chunk (pack, ext, n, bytes) VALUES (?, ?, ?, ?)"""
                            .formatted(StoredRepository.SCHEMA))) {
                        for (int n = 0, at = 0; at < written.length || n == 0;
                                n++, at += StoredRepository.CHUNK) {
                            int length = Math.min(StoredRepository.CHUNK, written.length - at);
                            byte[] piece = new byte[length];
                            System.arraycopy(written, at, piece, 0, length);
                            ps.setString(1, desc.getPackName());
                            ps.setString(2, ext.name());
                            ps.setInt(3, n);
                            ps.setBytes(4, piece);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO %s.file (pack, ext, size) VALUES (?, ?, ?)"""
                            .formatted(StoredRepository.SCHEMA))) {
                        ps.setString(1, desc.getPackName());
                        ps.setString(2, ext.name());
                        ps.setLong(3, written.length);
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw new IOException("a file of " + desc.getPackName() + " could not be kept", e);
            }
            desc.addFileExt(ext);
            desc.setFileSize(ext, written.length);
            desc.setBlockSize(ext, StoredRepository.CHUNK);
        }
    }

    /** A pack as the database lists it. */
    private static final class StoredPack extends DfsPackDescription {

        StoredPack(org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription repository,
                String name, PackSource source) {
            super(repository, name, source);
        }
    }
}
