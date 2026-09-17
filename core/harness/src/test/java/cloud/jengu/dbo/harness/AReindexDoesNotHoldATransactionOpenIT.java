package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reindex that extracts inside its own transaction is killed by this store's
 * own guard.
 *
 * <p>Every tenant database is created with
 * {@code idle_in_transaction_session_timeout = '60s'}, and the reindex ran the
 * extractor — work in this JVM, over as many as 500 rows — between two
 * statements of an open transaction. On a loaded node that crossed sixty
 * seconds, Postgres terminated the connection, the rollback then failed
 * against a closed one, and the reindex died.
 *
 * <p>What made it expensive rather than merely noisy: the feed consumer that
 * triggers the rebuild is acknowledged <em>before</em> the rebuild runs, so
 * the events were never re-read. The index stayed stale behind a single
 * warning — and a stale envelope does not make a search slow, it makes it
 * <b>miss</b>, which reads as nothing here.
 *
 * <p>This holds the timeout at one second and gives the extractor enough work
 * to cross it. Before the split it fails; after it, the transaction spans only
 * the database's own work and the duration of the extraction stops mattering.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AReindexDoesNotHoldATransactionOpenIT {

    private static final String TYPE = "SlowlyExtracted";
    private static final String SYSTEM = "https://reindex.test/id";
    /**
     * Longer than the guard, per row.
     *
     * <p>Per ROW rather than per batch, because the timeout measures a
     * CONTINUOUS idle gap and not time accumulated across one — the old code
     * wrote after each row, so its longest gap was a single extraction. That
     * is also how the real failure happened: one extraction starved of CPU on
     * a loaded node, not a batch adding up.
     */
    private static final long EXTRACTION_MILLIS = 2_500;
    private static final int ROWS = 2;
    private static final int BATCH = 2;

    static PostgreSQLContainer<?> postgres;
    static PGSimpleDataSource ds;
    static PgObjectStore store;
    /**
     * Slow only once the rows are in.
     *
     * <p>A write extracts inside its own transaction too, so an extractor that
     * was slow from the start killed the writes before the reindex was
     * reached. That is a narrower exposure — one record, bounded work — and it
     * is not what this test is about.
     */
    static final java.util.concurrent.atomic.AtomicBoolean SLOW =
            new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        String url = SharedPostgres.urlFor("AReindexDoesNotHoldATransactionOpenIT");
        ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());

        // The same guard a provisioned tenant gets, wound down from sixty
        // seconds to one so the test does not have to take a minute to state
        // its case. It is set on the DATABASE, so every connection opened
        // after this carries it.
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT current_database()");
            try (var rs = s.executeQuery("SELECT current_database()")) {
                rs.next();
                try (Statement alter = c.createStatement()) {
                    alter.execute("ALTER DATABASE \"" + rs.getString(1)
                            + "\" SET idle_in_transaction_session_timeout = '1s'");
                }
            }
        }

        // The guard is worth asserting rather than assuming: a test that
        // silently ran without it would pass whatever the code did, which is
        // what the first version of this did.
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                var rs = s.executeQuery("SHOW idle_in_transaction_session_timeout")) {
            rs.next();
            assertEquals("1s", rs.getString(1),
                    "the guard this test depends on is not in force, so it proves nothing");
        }

        EnvelopeExtractor slow = (type, payload) -> {
            if (SLOW.get()) {
                try {
                    Thread.sleep(EXTRACTION_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Envelope envelope = new Envelope();
            String body = new String(payload, StandardCharsets.UTF_8);
            String value = body.substring(body.indexOf(':') + 1).replace("\"", "")
                    .replace("}", "").trim();
            envelope.identifier(SYSTEM, value);
            envelope.value("marker", EnvelopeValue.of(value));
            return envelope;
        };
        store = new PgObjectStore(ds, List.of(new TypeRegistration(TYPE, "state",
                IdentityClass.IDENTIFIER, Set.of(SYSTEM), Handling.operational(),
                slow, List.of())));
    }

    @AfterAll
    void down() {
    }

    @Test
    @Timeout(600)
    @Proving(DboPromises.SRCH_A_REINDEX_HOLDS_NO_TRANSACTION_WHILE_IT_EXTRACTS)
    @DisplayName("a reindex whose extraction outlasts the idle-in-transaction guard still "
            + "finishes, because it is not holding a transaction while it extracts")
    void extractionOutsideTheTransactionSurvivesTheGuard() {
        for (int i = 0; i < ROWS; i++) {
            store.put(PutRequest.create(TYPE,
                    ("{\"marker\":\"row-" + i + "\"}").getBytes(StandardCharsets.UTF_8)));
        }

        SLOW.set(true);
        // One extraction is longer than the whole guard, so if it happens
        // with the transaction open the connection is gone before the second
        // row is reached.
        int rebuilt = store.rebuildEnvelopes(TYPE, BATCH);

        assertEquals(ROWS, rebuilt,
                "the reindex did not finish: with extraction inside the transaction the "
                        + "connection is terminated as idle-in-transaction, and the rollback "
                        + "then fails against a closed connection");
    }
}
