package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #33: every version links to the one before it, so a history cannot be
 * rewritten without the break showing.
 *
 * <p>The test that matters is the tamper: an edit made <b>behind the store's
 * back</b>, straight into Postgres, the way somebody with database access
 * would actually do it. A chain that only detects edits made through the
 * store's own API detects nothing worth detecting.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionChainIT {

    private static final String EID = "https://ee.ee/eid";

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("VersionChainIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID)));
        store = new PgObjectStore(ds, personality.registrations());
    }

    @AfterAll
    void down() {
    }

    private static byte[] patient(String eid, String family) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}"""
                .formatted(EID, eid, family).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("#33: an untouched history verifies, without asking anything outside")
    void anUntouchedHistoryVerifies() {
        PutResult created = store.put(PutRequest.create("Patient", patient("38001010001", "Kask")));
        store.put(new PutRequest("Patient", created.id(), null, patient("38001010001", "Kask-Tamm")));
        store.put(new PutRequest("Patient", created.id(), null, patient("38001010001", "Tamm")));

        PgObjectStore.ChainCheck check = store.verifyChain("Patient", created.id());

        assertTrue(check.isIntact(), "three honest versions must verify: " + check);
    }

    @Test
    @DisplayName("#33: a version edited straight in the database breaks the chain, and the break "
            + "is named")
    void anEditBehindTheStoresBackIsCaught() throws Exception {
        PutResult created = store.put(PutRequest.create("Patient", patient("38001010002", "Rebane")));
        store.put(new PutRequest("Patient", created.id(), null, patient("38001010002", "Rebane-Kukk")));
        store.put(new PutRequest("Patient", created.id(), null, patient("38001010002", "Kukk")));

        // Somebody with database access rewrites what version 2 said. No API,
        // no audit entry, no trace anywhere the store controls.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE history." + R4Personality.DOMAIN + "_history SET payload = ? "
                             + "WHERE id = ? AND version_id = 2")) {
            ps.setBytes(1, patient("38001010002", "Something-Else"));
            ps.setObject(2, UUID.fromString(created.id()));
            assertEquals(1, ps.executeUpdate(), "the tamper must actually land");
        }

        PgObjectStore.ChainCheck check = store.verifyChain("Patient", created.id());

        assertEquals(PgObjectStore.ChainCheck.Result.BROKEN, check.result(),
                "a rewritten version must not verify");
        assertEquals(2L, check.atVersion(),
                "and the walk names the first version that no longer follows");
    }

    @Test
    @DisplayName("#33: a deletion is a version too, and it is chained")
    void aTombstoneIsChained() {
        PutResult created = store.put(PutRequest.create("Patient", patient("38001010003", "Saar")));
        store.delete("Patient", created.id(), null);

        PgObjectStore.ChainCheck check = store.verifyChain("Patient", created.id());

        assertTrue(check.isIntact(),
                "an unchained tombstone would let a record be removed with no link to show it: "
                        + check);
    }

    @Test
    @DisplayName("#33: a replayed version is chained exactly as a live one — the restore path is "
            + "not a way around the chain")
    void aRestoredVersionIsChained() {
        // the shape a migration writes: chosen version, chosen moment
        PutResult created = store.put(PutRequest.create("Patient", patient("38001010004", "Mets")));
        store.put(PutRequest.restored("Patient", created.id(), patient("38001010004", "Mets-Oja"),
                2L, Instant.parse("2023-03-03T09:15:00Z")));

        PgObjectStore.ChainCheck check = store.verifyChain("Patient", created.id());

        assertTrue(check.isIntact(), "a restored version must be chained like any other: " + check);
    }
}
