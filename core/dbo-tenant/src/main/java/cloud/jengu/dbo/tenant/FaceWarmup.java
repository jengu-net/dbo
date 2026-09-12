package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.definitions.FaceFunctions;
import cloud.jengu.dbo.definitions.FaceImage;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Cutting a face, once, where the release is made.
 *
 * <p>A face root reads the packages a release carries, expands every structure
 * and imports every vocabulary. That is half a minute, and its answer is the
 * same for every tenant that will ever come up on the face. Warmup is the step
 * that pays it once: it takes an image of a root that has finished, and every
 * tenant afterwards is brought up from the bytes.
 *
 * <p><b>Only from a root that has finished.</b> An image cut from a root
 * halfway through loading is the failure this design exists to avoid, and it
 * is a quiet one: the rows that are there load perfectly, and a tenant brought
 * up from them holds most of a specification and reports nothing. So warmup
 * asks whether the root is serving and whether anything is still queued for
 * it, and cuts nothing when the answer is no.
 *
 * <p><b>One at a time, per database.</b> Two warmups cutting the same root at
 * once would each take a consistent image, so the lock is not about
 * correctness of the bytes — it is about the cost. Cutting is the one moment a
 * root is read whole, and doing it twice concurrently on one instance buys
 * nothing and competes for the same pages.
 *
 * <p><b>The operator holds what this writes.</b> Warmup runs when a release is
 * made, not when a pod boots, and the images live wherever the operator keeps
 * them — the same arrangement as secrets and sealed archives. This writes one
 * file and says where; scheduling it, keeping it and handing it to pods are
 * not the store's to do. There is nothing to hide in one: a face is a public
 * specification plus what a zone declares, and carries no person.
 */
public final class FaceWarmup {

    private static final Logger LOG = LoggerFactory.getLogger(FaceWarmup.class);

    /** "dbo_warm" — one cutting at a time on one database. */
    private static final long LOCK_KEY = 0x64626F5F7761726DL;

    private FaceWarmup() {}

    /** What was cut, or why nothing was. */
    public sealed interface Outcome {
        record Cut(Path image, FaceImage.Manifest manifest, long millis) implements Outcome {}

        record NotYet(String why) implements Outcome {}
    }

    /**
     * Cuts an image of one face root into {@code directory}.
     *
     * <p>Named for the face and written through a temporary file, so a reader
     * never opens one that is still being written and a cut that dies leaves
     * no file rather than half of one.
     */
    public static Outcome cut(TenantRuntimeManager manager, String rootCode, Path directory)
            throws IOException {
        TenantRuntimeManager.TenantRuntime root = manager.runtime(rootCode).orElse(null);
        if (root == null) {
            return new Outcome.NotYet("tenant '" + rootCode + "' is not serving");
        }
        if (!root.spec().faceRoot()) {
            return new Outcome.NotYet("tenant '" + rootCode + "' is not a face root, so what it "
                    + "holds is its own rather than the face's");
        }
        DataSource source = manager.databaseOf(rootCode).orElse(null);
        if (source == null) {
            return new Outcome.NotYet("tenant '" + rootCode + "' has no database yet");
        }

        String face = root.spec().face();
        Files.createDirectories(directory);
        Path image = directory.resolve(face + ".faceimage");
        Path partial = directory.resolve(face + ".faceimage.cutting");

        try (Connection lock = source.getConnection()) {
            if (!taken(lock)) {
                return new Outcome.NotYet("another cutting of this face is already running");
            }
            try {
                String queued = stillQueued(manager, rootCode);
                if (queued != null) {
                    return new Outcome.NotYet(queued);
                }
                FaceImage.Facts facts = new FaceImage.Facts(FaceRootPackages.carried(face), face,
                        FaceFunctions.installedIn(source), DefinitionStore.SHAPE);
                // Read before the dump and under the lock: nothing is writing
                // to a drained root, so the position and the rows are the same
                // instant.
                String cursor = cursorOf(root.definitionsFeed());

                long began = System.currentTimeMillis();
                FaceImage.Manifest manifest;
                try (OutputStream out = Files.newOutputStream(partial)) {
                    manifest = FaceImage.cut(source, facts, cursor, out);
                }
                Files.move(partial, image, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                long millis = System.currentTimeMillis() - began;
                LOG.info("face {} was cut for bringing tenants up: rows={} bytes={} in {}ms at {}",
                        face, manifest.rows(), Files.size(image), millis, image);
                return new Outcome.Cut(image, manifest, millis);
            } finally {
                Files.deleteIfExists(partial);
                release(lock);
            }
        } catch (SQLException e) {
            throw new IOException("the face could not be cut", e);
        }
    }

    /**
     * Whether the root has everything the face gives it.
     *
     * <p>Serving is the first half: a root that is serving has read its
     * packages through, because that is what it does before it serves. The
     * second half is anything it subscribes to — a zone publishing onto the
     * face — which is drained rather than asked about, since draining it is
     * what makes the answer true instead of merely reporting that it is not.
     *
     * @return why it is not ready, or null when nothing is outstanding
     */
    private static String stillQueued(TenantRuntimeManager manager, String rootCode) {
        boolean serving = manager.tenantStates().stream()
                .anyMatch(t -> t.code().equals(rootCode)
                        && t.state() == TenantState.State.SERVING);
        if (!serving) {
            return "the root is not serving yet, so what it holds is part of a face";
        }
        for (cloud.jengu.dbo.sync.ContentSyncEngine stream : manager.streamsOf(rootCode)) {
            int carried = 0;
            int events;
            do {
                events = stream.syncOnce(500);
                carried += events;
            } while (events > 0);
            if (carried > 0) {
                LOG.info("face root {} took {} outstanding change(s) from '{}' before it was cut",
                        rootCode, carried, stream.name());
            }
        }
        return null;
    }

    private static String cursorOf(ChangeFeed feed) {
        return feed instanceof cloud.jengu.dbo.postgres.PgChangeFeed pg ? pg.headCursor() : null;
    }

    private static boolean taken(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void release(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.execute();
        } catch (SQLException ignored) {
            // the connection is closing, and the lock goes with it
        }
    }
}
