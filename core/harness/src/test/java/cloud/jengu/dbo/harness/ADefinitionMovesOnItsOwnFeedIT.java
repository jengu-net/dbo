package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.fhir.common.FaceDefinitions;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a face gave a tenant moves apart from what happened at the tenant.
 *
 * <p>A tenant's profiles, parameters and vocabularies used to be rows among
 * its patients, on the same feed. Two things were wrong with that. A face is
 * cut once per release and handed to every tenant that comes up on it, so the
 * definitions have to be separable, and "the definitions" was not a thing that
 * could be named — it was a filter by type inside shared tables. And a
 * subscriber to a face read the root's clinical traffic on the way to the next
 * profile, because a root's feed was definitions only by accident of what the
 * root happened to hold.
 *
 * <p>So they are a domain: their own schema, their own feed, their own cursor.
 * The cursor is the part the rest of this work stands on — an image can only
 * be cut at a definitions cursor if records are not moving past it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADefinitionMovesOnItsOwnFeedIT {

    private static final String ROOT = "eristus-juur";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-definitions-feed");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADefinitionMovesOnItsOwnFeedIT"),
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
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"identifier","systems":["urn:test:mrn"],
                   "handling":"operational"}]}"""
                .formatted(ROOT));
        UntilServed.scan(manager, ROOT);
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
    @DisplayName("a profile lands in the definitions schema and a patient does not")
    @Proving(DboPromises.VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN)
    void aProfileLandsInTheDefinitionsSchema() throws Exception {
        writeAProfileAndAPatient();

        // A root holds the whole of what its version publishes, and the one
        // profile written above. All of it is in the one schema.
        assertTrue(count(Domains.tables(Domains.DEFINITIONS) + "_data",
                        "type = 'StructureDefinition' AND NOT deleted") > 100,
                "the version's own profiles are not in the schema a face is cut from");
        assertEquals(1, count(Domains.tables(Domains.DEFINITIONS) + "_data",
                        "type = 'StructureDefinition' AND NOT deleted"
                        + " AND envelope::text LIKE '%urn:test:profile:one%'"),
                "the profile this tenant authored is not there with them");
        assertEquals(0, count("state.r4_data", "type = 'StructureDefinition'"),
                "a profile is still among the tenant's records, so a dump of the definitions "
                        + "schema would be short of what the face gave");
        assertEquals(1, count("state.r4_data", "type = 'Patient' AND NOT deleted"),
                "the patient did not stay among the records");
        assertEquals(0, count(Domains.tables(Domains.DEFINITIONS) + "_data", "type = 'Patient'"),
                "a patient is in the schema cut into an image and handed to every other "
                        + "tenant on this face");
    }

    @Test
    @Timeout(600)
    @DisplayName("each feed carries its own, and neither carries the other's")
    @Proving(DboPromises.FEED_DEFINITIONS_MOVE_ON_A_FEED_OF_THEIR_OWN)
    void eachFeedCarriesItsOwn() throws Exception {
        writeAProfileAndAPatient();
        TenantRuntimeManager.TenantRuntime runtime = manager.runtime(ROOT).orElseThrow();

        List<String> onDefinitions = typesOn(drain(runtime.definitionsFeed(), "test.definitions"));
        List<String> onRecords = typesOn(drain(runtime.feed(), "test.records"));

        assertTrue(onDefinitions.contains("StructureDefinition"),
                "the definitions feed did not carry the profile: " + onDefinitions);
        assertFalse(onDefinitions.contains("Patient"),
                "a subscriber taking a face reads the root's patients on the way to the next "
                        + "profile: " + onDefinitions);
        assertTrue(onRecords.contains("Patient"),
                "the record feed did not carry the patient: " + onRecords);
        assertFalse(onRecords.contains("StructureDefinition"),
                "a reader of what happened at this tenant is handed its face as well: "
                        + onRecords);
    }

    @Test
    @DisplayName("a type registered into the wrong domain is refused, naming it and why")
    @Proving(DboPromises.TEN_A_TYPE_DECLARES_ITS_DOMAIN)
    void aMisplacedTypeIsRefused() {
        IllegalArgumentException definitionAmongRecords = assertThrows(
                IllegalArgumentException.class,
                () -> FaceDefinitions.refuseIfMisplaced(
                        List.of(registration("StructureDefinition", "r4")), "r4"));
        assertTrue(definitionAmongRecords.getMessage().contains("StructureDefinition"),
                definitionAmongRecords.getMessage());
        assertTrue(definitionAmongRecords.getMessage().contains("image"),
                "the refusal says what would go wrong rather than only that something did: "
                        + definitionAmongRecords.getMessage());

        IllegalArgumentException recordAmongDefinitions = assertThrows(
                IllegalArgumentException.class,
                () -> FaceDefinitions.refuseIfMisplaced(
                        List.of(registration("Patient", Domains.DEFINITIONS)), "r4"));
        assertTrue(recordAmongDefinitions.getMessage().contains("Patient"),
                recordAmongDefinitions.getMessage());

        // And the arrangement a tenant actually has is accepted.
        FaceDefinitions.refuseIfMisplaced(
                List.of(registration("StructureDefinition", Domains.DEFINITIONS),
                        registration("Patient", "r4")), "r4");
    }

    // ------------------------------------------------------------ the tenant

    private boolean written;

    private void writeAProfileAndAPatient() {
        if (written) {
            return; // one write, whichever test runs first
        }
        var store = manager.runtime(ROOT).orElseThrow().store();
        store.create("""
                {"resourceType":"StructureDefinition","url":"urn:test:profile:one",
                 "name":"OnlyAName","status":"draft","kind":"resource","abstract":false,
                 "type":"Patient","baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[{"id":"Patient","path":"Patient"}]}}""");
        store.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:test:mrn","value":"1"}]}""");
        written = true;
    }

    private static TypeRegistration registration(String typeName, String domain) {
        return new TypeRegistration(typeName, domain, IdentityClass.INTERNAL,
                java.util.Set.of(), Handling.operational(),
                (type, payload) -> new cloud.jengu.dbo.core.api.Envelope(), List.of());
    }

    // -------------------------------------------------------------- reading

    private static List<FeedItem> drain(cloud.jengu.dbo.core.api.feed.ChangeFeed feed,
            String consumer) {
        List<FeedItem> all = new ArrayList<>();
        FeedChunk<FeedItem> chunk;
        while (!(chunk = feed.readFor(consumer, 200)).items().isEmpty()) {
            all.addAll(chunk.items());
            feed.ack(consumer, chunk.nextCursor());
        }
        return all;
    }

    private static List<String> typesOn(List<FeedItem> items) {
        return items.stream().map(FeedItem::typeName).distinct().toList();
    }

    private static long count(String qualifiedTable, String where) throws Exception {
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM " + qualifiedTable + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + ROOT.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
