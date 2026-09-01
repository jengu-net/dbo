package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.Adjudications;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.identity.Adjudication;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;
import cloud.jengu.dbo.core.api.identity.Resolution;
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
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision is written down, and found again the next time the same
 * claim turns up.
 *
 * <p>Recording it is only half. Without the recall, the record is an archive
 * nobody consults and every near-match is adjudicated from nothing, forever.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdjudicationPersistedIT {

    private static final String LICENCE = "https://issuer.example/driving-licence";
    private static final Instant WHEN = Instant.parse("2026-08-17T09:15:00Z");

    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AdjudicationPersistedIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE adjudications");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "adjudications");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, IdentityModel.registrations());
    }

    @Test
    @Timeout(300)
    @DisplayName("a decision recorded today changes what resolution offers tomorrow")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void aRecordedDecisionIsFoundAgain() {
        IdentityClaim claim = IdentityClaim.authenticated(LICENCE, "K7777001");

        // before anybody has looked, a proven claim resolves on its own
        Resolution first = Resolution.of(List.of(claim), Map.of(claim, List.of("person-2")),
                Adjudications.priorRejections(store, List.of(claim)));
        assertEquals("person-2", first.certain().orElseThrow());

        Adjudications.record(store, Adjudication.created("person-9", List.of("person-2"),
                List.of(claim), "reception-desk-7", WHEN, "same surname, different birth date"));

        Set<String> rejected = Adjudications.priorRejections(store, List.of(claim));
        assertEquals(Set.of("person-2"), rejected,
                "the decision must be findable by the claim that provoked it, or nobody will "
                        + "ever consult it");

        Resolution second = Resolution.of(List.of(claim),
                Map.of(claim, List.of("person-2")), rejected);
        assertTrue(second.certain().isEmpty(),
                "the same evidence that resolved cleanly before must now go back to a person — "
                        + "a machine does not silently reverse somebody's conclusion");
        assertTrue(second.candidates().get(0).previouslyRejected());
    }

    @Test
    @Timeout(300)
    @DisplayName("a decision cannot be edited — revising means recording a new one")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void decisionsAreAppendOnly() {
        IdentityClaim claim = IdentityClaim.authenticated(LICENCE, "K7777002");
        String id = Adjudications.record(store, Adjudication.bound("person-3", List.of(),
                List.of(claim), "reception-desk-7", WHEN, "photograph matches"));

        cloud.jengu.dbo.core.api.HandlingRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.put(new PutRequest("Adjudication", id, null,
                        ("{\"outcome\":\"BOUND\",\"subjectId\":\"person-4\","
                                + "\"rejected\":[],\"presented\":[]}")
                                .getBytes(StandardCharsets.UTF_8))));

        assertTrue(refused.getMessage().contains("append-only"),
                "editing a past decision would destroy the evidence of what somebody concluded "
                        + "and when, which is the only reason to keep it: " + refused.getMessage());
    }

    @Test
    @Timeout(300)
    @DisplayName("a decision about one claim says nothing about another")
    @Proving(DboPromises.IDN_A_DECISION_IS_EVIDENCE)
    void decisionsDoNotLeakAcrossClaims() {
        IdentityClaim decided = IdentityClaim.authenticated(LICENCE, "K7777003");
        IdentityClaim unrelated = IdentityClaim.authenticated(LICENCE, "K7777004");

        Adjudications.record(store, Adjudication.created("person-11", List.of("person-5"),
                List.of(decided), "reception-desk-7", WHEN, "different person"));

        assertEquals(Set.of(), Adjudications.priorRejections(store, List.of(unrelated)),
                "rejections are scoped to the claim that provoked them, or one decision would "
                        + "quietly bias every later match");
    }
}
