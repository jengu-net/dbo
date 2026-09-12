package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.FaceWarmup;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same tenant, by two routes.
 *
 * <p>One subscriber reads the whole of its face through the chain, a record at
 * a time, and expands what it receives. The other loads the same face as bytes
 * somebody cut earlier. The entire argument for the second is that there is no
 * way to tell afterwards which one a tenant took — so that is what this
 * compares, table by table, rather than asserting that the fast one finished.
 *
 * <p>The part that is easy to get wrong is not the rows. It is the two things
 * the chain does BESIDE writing them: marking each row as having come from the
 * face, which is what a read reports as its source and what a later change is
 * matched against, and leaving the stream at a known position. An image that
 * gets the rows right and those wrong produces a tenant that serves correctly
 * today and either re-reads its whole face or misses the next change.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATenantComesUpFromTheFaceImageIT {

    private static final String ROOT = "pilt-juur";
    private static final String READ_THE_CHAIN = "pilt-ahelast";
    private static final String FROM_THE_IMAGE = "pilt-kujutisest";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static Path images;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static long chainMillis;
    static long imageMillis;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-image-bringup");
        images = Files.createTempDirectory("dbo-image-kept");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantComesUpFromTheFaceImageIT"),
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

        // One reads the chain, because no image is kept yet.
        Files.writeString(dir.resolve(READ_THE_CHAIN + ".json"), subscriber(READ_THE_CHAIN));
        long began = System.currentTimeMillis();
        UntilServed.scan(manager, READ_THE_CHAIN);
        chainMillis = System.currentTimeMillis() - began;

        // The face is cut, and the next one is brought up from it.
        assertInstanceOf(FaceWarmup.Outcome.Cut.class, FaceWarmup.cut(manager, ROOT, images));
        manager.faceImagesIn(images);
        Files.writeString(dir.resolve(FROM_THE_IMAGE + ".json"), subscriber(FROM_THE_IMAGE));
        began = System.currentTimeMillis();
        UntilServed.scan(manager, FROM_THE_IMAGE);
        imageMillis = System.currentTimeMillis() - began;
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
    @Timeout(900)
    @DisplayName("the two tenants hold the same face, table for table")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void theTwoTenantsHoldTheSameFace() throws Exception {
        Map<String, Long> read = faceOf(READ_THE_CHAIN);
        Map<String, Long> loaded = faceOf(FROM_THE_IMAGE);

        assertTrue(read.getOrDefault(Domains.DEFINITIONS + "_data", 0L) > 1000,
                "the tenant that read the chain does not hold a face: " + read);
        assertEquals(read, loaded,
                "a tenant brought up from the image holds something other than what the "
                        + "tenant that read the chain holds");

        System.out.printf("METRICS faceBringUp chainMs=%d imageMs=%d%n",
                chainMillis, imageMillis);
    }

    @Test
    @Timeout(900)
    @DisplayName("its rows say they came from the face, as the chain would have said")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void itsRowsSayWhereTheyCameFrom() throws Exception {
        long marked = count(FROM_THE_IMAGE,
                "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                        + "_sync_origin WHERE dependency = '" + ROOT + "'");
        long held = count(FROM_THE_IMAGE,
                "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS) + "_data");

        // Not every definition a tenant holds came from the face: the runtime
        // writes the face's own vocabulary into each tenant itself, after the
        // chain has run, and those are the tenant's own. What must match is
        // how much came from the FACE, and that is the same by either route.
        assertTrue(marked > 1000 && marked <= held,
                "the tenant brought up from the image marked " + marked + " of " + held
                        + " definitions as having come from its face");
        assertEquals(count(READ_THE_CHAIN,
                        "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                                + "_sync_origin WHERE dependency = '" + ROOT + "'"),
                marked,
                "the two tenants disagree about how much of their face came from the face, "
                        + "so a read of one reports a different source than a read of the "
                        + "other and the next change from upstream matches differently");
    }

    @Test
    @Timeout(900)
    @DisplayName("a definition published after the cut still arrives, once")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void aDefinitionPublishedAfterTheCutStillArrives() throws Exception {
        manager.runtime(ROOT).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition","url":"urn:test:after-the-cut",
                 "name":"AfterTheCut","status":"draft","kind":"resource","abstract":false,
                 "type":"Patient","baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[{"id":"Patient","path":"Patient"}]}}""");

        // Whatever a round does for the tenant that read the chain, it does
        // for the one that did not: the stream was left where the image was
        // cut, so the only thing outstanding is what came after it.
        manager.streamsOf(FROM_THE_IMAGE).forEach(s -> {
            while (s.syncOnce(500) > 0) {
                // drain
            }
        });

        assertEquals(1, count(FROM_THE_IMAGE,
                        "SELECT count(*) FROM " + Domains.tables(Domains.DEFINITIONS)
                                + "_data WHERE envelope::text LIKE '%urn:test:after-the-cut%'"),
                "a definition published after the image was cut either never reached the "
                        + "tenant brought up from it, or reached it more than once");
    }

    @Test
    @Timeout(900)
    @DisplayName("the first tenant to want a face cuts it, and the next one finds it there")
    @Proving(DboPromises.VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE)
    void theFirstTenantToWantAFaceCutsIt() throws Exception {
        // Not every root that comes up: a deployment serving one face has no
        // use for images of the others, and an edge node may want none at
        // all. The cost is paid where the benefit is.
        Path kept = Files.createTempDirectory("dbo-image-on-demand");
        manager.faceImagesIn(kept);
        try (var before = Files.list(kept)) {
            assertEquals(java.util.List.of(), before.toList(),
                    "an image was cut before anybody asked for one");
        }

        Files.writeString(dir.resolve("kysija.json"), subscriber("kysija"));
        UntilServed.scan(manager, "kysija");

        try (var after = Files.list(kept)) {
            assertEquals(java.util.List.of("r4.faceimage"),
                    after.map(f -> f.getFileName().toString()).sorted().toList(),
                    "the first tenant to want this face did not leave one behind, so the "
                            + "next one reads the whole face through the chain as well");
        }

        // And the one after it is brought up from what the first one cut.
        Files.writeString(dir.resolve("jargmine.json"), subscriber("jargmine"));
        long began = System.currentTimeMillis();
        UntilServed.scan(manager, "jargmine");
        long secondMillis = System.currentTimeMillis() - began;

        assertEquals(count("kysija", "SELECT count(*) FROM "
                        + Domains.tables(Domains.DEFINITIONS) + "_data"),
                count("jargmine", "SELECT count(*) FROM "
                        + Domains.tables(Domains.DEFINITIONS) + "_data"),
                "the tenant that cut the face and the one brought up from it hold "
                        + "different amounts of it");
        System.out.printf("METRICS faceOnDemand secondTenantMs=%d%n", secondMillis);
        assertTrue(secondMillis < chainMillis,
                "the tenant after the one that cut the face took " + secondMillis
                        + "ms, no better than the " + chainMillis + "ms of reading the chain");
    }

    // ------------------------------------------------------------- the face

    private static String subscriber(String code) {
        return """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                                  "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code, ROOT);
    }

    /** Every table of the tenant's face and how much is in it. */
    private static Map<String, Long> faceOf(String tenant) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = sourceFor(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = ? AND table_type = 'BASE TABLE'
                     ORDER BY table_name""")) {
            ps.setString(1, Domains.schema(Domains.DEFINITIONS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    // What is about this tenant's own relationship to a feed
                    // is not the face, and the two tenants reached their
                    // positions by different routes on purpose.
                    if (!table.contains("_outbox") && !table.contains("_consumer")
                            && !table.contains("_sync_")) {
                        counts.put(table, null);
                    }
                }
            }
        }
        for (Map.Entry<String, Long> table : counts.entrySet()) {
            table.setValue(count(tenant, "SELECT count(*) FROM "
                    + Domains.schema(Domains.DEFINITIONS) + "." + table.getKey()));
        }
        return counts;
    }

    private static long count(String tenant, String sql) throws Exception {
        try (Connection c = sourceFor(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static PGSimpleDataSource sourceFor(String tenant) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + tenant.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
