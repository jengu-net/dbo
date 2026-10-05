package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.DatabaseExtractor;
import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reindex done in the database writes back only over the version it read.
 *
 * <p>Where a type's extractor is a database function, the whole reindex is
 * one transaction of two halves: a walk that extracts every row into a
 * temporary table, and the writes that put what it extracted back. A write to
 * the record that commits between the two already carries an envelope from
 * its own payload — and the reindex wrote over it with one from the payload
 * the walk had read, so a search matched the record by values it no longer
 * holds and missed it by the ones it does. The Java reindex was guarded on
 * the version it read; this one was not.
 *
 * <p>Held open without timing anything: the test's own function waits, in the
 * middle of the walk, on a lock the test holds, and the update lands while it
 * waits. The gate it waits behind is open only for the reindex, because the
 * write extracts through the same function.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AReindexWhereTheBytesAreLeavesAMovedRowAloneIT {

    private static final String TYPE = "ExtractedInTheDatabase";
    private static final String SYSTEM = "https://reindex-race.test/id";
    private static final long HELD = 4_242;

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AReindexWhereTheBytesAreLeavesAMovedRowAloneIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS race");
            s.execute("CREATE TABLE IF NOT EXISTS race.gate (open boolean)");
            s.execute("DELETE FROM race.gate");
            // The marker is the envelope and the identity both, so a stale
            // reindex shows in each.
            s.execute("""
                    CREATE OR REPLACE FUNCTION race.parts(doc jsonb, type_name text,
                            canonical boolean) RETURNS jsonb LANGUAGE plpgsql VOLATILE AS $$
                    BEGIN
                      IF EXISTS (SELECT 1 FROM race.gate) THEN
                        PERFORM pg_advisory_xact_lock_shared(%d);
                      END IF;
                      RETURN jsonb_build_object(
                        'envelope', jsonb_build_object('marker', jsonb_build_array(
                            jsonb_build_object('t', 'str', 'v', doc ->> 'marker'))),
                        'identifiers', jsonb_build_array(jsonb_build_object(
                            'system', '%s', 'value', doc ->> 'marker')),
                        'references', '[]'::jsonb);
                    END $$""".formatted(HELD, SYSTEM));
        }
        DatabaseExtractor inTheDatabase = new DatabaseExtractor() {
            @Override
            public String functionName() {
                return "race.parts";
            }

            @Override
            public Envelope extract(String typeName, byte[] payload) {
                throw new AssertionError("the type's extractor is in the database, and this "
                        + "process was asked instead");
            }
        };
        store = new PgObjectStore(ds, List.of(new TypeRegistration(TYPE, "state",
                IdentityClass.IDENTIFIER, Set.of(SYSTEM), Handling.operational(),
                inTheDatabase, List.of())));
    }

    @Test
    @Timeout(300)
    @Proving(DboPromises.SRCH_A_REINDEX_HOLDS_NO_TRANSACTION_WHILE_IT_EXTRACTS)
    @DisplayName("a record written while the database reindexes it keeps the envelope its own "
            + "write extracted, and the reindex does not count it as rebuilt")
    void aRowThatMovedDuringTheWalkIsLeftToTheWriteThatMovedIt() throws Exception {
        String marker = "m-" + UUID.randomUUID();
        PutResult first = store.put(PutRequest.create(TYPE,
                ("{\"marker\":\"" + marker + "-old\"}").getBytes(StandardCharsets.UTF_8)));

        int rebuilt;
        try (Connection holder = ds.getConnection(); Statement hold = holder.createStatement()) {
            hold.execute("SELECT pg_advisory_lock(" + HELD + ")");
            hold.execute("INSERT INTO race.gate VALUES (true)");
            CompletableFuture<Integer> reindex =
                    CompletableFuture.supplyAsync(() -> store.rebuildEnvelopes(TYPE));
            Eventually.until("the reindex reaching the record and waiting in its walk",
                    () -> { }, () -> walkIsWaiting(holder));
            // Closed before the write, which extracts through the same
            // function and would otherwise wait behind the same lock.
            hold.execute("DELETE FROM race.gate");
            store.put(PutRequest.update(TYPE, first.id(), first.versionId(),
                    ("{\"marker\":\"" + marker + "-new\"}").getBytes(StandardCharsets.UTF_8)));
            hold.execute("SELECT pg_advisory_unlock(" + HELD + ")");
            rebuilt = reindex.get(Eventually.PATIENCE.toSeconds(), TimeUnit.SECONDS);
        }

        String envelope = envelopeOf(first.id());
        assertTrue(envelope.contains(marker + "-new") && !envelope.contains(marker + "-old"),
                "the reindex wrote an envelope extracted from the payload it read over the one "
                        + "the later write extracted from its own: " + envelope);
        assertEquals(1, store.getByIdentifier(TYPE,
                        List.of(new Identifier(SYSTEM, marker + "-new"))).size(),
                "the record is not found by the identifier its current payload carries");
        assertEquals(0, store.getByIdentifier(TYPE,
                        List.of(new Identifier(SYSTEM, marker + "-old"))).size(),
                "the record is still found by an identifier only its older payload carried");
        assertEquals(0, rebuilt,
                "the one record moved under the reindex and was left alone, so nothing was "
                        + "rebuilt — a count that includes it says it was");
    }

    private static boolean walkIsWaiting(Connection holder) {
        try (Statement s = holder.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = "
                        + "'advisory' AND objid = " + HELD + " AND NOT granted"
                        + " AND database = (SELECT oid FROM pg_database"
                        + " WHERE datname = current_database())")) {
            rs.next();
            return rs.getLong(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String envelopeOf(String id) throws SQLException {
        try (Connection c = ds.getConnection();
                var ps = c.prepareStatement(
                        "SELECT envelope::text FROM " + Domains.tables("state")
                                + "_data WHERE id = ?::uuid")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the record is not where it was looked for");
                return rs.getString(1);
            }
        }
    }
}
