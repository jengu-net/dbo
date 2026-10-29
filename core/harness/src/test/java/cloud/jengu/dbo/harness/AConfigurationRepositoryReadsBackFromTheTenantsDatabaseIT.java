package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.git.StoredRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.transport.PackParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's configuration repository, kept in the tenant's own database.
 *
 * <p>Kept through JGit's storage for repositories that are not on a file
 * system: packs in chunks, refs as rows. What is read back has to be what was
 * written, object for object and byte for byte, or the apply reads a different
 * configuration from the one the owner committed.
 */
class AConfigurationRepositoryReadsBackFromTheTenantsDatabaseIT {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a repository written into the tenant's database reads back object for object "
            + "as the same repository on disk, and a ref moves only from what it was")
    @Proving(DboPromises.TEN_A_TENANTS_CONFIGURATION_REPOSITORY_IS_KEPT_IN_ITS_DATABASE)
    void itReadsBackAsWritten() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AConfigurationRepositoryReadsBackFromTheTenantsDatabaseIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        ObjectId tip;
        Set<ObjectId> reachable = new java.util.HashSet<>();
        try (Git onDisk = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            commit(onDisk, "organisation.json", "{\"resourceType\":\"Organization\"}", "first");
            // Larger than a chunk and not compressible, so the pack spans
            // several and a read crosses from one into the next.
            byte[] noise = new byte[300 * 1024];
            new Random(7).nextBytes(noise);
            Files.write(dir.resolve("attachment.bin"), noise);
            commit(onDisk, "practitioners/one.json", "{\"resourceType\":\"Practitioner\"}",
                    "second");
            tip = commit(onDisk, "organisation.json",
                    "{\"resourceType\":\"Organization\",\"name\":\"Hogwarts\"}", "third");
            try (ObjectWalk walk = new ObjectWalk(onDisk.getRepository())) {
                walk.markStart(walk.parseCommit(tip));
                for (RevCommit commit; (commit = walk.next()) != null; ) {
                    reachable.add(commit.copy());
                }
                for (RevObject object; (object = walk.nextObject()) != null; ) {
                    reachable.add(object.copy());
                }
            }

            try (StoredRepository stored = StoredRepository.open(ds, "hogwarts")) {
                received(onDisk.getRepository(), stored, tip);
                RefUpdate main = stored.updateRef(Constants.R_HEADS + "main");
                main.setExpectedOldObjectId(ObjectId.zeroId());
                main.setNewObjectId(tip);
                assertEquals(RefUpdate.Result.NEW, main.update());
            }
        }

        try (StoredRepository again = StoredRepository.open(ds, "hogwarts");
             Repository onDisk = Git.open(dir.toFile()).getRepository()) {
            Ref head = again.exactRef(Constants.HEAD);
            assertEquals(Constants.R_HEADS + "main", head.getTarget().getName());
            assertEquals(tip, head.getObjectId(), "HEAD does not reach the branch it names");
            for (ObjectId id : reachable) {
                assertEquals(onDisk.open(id).getType(), again.open(id).getType(), id.name());
                assertArrayEquals(onDisk.open(id).getBytes(), again.open(id).getBytes(),
                        "the object " + id.name() + " read back differently");
            }
            assertTrue(reachable.size() >= 9, "fewer objects than three commits make: "
                    + reachable.size());
            try (java.sql.Connection c = ds.getConnection();
                 java.sql.ResultSet rs = c.createStatement().executeQuery(
                         "SELECT max(n) FROM " + StoredRepository.SCHEMA
                                 + ".chunk WHERE ext = 'PACK'")) {
                rs.next();
                assertTrue(rs.getInt(1) >= 1, "the pack was kept in one chunk, so no read "
                        + "crossed from one chunk into the next");
            }

            ObjectId parent = again.parseCommit(tip).getParent(0).copy();
            RefUpdate first = again.updateRef(Constants.R_HEADS + "main");
            first.setExpectedOldObjectId(tip);
            first.setNewObjectId(parent);
            first.setForceUpdate(true);
            RefUpdate stale = again.updateRef(Constants.R_HEADS + "main");
            stale.setExpectedOldObjectId(tip);
            stale.setNewObjectId(parent);
            stale.setForceUpdate(true);
            assertEquals(RefUpdate.Result.FORCED, first.update());
            assertEquals(RefUpdate.Result.LOCK_FAILURE, stale.update(),
                    "a ref moved from a value it no longer had");
        }
    }

    @Test
    @DisplayName("after push upon push, collecting garbage leaves fewer packs and every object "
            + "the branch reaches as it was")
    @Proving(DboPromises.TEN_A_TENANTS_CONFIGURATION_REPOSITORY_IS_KEPT_IN_ITS_DATABASE)
    void collectingGarbageKeepsWhatIsReached() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AConfigurationRepositoryReadsBackFromTheTenantsDatabaseIT_gc"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        Path disk = dir.resolve("collected");
        try (Git onDisk = Git.init().setDirectory(disk.toFile()).setInitialBranch("main").call();
             StoredRepository stored = StoredRepository.open(ds, "collected")) {
            ObjectId had = ObjectId.zeroId();
            for (int push = 1; push <= 6; push++) {
                Files.writeString(disk.resolve("ward-" + push + ".json"),
                        "{\"resourceType\":\"Organization\",\"name\":\"Ward " + push + "\"}");
                onDisk.add().addFilepattern(".").call();
                ObjectId tip = onDisk.commit().setMessage("Ward " + push)
                        .setAuthor(new PersonIdent("Owner", "owner@example.org"))
                        .setCommitter(new PersonIdent("Owner", "owner@example.org")).call();
                receivedSince(onDisk.getRepository(), stored, tip, had);
                RefUpdate main = stored.updateRef(Constants.R_HEADS + "main");
                main.setExpectedOldObjectId(had);
                main.setNewObjectId(tip);
                main.update();
                had = tip;
            }
            Set<ObjectId> reachable = reachable(onDisk.getRepository(), had);
            int before = stored.packs();
            assertTrue(before >= 6, "a push was not a pack of its own: " + before);

            StoredRepository.Collected collected = stored.collectGarbage();

            assertEquals(before, collected.packsBefore());
            assertTrue(collected.packsAfter() < before, "collecting left " + collected);
            try (StoredRepository again = StoredRepository.open(ds, "collected")) {
                assertEquals(had, again.resolve("refs/heads/main"));
                for (ObjectId id : reachable) {
                    assertArrayEquals(onDisk.getRepository().open(id).getBytes(),
                            again.open(id).getBytes(),
                            "the object " + id.name() + " read differently after collecting");
                }
            }
        }
    }

    private static Set<ObjectId> reachable(Repository repository, ObjectId tip) throws Exception {
        Set<ObjectId> reachable = new java.util.HashSet<>();
        try (ObjectWalk walk = new ObjectWalk(repository)) {
            walk.markStart(walk.parseCommit(tip));
            for (RevCommit commit; (commit = walk.next()) != null; ) {
                reachable.add(commit.copy());
            }
            for (RevObject object; (object = walk.nextObject()) != null; ) {
                reachable.add(object.copy());
            }
        }
        return reachable;
    }

    /** What a push sends: a pack of what the far side lacks since what it had. */
    private static void receivedSince(Repository from, Repository into, ObjectId tip,
            ObjectId had) throws Exception {
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(from)) {
            writer.preparePack(NullProgressMonitor.INSTANCE, Set.of(tip),
                    had.equals(ObjectId.zeroId()) ? Set.of() : Set.of(had));
            writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, pack);
        }
        try (ObjectInserter inserter = into.newObjectInserter()) {
            PackParser parser = inserter.newPackParser(
                    new ByteArrayInputStream(pack.toByteArray()));
            parser.setAllowThin(true);
            parser.parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
        }
    }

    /** What a fetch into the stored repository does: a pack of what it lacks, then parsed. */
    private static void received(Repository from, Repository into, ObjectId tip)
            throws Exception {
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(from)) {
            writer.preparePack(NullProgressMonitor.INSTANCE, Set.of(tip), Set.of());
            writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, pack);
        }
        try (ObjectInserter inserter = into.newObjectInserter()) {
            PackParser parser = inserter.newPackParser(
                    new ByteArrayInputStream(pack.toByteArray()));
            parser.setAllowThin(false);
            parser.parse(NullProgressMonitor.INSTANCE);
            inserter.flush();
        }
    }

    private RevCommit commit(Git git, String path, String content, String message)
            throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(".").call();
        PersonIdent owner = new PersonIdent("Owner", "owner@example.org");
        return git.commit().setMessage(message).setAuthor(owner).setCommitter(owner).call();
    }
}
