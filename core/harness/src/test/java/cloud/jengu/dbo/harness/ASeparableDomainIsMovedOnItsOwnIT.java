package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.testmodel.GadgetModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A domain that is moved on its own lives in a schema of its own.
 *
 * <p>Most domains share {@code state} and {@code history} and are told apart
 * by a prefix. A domain that is handed over separately cannot: a schema is the
 * unit Postgres dumps, restores, drops and grants on, and a domain spread
 * through {@code state} beside four others has to be named table by table by
 * whoever moves it. So a separable domain gets a schema, and everything that
 * composes a table name — the store, the feed, the backup — has to find it
 * there rather than where it used to be.
 *
 * <p>The failure this guards against is the quiet one. Nothing about a domain
 * moved to another schema fails: the store writes it, the feed reads it, and
 * a backup whose sweep still looks only in {@code state} takes everything
 * except it and says nothing. That archive restores an installation missing
 * exactly the part that was made portable, which is why the round trip below
 * is the test and the placement checks are only the diagnosis.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ASeparableDomainIsMovedOnItsOwnIT {

    /** The separable domain this store has: what a face gave the tenant. */
    private static final String SEPARABLE = Domains.DEFINITIONS;

    private static final byte[] OWNER_KEY = new byte[32];
    private static final List<TypeRegistration> TYPES = types();

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        ds = database("separable_domain");
        store = new PgObjectStore(ds, TYPES);
        store.put(PutRequest.create("Gadget", gadget("g-1")));
        store.put(PutRequest.create("Leaflet", leaflet("urn:leaflet:one")));
    }

    @Test
    @Timeout(300)
    @DisplayName("its rows are in its own schema, and the shared one has no table for it")
    @Proving(DboPromises.CORE_A_SEPARABLE_DOMAIN_HAS_A_SCHEMA_OF_ITS_OWN)
    void itsRowsAreInItsOwnSchema() throws Exception {
        assertEquals(1, rows(ds, Domains.tables(SEPARABLE) + "_data"),
                "the separable domain's record is not in the schema that domain names");
        assertEquals(1, rows(ds, Domains.historyTables(SEPARABLE) + "_history"),
                "its history went somewhere else than its records, so a dump of the schema "
                        + "would carry a tenant's definitions without their versions");

        // An ordinary domain is untouched by any of this.
        assertEquals(1, rows(ds, Domains.tables(GadgetModel.DOMAIN) + "_data"));

        // And nothing was left behind in the shared schemas under the same
        // name — a table in both places is how a reader ends up dumping the
        // empty one.
        assertNull(relation(ds, "state." + SEPARABLE + "_data"),
                "the shared schema still has a table for the separable domain");
        assertNull(relation(ds, "history." + SEPARABLE + "_history"),
                "the shared history schema still has a table for the separable domain");
        assertNotNull(relation(ds, Domains.tables(SEPARABLE) + "_outbox"),
                "its feed's outbox stayed behind while its records moved");
    }

    @Test
    @Timeout(300)
    @DisplayName("its feed reads across the move, payload and all")
    @Proving(DboPromises.CORE_A_SEPARABLE_DOMAIN_HAS_A_SCHEMA_OF_ITS_OWN)
    void itsFeedReadsAcrossTheMove() {
        // The feed joins the outbox to history for the payload. Both moved,
        // and a join that found only one of them would read as an empty feed
        // rather than as an error.
        FeedChunk chunk = new PgChangeFeed(ds, SEPARABLE).read(null, 10);
        List<FeedItem> items = new ArrayList<>(chunk.items());
        assertEquals(1, items.size(), "the separable domain's feed delivered nothing");
        assertEquals("Leaflet", items.get(0).typeName());
        assertTrue(new String(items.get(0).payload(), StandardCharsets.UTF_8)
                        .contains("urn:leaflet:one"),
                "the item arrived without its payload, so the join reached the wrong schema");
    }

    @Test
    @Timeout(300)
    @DisplayName("a backup carries it, rather than taking everything except it")
    @Proving(DboPromises.CORE_A_SEPARABLE_DOMAIN_HAS_A_SCHEMA_OF_ITS_OWN)
    void aBackupCarriesIt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, GadgetModel.DOMAIN, OWNER_KEY, out, TYPES,
                TenantExport.Kind.BACKUP);

        PGSimpleDataSource target = database("separable_domain_target");
        new PgObjectStore(target, TYPES);
        CoSignedArchive.over(out.toByteArray(), OWNER_KEY)
                .restoreFidelityInto(target, GadgetModel.DOMAIN, OWNER_KEY);

        assertEquals(1, rows(target, Domains.tables(GadgetModel.DOMAIN) + "_data"),
                "the domain the backup was asked for did not come back");
        assertEquals(1, rows(target, Domains.tables(SEPARABLE) + "_data"),
                "the backup was taken over a tenant holding a separable domain and restored "
                        + "one without it — the archive is short by exactly the part a schema "
                        + "of its own was supposed to make easy to carry");
        assertEquals(1, rows(target, Domains.historyTables(SEPARABLE) + "_history"),
                "its records came back without their versions");
    }

    // ------------------------------------------------------------- the model

    /**
     * One ordinary domain and one separable one in a single store, because
     * that is the arrangement a tenant has: its records and what its face
     * gave it, side by side in one database.
     */
    private static List<TypeRegistration> types() {
        List<TypeRegistration> all = new ArrayList<>(GadgetModel.registrations());
        EnvelopeExtractor extractor = (type, payload) -> {
            Envelope e = new Envelope();
            String body = new String(payload, StandardCharsets.UTF_8);
            String url = body.substring(body.indexOf("\"url\":\"") + 7, body.lastIndexOf('"'));
            e.identifier(Identifier.CANONICAL_SYSTEM, url);
            e.value("url", EnvelopeValue.of(url));
            return e;
        };
        all.add(new TypeRegistration("Leaflet", SEPARABLE, IdentityClass.CANONICAL,
                java.util.Set.of(), Handling.operational(), extractor, List.of()));
        return List.copyOf(all);
    }

    private static byte[] gadget(String serial) {
        return ("{\"serial\":\"%s\",\"vendor\":\"acme\",\"name\":\"widget\",\"weightGrams\":1}"
                .formatted(serial)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] leaflet(String url) {
        return "{\"url\":\"%s\"}".formatted(url).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------- the database

    private static PGSimpleDataSource database(String name) throws Exception {
        String root = SharedPostgres.urlFor("ASeparableDomainIsMovedOnItsOwnIT");
        try (Connection c = DriverManager.getConnection(root,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             PreparedStatement ps = c.prepareStatement("CREATE DATABASE " + name)) {
            ps.execute();
        } catch (java.sql.SQLException alreadyThere) {
            // a rerun against a kept container
        }
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(root.replaceAll("/[^/?]+(\\?.*)?$", "/" + name));
        pg.setUser(SharedPostgres.get().getUsername());
        pg.setPassword(SharedPostgres.get().getPassword());
        return pg;
    }

    private static long rows(PGSimpleDataSource on, String qualifiedTable) throws Exception {
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + qualifiedTable);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The table's oid, or null when nothing of that name exists. */
    private static String relation(PGSimpleDataSource on, String qualifiedTable) throws Exception {
        try (Connection c = on.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?)::text")) {
            ps.setString(1, qualifiedTable);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
