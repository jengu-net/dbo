package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.testmodel.GadgetModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#18 R1: the per-database barrier fast path — a long write-transaction
 * in a FOREIGN database of the same instance (the dbo#16 pinner) no longer
 * delays a quiet tenant's feed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BarrierLivenessIT {

    static PostgreSQLContainer<?> postgres;
    static PGSimpleDataSource tenantDs;
    static Connection pinner;

    @BeforeAll
    void up() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE barrier_tenant");
            st.execute("CREATE DATABASE barrier_foreign");
        }
        String baseUrl = postgres.getJdbcUrl().substring(0, postgres.getJdbcUrl().lastIndexOf('/') + 1);

        tenantDs = new PGSimpleDataSource();
        tenantDs.setUrl(baseUrl + "barrier_tenant");
        tenantDs.setUser(postgres.getUsername());
        tenantDs.setPassword(postgres.getPassword());

        // the foreign pinner: an OPEN write transaction (assigned XID) in
        // another database of the same instance
        pinner = DriverManager.getConnection(baseUrl + "barrier_foreign",
                postgres.getUsername(), postgres.getPassword());
        pinner.setAutoCommit(false);
        try (var st = pinner.createStatement()) {
            st.execute("CREATE TABLE pin (n int)");
            st.execute("INSERT INTO pin VALUES (1)"); // XID assigned; tx stays open
        }
    }

    @AfterAll
    void down() throws Exception {
        if (pinner != null) {
            pinner.rollback();
            pinner.close();
        }
        postgres.stop();
    }

    @Test
    @Timeout(60)
    void aForeignDatabasePinnerDoesNotDelayAQuietTenantsFeed() {
        PgObjectStore store = new PgObjectStore(tenantDs, GadgetModel.registrations());
        store.put(PutRequest.create("Gadget",
                "{\"serial\":\"BAR-1\",\"vendor\":\"barco\",\"name\":\"Pinned?\",\"weightGrams\":1}"
                        .getBytes(StandardCharsets.UTF_8)));

        // conservative-only barrier would hide this event as long as the
        // foreign transaction lives; the fast path must deliver it now
        List<FeedItem> items = new PgChangeFeed(tenantDs, GadgetModel.DOMAIN)
                .read(null, 10).items();
        assertEquals(1, items.size(), "quiet tenant's feed must deliver despite the foreign pinner");
        assertTrue(new String(items.get(0).payload(), StandardCharsets.UTF_8).contains("BAR-1"));
    }
}
