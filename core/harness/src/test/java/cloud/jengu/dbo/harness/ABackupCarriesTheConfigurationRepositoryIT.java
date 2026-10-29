package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.postgres.PgObjectStore;
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
import org.eclipse.jgit.lib.RefUpdate;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's configuration repository, carried by its backup.
 *
 * <p>The store keeps the repository and nothing else does, so a backup that
 * left it out restored a tenant without the configuration that made it what it
 * is. The backup found what to carry by looking for each domain's tables, and
 * the repository's schema is no domain's.
 */
class ABackupCarriesTheConfigurationRepositoryIT {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a tenant restored from its backup holds its configuration repository as it "
            + "was, every object and the branch it applies")
    @Proving(DboPromises.TEN_A_TENANTS_CONFIGURATION_REPOSITORY_IS_KEPT_IN_ITS_DATABASE)
    void theRepositoryIsRestored() throws Exception {
        byte[] ownerKey = new byte[32];
        new SecureRandom().nextBytes(ownerKey);
        PGSimpleDataSource source = database("config_backup_source");
        PGSimpleDataSource target = database("config_backup_target");
        List<cloud.jengu.dbo.core.api.TypeRegistration> registrations = new R4Personality(
                List.of(FhirTypeConfig.internal("Observation"))).registrations();
        new PgObjectStore(source, registrations);

        ObjectId tip;
        Set<ObjectId> reachable = new HashSet<>();
        try (Git onDisk = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
             StoredRepository stored = StoredRepository.open(source, "clinic")) {
            Files.writeString(dir.resolve("ward-7.json"),
                    "{\"resourceType\":\"Organization\",\"name\":\"Ward 7\"}");
            onDisk.add().addFilepattern(".").call();
            tip = onDisk.commit().setMessage("Open ward 7")
                    .setAuthor(new PersonIdent("Owner", "owner@example.org"))
                    .setCommitter(new PersonIdent("Owner", "owner@example.org")).call();
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            try (PackWriter writer = new PackWriter(onDisk.getRepository())) {
                writer.preparePack(NullProgressMonitor.INSTANCE, Set.of(tip), Set.of());
                writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE,
                        pack);
            }
            try (ObjectInserter inserter = stored.newObjectInserter()) {
                PackParser parser = inserter.newPackParser(
                        new ByteArrayInputStream(pack.toByteArray()));
                parser.parse(NullProgressMonitor.INSTANCE);
                inserter.flush();
            }
            RefUpdate main = stored.updateRef(Constants.R_HEADS + "main");
            main.setExpectedOldObjectId(ObjectId.zeroId());
            main.setNewObjectId(tip);
            assertEquals(RefUpdate.Result.NEW, main.update());
            try (ObjectWalk walk = new ObjectWalk(onDisk.getRepository())) {
                walk.markStart(walk.parseCommit(tip));
                for (RevCommit commit; (commit = walk.next()) != null; ) {
                    reachable.add(commit.copy());
                }
                for (RevObject object; (object = walk.nextObject()) != null; ) {
                    reachable.add(object.copy());
                }
            }

            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            TenantExport.export(source, R4Personality.DOMAIN, ownerKey, archive);
            new PgObjectStore(target, registrations);
            assertFalse(StoredRepository.exists(target), "the target held a repository before "
                    + "anything was restored into it");
            CoSignedArchive.over(archive.toByteArray(), ownerKey)
                    .restoreFidelityInto(target, R4Personality.DOMAIN, ownerKey);

            assertTrue(StoredRepository.exists(target), "the backup did not carry the repository");
            try (StoredRepository restored = StoredRepository.open(target, "clinic")) {
                assertEquals(tip, restored.resolve(Constants.R_HEADS + "main"));
                assertEquals(Constants.R_HEADS + "main",
                        restored.exactRef(Constants.HEAD).getTarget().getName());
                for (ObjectId id : reachable) {
                    assertArrayEquals(onDisk.getRepository().open(id).getBytes(),
                            restored.open(id).getBytes(), "the object " + id.name()
                                    + " was restored differently");
                }
            }
        }
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("ABackupCarriesTheConfigurationRepositoryIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }
}
