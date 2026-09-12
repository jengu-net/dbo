package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.definitions.FaceFunctions;
import cloud.jengu.dbo.definitions.FaceImage;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half minute a face costs, paid once.
 *
 * <p>Every tenant on a face reads the whole of what that face publishes,
 * expands every structure and imports every vocabulary — and arrives at an
 * answer identical to the one every other tenant on that face arrived at.
 * Since that answer is now a schema, it can be cut once and handed over.
 *
 * <p>What this proves is the two ends of that: an image cut from a face root
 * loads into an empty database and puts the same rows there, and an image that
 * does not match this release is refused by name rather than loaded. The
 * second is the one that matters. Nothing about a wrong image fails on its
 * own — the rows load, the tenant serves, and it answers from a specification
 * or an expander that is not this one.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AFaceIsCutOnceAndBroughtUpFromIT {

    private static final String ROOT = "kujutis-juur";
    private static final String RELEASE = "test-release";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static byte[] image;
    static FaceImage.Manifest cut;
    static long cutMillis;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-face-image");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AFaceIsCutOnceAndBroughtUpFromIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(ROOT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(ROOT));
        UntilServed.scan(manager, ROOT);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long began = System.currentTimeMillis();
        cut = FaceImage.cut(rootSource(), facts(), "a-cursor-the-feed-minted", out);
        cutMillis = System.currentTimeMillis() - began;
        image = out.toByteArray();
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    @Timeout(600)
    @DisplayName("an image cut from a face puts the same rows in an empty database")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void anImageCutFromAFacePutsTheSameRowsBack() throws Exception {
        assertTrue(cut.rows() > 1000,
                "a face is the whole of what a version publishes, and this cut " + cut.rows());

        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema("face_image_target");
        long began = System.currentTimeMillis();
        FaceImage.Acceptance answer = FaceImage.accept(
                fresh, facts(), new ByteArrayInputStream(image));
        long acceptMillis = System.currentTimeMillis() - began;

        FaceImage.Acceptance.Accepted accepted = assertInstanceOf(
                FaceImage.Acceptance.Accepted.class, answer,
                answer instanceof FaceImage.Acceptance.Refused refused ? refused.why() : "");
        assertEquals(cut.cursor(), accepted.manifest().cursor(),
                "the position the face stood at did not travel with it, so a tenant brought "
                        + "up from this would not know where to catch up from");

        assertEquals(rowsPerTable(rootSource()), rowsPerTable(fresh),
                "a tenant brought up from the image does not hold what the face holds");

        // The number this whole issue is about. A face root's natural bring-up
        // is around half a minute; what replaces it for every tenant after the
        // first is the accept below.
        System.out.printf("METRICS faceImage rows=%d bytes=%d cutMs=%d acceptMs=%d%n",
                cut.rows(), image.length, cutMillis, acceptMillis);
        assertTrue(acceptMillis < 20_000,
                "bringing a face up from its image took " + acceptMillis + "ms, which is not "
                        + "obviously better than reading the whole face through a chain");
    }

    @Test
    @Timeout(600)
    @DisplayName("an image from another release is refused, and says which part disagreed")
    @Proving(DboPromises.VER_AN_IMAGE_FROM_ANOTHER_RELEASE_IS_REFUSED)
    void anImageFromAnotherReleaseIsRefused() throws Exception {
        assertRefused(new FaceImage.Facts(RELEASE, "r5", facts().faceSql(), facts().shape()),
                "r5", "a face's image came up on another face");
        assertRefused(new FaceImage.Facts(RELEASE, "r4", facts().faceSql(), facts().shape() + 1),
                "shape", "rows expanded by one expander were read by the checks of another");
        assertRefused(new FaceImage.Facts(RELEASE, "r4", "a-different-fingerprint",
                        facts().shape()),
                "face SQL", "rows shaped for one release's checks were read by another's");
        assertRefused(new FaceImage.Facts("some-other-release", "r4", facts().faceSql(),
                        facts().shape()),
                "release", "an image from another release was loaded anyway");
    }

    @Test
    @Timeout(600)
    @DisplayName("an image with no manifest is not an image")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void anImageWithNoManifestIsNotAnImage() throws Exception {
        byte[] halfWritten = withoutTheManifest(image);
        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema("face_image_half");

        FaceImage.Acceptance answer = FaceImage.accept(
                fresh, facts(), new ByteArrayInputStream(halfWritten));

        FaceImage.Acceptance.Refused refused = assertInstanceOf(
                FaceImage.Acceptance.Refused.class, answer,
                "an image a job died halfway through was loaded as though it were whole");
        assertTrue(refused.why().contains("manifest"), refused.why());
        assertEquals(0, rowsIn(fresh, Domains.tables(Domains.DEFINITIONS) + "_data"),
                "the refusal had already loaded some of it");
    }

    @Test
    @Timeout(600)
    @DisplayName("an image is brought up from, never merged into")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void anImageIsNotMergedIntoWhatIsAlreadyThere() throws Exception {
        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema("face_image_twice");
        assertInstanceOf(FaceImage.Acceptance.Accepted.class,
                FaceImage.accept(fresh, facts(), new ByteArrayInputStream(image)));

        FaceImage.Acceptance again = FaceImage.accept(
                fresh, facts(), new ByteArrayInputStream(image));

        FaceImage.Acceptance.Refused refused = assertInstanceOf(
                FaceImage.Acceptance.Refused.class, again,
                "the face was loaded twice, so every definition it holds is now there twice");
        assertTrue(refused.why().contains("already holds rows"), refused.why());
    }

    @Test
    @Timeout(600)
    @DisplayName("warmup cuts the face where the operator keeps it, and leaves nothing partial")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void warmupCutsTheFaceWhereTheOperatorKeepsIt() throws Exception {
        Path kept = Files.createTempDirectory("dbo-face-images");

        cloud.jengu.dbo.tenant.FaceWarmup.Outcome outcome =
                cloud.jengu.dbo.tenant.FaceWarmup.cut(manager, ROOT, kept);

        cloud.jengu.dbo.tenant.FaceWarmup.Outcome.Cut done = assertInstanceOf(
                cloud.jengu.dbo.tenant.FaceWarmup.Outcome.Cut.class, outcome,
                outcome instanceof cloud.jengu.dbo.tenant.FaceWarmup.Outcome.NotYet notYet
                        ? notYet.why() : "");
        assertTrue(Files.exists(done.image()), "warmup said it cut a face and wrote no file");
        assertEquals("r4", done.manifest().facts().face());
        assertTrue(done.manifest().rows() > 1000,
                "the face it cut holds " + done.manifest().rows() + " rows");
        assertTrue(done.manifest().cursor() != null && !done.manifest().cursor().isBlank(),
                "the image does not say where the face stood, so a tenant brought up from it "
                        + "would not know what it still has to catch up on");

        // Written through a temporary name so a reader never opens one that is
        // half there. Nothing of that may survive the cutting.
        try (var listed = Files.list(kept)) {
            assertEquals(java.util.List.of("r4.faceimage"),
                    listed.map(f -> f.getFileName().toString()).sorted().toList(),
                    "the cutting left something behind beside the image it wrote");
        }

        // And what warmup wrote is an image, on this release's own terms.
        assertInstanceOf(FaceImage.Acceptance.Accepted.class,
                FaceImage.accept(emptyDatabaseWithTheSchema("face_image_warmed"),
                        done.manifest().facts(),
                        new java.io.ByteArrayInputStream(Files.readAllBytes(done.image()))),
                "warmup wrote something this release would not accept");
    }

    @Test
    @Timeout(600)
    @DisplayName("what is not a face root is not cut into a face")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void whatIsNotAFaceRootIsNotCut() throws Exception {
        Path kept = Files.createTempDirectory("dbo-face-images-refused");

        cloud.jengu.dbo.tenant.FaceWarmup.Outcome outcome =
                cloud.jengu.dbo.tenant.FaceWarmup.cut(manager, "a-tenant-nobody-declared", kept);

        cloud.jengu.dbo.tenant.FaceWarmup.Outcome.NotYet notYet = assertInstanceOf(
                cloud.jengu.dbo.tenant.FaceWarmup.Outcome.NotYet.class, outcome,
                "a tenant that is not serving was cut into a face anyway");
        assertTrue(notYet.why().contains("a-tenant-nobody-declared"), notYet.why());
        try (var listed = Files.list(kept)) {
            assertEquals(java.util.List.of(), listed.toList(),
                    "nothing was cut and a file was written anyway");
        }
    }

    // ------------------------------------------------------------- helpers

    private void assertRefused(FaceImage.Facts expected, String namesThePart, String because)
            throws Exception {
        PGSimpleDataSource fresh = emptyDatabaseWithTheSchema(
                "face_image_refused_" + Math.abs(expected.hashCode()));
        FaceImage.Acceptance answer = FaceImage.accept(
                fresh, expected, new ByteArrayInputStream(image));

        FaceImage.Acceptance.Refused refused = assertInstanceOf(
                FaceImage.Acceptance.Refused.class, answer, because);
        assertTrue(refused.why().contains(namesThePart),
                "the refusal does not say which part disagreed: " + refused.why());
        assertEquals(0, rowsIn(fresh, Domains.tables(Domains.DEFINITIONS) + "_data"),
                "a refused image had already put rows in: " + refused.why());
    }

    private static FaceImage.Facts facts() {
        return new FaceImage.Facts(RELEASE, "r4",
                FaceFunctions.installedIn(rootSource()), DefinitionStore.SHAPE);
    }

    /** Every table of the definitions schema and how many rows it holds. */
    private static Map<String, Long> rowsPerTable(PGSimpleDataSource on) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = ? AND table_type = 'BASE TABLE'
                     ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), null);
                }
            }
        }
        for (Map.Entry<String, Long> table : counts.entrySet()) {
            table.setValue(rowsIn(on,
                    Domains.schema(Domains.DEFINITIONS) + "." + table.getKey()));
        }
        // What is about this tenant rather than about the face does not travel,
        // and comparing it would be comparing the two tenants instead.
        counts.keySet().removeIf(t -> t.contains("_outbox") || t.contains("_consumer")
                || t.contains("_sync_")
                // The shape marker is about the database, not the face: every
                // store writes its own as it is built, before any face arrives.
                || t.equals("definition_shape"));
        return counts;
    }

    private static long rowsIn(PGSimpleDataSource on, String qualified) throws Exception {
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + qualified);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * A database as a tenant's bring-up leaves it just before the face
     * arrives: the schema and its tables set up, and nothing in them.
     */
    private PGSimpleDataSource emptyDatabaseWithTheSchema(String name) throws Exception {
        String root = SharedPostgres.urlFor("AFaceIsCutOnceAndBroughtUpFromIT");
        try (Connection c = DriverManager.getConnection(root,
                postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement("CREATE DATABASE " + name)) {
            ps.execute();
        } catch (java.sql.SQLException alreadyThere) {
            // a rerun against a kept container
        }
        PGSimpleDataSource fresh = new PGSimpleDataSource();
        fresh.setUrl(root.replaceAll("/[^/?]+(\\?.*)?$", "/" + name));
        fresh.setUser(postgres.getUsername());
        fresh.setPassword(postgres.getPassword());
        copySchemaShape(fresh);
        return fresh;
    }

    /**
     * The empty tables, shaped as the source shapes them.
     *
     * <p>Built column by column rather than with LIKE, which cannot reach
     * across databases, and from the catalogue's own rendering of each type
     * rather than the standard view's — which reports anything Postgres
     * added, xid8 among them, as USER-DEFINED.
     */
    private void copySchemaShape(PGSimpleDataSource into) throws Exception {
        try (Connection from = rootSource().getConnection();
             Connection to = into.getConnection()) {
            try (PreparedStatement schema = to.prepareStatement(
                    "CREATE SCHEMA IF NOT EXISTS " + Domains.schema(Domains.DEFINITIONS))) {
                schema.execute();
            }
            for (String table : tablesOf(from)) {
                try (PreparedStatement ps = to.prepareStatement("CREATE TABLE IF NOT EXISTS "
                        + Domains.schema(Domains.DEFINITIONS) + "." + table
                        + " (" + columnsOf(from, table) + ")")) {
                    ps.execute();
                }
            }
        }
    }

    private static java.util.List<String> tablesOf(Connection c) throws Exception {
        java.util.List<String> tables = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private static String columnsOf(Connection from, String table) throws Exception {
        StringBuilder columns = new StringBuilder();
        try (PreparedStatement ps = from.prepareStatement("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!columns.isEmpty()) {
                        columns.append(", ");
                    }
                    columns.append(rs.getString(1)).append(' ').append(rs.getString(2));
                }
            }
        }
        return columns.toString();
    }

    private static byte[] withoutTheManifest(byte[] whole) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in =
                     new java.util.zip.ZipInputStream(new ByteArrayInputStream(whole));
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (FaceImage.MANIFEST_ENTRY.equals(entry.getName())) {
                    continue;
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                zip.write(in.readAllBytes());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static PGSimpleDataSource rootSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + ROOT.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
