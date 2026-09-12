package cloud.jengu.dbo.harness;

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
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zone written in one version, reaching tenants on another.
 *
 * <p>A zone publishes in the version it was written in and its tenants are on
 * whichever face each of them chose. Every one of those tenants could convert
 * the zone for itself — the stream has always been able to — and then a zone
 * with twenty tenants on a face converts the same documents twenty times into
 * twenty copies that had better agree.
 *
 * <p>So it is converted once, by a tenant that exists to do it, and the zone's
 * tenants on that face read the converted result. Nobody declares that tenant:
 * it follows from a zone's version and the faces of the tenants that asked for
 * it, which is arithmetic rather than configuration — made to be declared,
 * every deployment would owe one per zone per face, and forgetting one is a
 * tenant quietly converting for itself again.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class AZoneReachesAnotherFaceThroughOneProjectionIT {

    private static final String R4_ROOT = "r4-juur";
    private static final String ZONE = "vald";
    private static final String ON_R4 = "vald-on-r4";
    private static final String SAME_FACE = "sama-nagu";
    private static final String OTHER_FACE = "teine-nagu";
    private static final String EID = "https://zone.test/id";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-zone-projection");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AZoneReachesAnotherFaceThroughOneProjectionIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.faceImagesIn(Files.createTempDirectory("dbo-zone-images"));

        // The face the zone will be converted INTO has to be somewhere, or the
        // projection has nothing to judge a converted definition against.
        Files.writeString(dir.resolve(R4_ROOT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(R4_ROOT));

        // The zone publishes in R5.
        Files.writeString(dir.resolve(ZONE + ".json"), """
                {"code":"%s","face":"r5","audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"identifier","systems":["%s"],
                   "handling":"operational"}]}"""
                .formatted(ZONE, EID));
        // One tenant is on R5 like the zone, and one is on R4 and is not.
        Files.writeString(dir.resolve(SAME_FACE + ".json"), tenant(SAME_FACE, "r5"));
        Files.writeString(dir.resolve(OTHER_FACE + ".json"), tenant(OTHER_FACE, "r4"));

        UntilServed.scan(manager, R4_ROOT, ZONE);
        UntilServed.scan(manager, SAME_FACE, OTHER_FACE);
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

    @org.junit.jupiter.api.Order(1)
    @Test
    @Timeout(900)
    @DisplayName("a zone on another face is converted once, by a tenant nobody declared")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void aZoneOnAnotherFaceIsConvertedOnce() {
        assertTrue(manager.codes().contains(ON_R4),
                "nothing stands between the R5 zone and its R4 tenant, so that tenant "
                        + "converts the zone for itself: " + manager.codes());
        assertEquals("r4", manager.runtime(ON_R4).orElseThrow().spec().face(),
                "the projection does not stand on the face it exists to serve");

        // And only where the versions differ: the tenant on the zone's own
        // face reads the zone itself, because there is nothing to convert.
        assertFalse(manager.codes().contains(ZONE + "-on-r5"),
                "a projection was made for a face the zone was already written in, which "
                        + "is a hop, a database and a second copy for no conversion");
    }

    @org.junit.jupiter.api.Order(2)
    @Test
    @Timeout(900)
    @DisplayName("the tenant reads the projection while still declaring the zone")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void theTenantReadsTheProjectionWhileDeclaringTheZone() throws Exception {
        // What it asked for is unchanged: a tenant names the zone, and which
        // projection serves it follows from its own face.
        assertEquals(List.of(ZONE), manager.runtime(OTHER_FACE).orElseThrow().spec()
                        .dependencies().stream().map(d -> d.name()).toList(),
                "the tenant's declaration names something other than the zone it asked for");

        // The stream is still NAMED for the zone, and that is not an oversight:
        // provenance says where a record came from as far as the tenant is
        // concerned, and the tenant asked for the zone.
        assertTrue(manager.streamsOf(OTHER_FACE).stream()
                        .anyMatch(stream -> stream.name().equals("sync." + ZONE + "."
                                + OTHER_FACE)),
                "the stream is named for something other than the zone the tenant declared: "
                        + manager.streamsOf(OTHER_FACE).stream().map(s -> s.name()).toList());

        // Something has to have travelled before there is anything to see: a
        // reader leaves its position where it read, and a reader that has read
        // nothing has left nothing.
        publishToTheZone();

        // What it READS is the projection, and a reader of a feed leaves its
        // position in the database it read from — so the R4 tenant's consumer
        // is the projection's to keep, and the zone has never heard of it.
        assertTrue(readsFrom(ON_R4, "r4", "sync." + ZONE + "." + OTHER_FACE),
                "the R4 tenant does not read the projection, so it is converting the "
                        + "zone for itself");
        assertFalse(readsFrom(ZONE, "r5", "sync." + ZONE + "." + OTHER_FACE),
                "the R4 tenant reads the R5 zone directly, across versions");

        // And the projection reads the zone, which is the one hop that converts.
        assertTrue(readsFrom(ZONE, "r5", "sync." + ZONE + "." + ON_R4),
                "the projection does not read the zone it exists to convert");

        // The tenant on the zone's own face reads the zone itself.
        assertTrue(readsFrom(ZONE, "r5", "sync." + ZONE + "." + SAME_FACE),
                "a tenant on the zone's own face was routed through a projection anyway");
    }

    @org.junit.jupiter.api.Order(3)
    @Test
    @Timeout(900)
    @DisplayName("what the zone publishes arrives on the other face, converted once")
    @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
    void whatTheZonePublishesArrivesConverted() throws Exception {
        publishToTheZone();

        // It reached the tenant on the zone's own face, unconverted, and the
        // tenant on the other face, converted — and the conversion happened at
        // the projection, which is the only place that crosses versions.
        long published = PUBLISHED.get();
        assertEquals(published, holds(SAME_FACE, "r5"),
                "the zone's own face did not receive what it published");
        assertEquals(published, holds(ON_R4, "r4"),
                "the projection did not take the zone across the version");
        assertEquals(published, holds(OTHER_FACE, "r4"),
                "the tenant on the other face never received the zone's record");
    }

    @org.junit.jupiter.api.Order(4)
    @Test
    @Timeout(900)
    @DisplayName("a definition the older face cannot stand up is named, not passed on")
    @Proving(DboPromises.ZONE_WHAT_CONVERSION_CANNOT_CARRY_IS_REFUSED_BY_NAME)
    void aDefinitionTheOlderFaceCannotStandUpIsNamed() throws Exception {
        // Nothing here is unservable yet, and saying so is half the point: a
        // check that always finds something is a check nobody reads.
        assertEquals(List.of(), manager.whatTheVersionCouldNotCarry(ON_R4),
                "a zone that converts cleanly was reported as partly unservable");

        // A profile built on a resource the older version never had. It
        // converts — the document is well-formed either way — and what comes
        // out stands on nothing, which is the failure worth catching: it
        // loads, and nothing can be validated against it.
        manager.runtime(ZONE).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition","url":"https://zone.test/only-in-r5",
                 "name":"OnlyInR5","status":"draft","kind":"resource","abstract":false,
                 "type":"ActorDefinition",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/ActorDefinition",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"ActorDefinition","path":"ActorDefinition"}]}}""");
        for (int round = 0; round < 10 && manager.syncRound() > 0; round++) {
            // carried as far as it goes
        }
        manager.syncRound();

        List<cloud.jengu.dbo.tenant.ConvertedDefinitions.Unfounded> lost =
                manager.whatTheVersionCouldNotCarry(ON_R4);
        assertTrue(lost.stream().anyMatch(one -> "https://zone.test/only-in-r5".equals(one.url())),
                "a profile standing on a resource this face never had was carried across as "
                        + "though it still stood on something: " + lost);
        assertTrue(lost.stream().anyMatch(one ->
                        one.base().contains("ActorDefinition")),
                "the report does not say what it lost, which is the part somebody can act "
                        + "on: " + lost);
    }

    @org.junit.jupiter.api.Order(5)
    @Test
    @Timeout(900)
    @DisplayName("a tenant is not served a zone that did not survive the trip to its face")
    @Proving(DboPromises.ZONE_AN_UNSERVABLE_ZONE_IS_SAID_AT_BRING_UP)
    void aTenantIsNotServedAZoneThatDidNotSurvive() throws Exception {
        // The zone lost a definition on the way to R4 in the test before this
        // one, which is why these are ordered: what is being asked here is
        // what a tenant arriving AFTER that is told.

        Files.writeString(dir.resolve("hiljem-tulija.json"), tenant("hiljem-tulija", "r4"));
        for (int pass = 0; pass < 5; pass++) {
            manager.scanOnce();
        }

        assertFalse(manager.codes().contains("hiljem-tulija"),
                "a tenant came up serving most of a zone, which looks exactly like serving "
                        + "the zone and is not");
        String why = manager.troubles().get("hiljem-tulija");
        assertTrue(why != null && why.contains(ZONE),
                "the refusal does not name the zone that could not be served: " + why);
        assertTrue(why.contains("only-in-r5"),
                "the refusal does not name the definition that was lost, which is the part "
                        + "somebody can act on: " + why);

        // And a tenant on the zone's own face is unaffected: nothing was
        // converted, so nothing can have been lost.
        Files.writeString(dir.resolve("hiljem-r5.json"), tenant("hiljem-r5", "r5"));
        UntilServed.scan(manager, "hiljem-r5");
        assertTrue(manager.codes().contains("hiljem-r5"),
                "a tenant on the zone's own face was refused for a loss that only happens "
                        + "on the way to another one");
    }

    @org.junit.jupiter.api.Order(6)
    @Test
    @Timeout(900)
    @DisplayName("a canonical the face already gave the projection does not stop the zone's "
            + "stream")
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aCanonicalTheFaceGaveUsDoesNotStopTheZone() {
        // A tenant can be given the same canonical by two upstreams: the
        // engine's own vocabulary reaches it with its face, and again with any
        // zone that publishes structures. The second arrival is a conflict
        // over identity, and what the stream does with it decides whether
        // everything behind it is ever delivered.
        //
        // It used to rethrow whenever the object already here had come from a
        // stream — any stream — so the zone's copy stopped this one at the
        // head of its queue forever. Nothing said so: no dead letter, no
        // parked shadow, just a zone whose profiles never arrived.
        var zoneStream = manager.streamsOf(ON_R4).stream()
                .filter(s -> s.name().equals("sync." + ZONE + "." + ON_R4 + ".definitions"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("the projection has no definitions stream for "
                                + "its zone: " + manager.streamsOf(ON_R4).stream()
                                .map(s -> s.name()).toList()));

        assertFalse(zoneStream.origins().isEmpty(),
                "the zone's definitions stream has applied nothing at all, which is what "
                        + "being stuck at the head of its queue looks like from outside");
        assertFalse(zoneStream.degraded(),
                "the zone's definitions stream is degraded: " + zoneStream.deadLetters());
    }

    /**
     * One observation into the zone, carried until everything is quiet.
     *
     * <p>Driven rather than waited for, so the test says when the streams have
     * run instead of guessing how long they take.
     */
    private static final java.util.concurrent.atomic.AtomicInteger PUBLISHED =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void publishToTheZone() {
        // Its own identifier each time: two tests publishing the same one is
        // the zone refusing the second as somebody else's claim, which is the
        // engine being right about identity and has nothing to do with zones.
        manager.runtime(ZONE).orElseThrow().store().create("""
                {"resourceType":"Observation","status":"final",
                 "identifier":[{"system":"%s","value":"%d"}],
                 "code":{"text":"carried across a version"}}"""
                .formatted(EID, PUBLISHED.incrementAndGet()));
        for (int round = 0; round < 10 && manager.syncRound() > 0; round++) {
            // each round carries what the last one made visible downstream
        }
        manager.syncRound();
    }

    /** How many observations this tenant holds. */
    private static long holds(String tenant, String face) throws Exception {
        try (java.sql.Connection c = databaseOf(tenant).getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state." + face
                             + "_data WHERE type = 'Observation' AND NOT deleted");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static org.postgresql.ds.PGSimpleDataSource databaseOf(String tenant) {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + tenant.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    /** Whether that consumer has a position in this tenant's own feed. */
    private static boolean readsFrom(String tenant, String face, String consumer)
            throws Exception {
        try (java.sql.Connection c = databaseOf(tenant).getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state." + face + "_consumer WHERE name = ?")) {
            ps.setString(1, consumer);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static String tenant(String code, String face) {
        return """
                {"code":"%s","face":"%s","audit":{"level":"none"},
                 "dependencies":[{"name":"%s",
                                  "types":["Observation","StructureDefinition"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"Observation","identity":"identifier","systems":["%s"],
                   "handling":"replicated"}]}"""
                .formatted(code, face, ZONE, EID);
    }
}
