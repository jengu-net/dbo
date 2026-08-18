package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restored consumer stands at the head of the feed.
 *
 * <p>The companion to {@code ImportDoesNotRedeliverIT}, which covers the other
 * half of the same promise: that half proves a restore does not <em>announce</em>
 * what it writes, this one proves it does not reinstate a cursor standing
 * behind the feed. Either alone leaves a path by which a hospital receives its
 * own past as fresh news.
 *
 * <p>This is the sharpest failure in the backup design because it does not
 * look like one. Delivery lags the feed by design, so a backup almost always
 * captures a cursor standing behind the outbox head; reinstating it re-sends
 * every event in that gap, and the restore reports success while a hospital's
 * downstream systems receive the same notifications a second time.
 *
 * <p>The test therefore reproduces the <b>normal</b> state of a live feed — a
 * consumer that has acknowledged some events and not others — rather than a
 * contrived one. A backup taken with a fully caught-up consumer would pass
 * against the broken code.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoredConsumerStartsAtHeadIT {

    private static final byte[] OWNER_KEY = new byte[32];
    private static final String CONSUMER = "downstream";

    static PGSimpleDataSource source;
    static PGSimpleDataSource target;
    static byte[] archive;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        source = database("cursor_head_source");
        target = database("cursor_head_target");

        R4Personality personality =
                new R4Personality(List.of(FhirTypeConfig.internal("Observation")));
        PgObjectStore store = new PgObjectStore(source, personality.registrations());
        PgChangeFeed feed = new PgChangeFeed(source, R4Personality.DOMAIN);

        // five events happen, and the consumer keeps up with the first three
        for (int i = 0; i < 3; i++) {
            store.put(PutRequest.create("Observation", observation(i)));
        }
        var caughtUp = feed.readFor(CONSUMER, 100);
        assertEquals(3, caughtUp.items().size(), "three events to acknowledge");
        feed.ack(CONSUMER, caughtUp.nextCursor());

        // two more arrive and are delivered, but the backup is taken before
        // the acknowledgement lands — which is simply what a busy feed looks
        // like at any given moment
        for (int i = 3; i < 5; i++) {
            store.put(PutRequest.create("Observation", observation(i)));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(source, R4Personality.DOMAIN, OWNER_KEY, out);
        archive = out.toByteArray();
    }

    @Test
    @Timeout(300)
    @DisplayName("a restored consumer stands at the head of the feed, not where the "
            + "backup caught it")
    void aRestoredConsumerIsAtTheHead() throws Exception {
        restoreInto(target);

        PgChangeFeed restored = new PgChangeFeed(target, R4Personality.DOMAIN);

        assertNotNull(restored.cursorOf(CONSUMER),
                "the consumer survives the restore — who reads the feed is not delivery state, "
                        + "and a consumer with no row would start at zero and replay everything");
        assertEquals(0, restored.lag(CONSUMER),
                "and stands at the head: the two events the backup caught undelivered are not "
                        + "re-sent, because a restore is a recovery and not a replay");
        assertTrue(restored.readFor(CONSUMER, 100).items().isEmpty(),
                "so reading as that consumer yields nothing — the assertion a downstream "
                        + "system would make on the morning after");
    }

    @Test
    @Timeout(300)
    @DisplayName("the objects themselves are all there — nothing was dropped along with "
            + "the delivery state")
    void theDataItselfSurvives() throws Exception {
        restoreInto(target);

        try (Connection c = target.getConnection();
             var ps = c.prepareStatement(
                     "SELECT count(*) FROM state.%s_data WHERE NOT deleted"
                             .formatted(R4Personality.DOMAIN));
             var rs = ps.executeQuery()) {
            rs.next();
            assertEquals(5, rs.getLong(1),
                    "leaving the cursor behind must not leave the data behind — a restore that "
                            + "delivered nothing because it restored nothing would pass the "
                            + "test above");
        }
    }

    /** A byte-faithful restore, which is what a disaster recovery performs. */
    private static void restoreInto(PGSimpleDataSource into) throws Exception {
        // the schema has to exist first: restoreFidelity loads into an
        // initialised, empty tenant rather than creating one
        new PgObjectStore(into, new R4Personality(
                List.of(FhirTypeConfig.internal("Observation"))).registrations());
        TenantImport.restoreFidelity(into, R4Personality.DOMAIN,
                new ByteArrayInputStream(archive), OWNER_KEY);
    }

    private static byte[] observation(int n) {
        return ("{\"resourceType\":\"Observation\",\"status\":\"final\",\"id\":null,"
                + "\"note\":[{\"text\":\"event " + n + "\"}]}")
                .replace("\"id\":null,", "")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("RestoredConsumerStartsAtHeadIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            // driven to the expected state rather than assumed to be in it:
            // the shared Postgres outlives a single run, so a leftover
            // database from an earlier one must not decide this test
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
