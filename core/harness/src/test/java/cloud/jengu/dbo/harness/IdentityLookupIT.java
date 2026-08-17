package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.Adjudications;
import cloud.jengu.dbo.auth.Identities;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.identity.Adjudication;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;
import cloud.jengu.dbo.core.api.identity.Resolution;
import cloud.jengu.dbo.postgres.PgObjectStore;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#39: the lookup lives with the recall, so a caller cannot do one and
 * forget the other.
 *
 * <p>The failure that motivates this is quiet: resolution offers a candidate
 * somebody examined and rejected last week as though for the first time, and
 * the adjudication record is an archive nobody reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityLookupIT {

    private static final String EE = "https://eesti.ee/isikukood";
    private static final String LICENCE = "https://issuer.example/driving-licence";
    private static final Instant NOW = Instant.parse("2026-08-17T11:00:00Z");

    static PgObjectStore store;

    /** A minimal identity record: whatever identifiers its payload lists. */
    private static final EnvelopeExtractor SUBJECT = (type, payload) -> {
        Envelope e = new Envelope();
        String json = new String(payload, StandardCharsets.UTF_8);
        int at = 0;
        while ((at = json.indexOf("\"system\":\"", at)) >= 0) {
            int systemEnd = json.indexOf('"', at + 10);
            String system = json.substring(at + 10, systemEnd);
            int valueAt = json.indexOf("\"value\":\"", systemEnd) + 9;
            String value = json.substring(valueAt, json.indexOf('"', valueAt));
            e.identifier(system, value);
            at = valueAt;
        }
        return e;
    };

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("IdentityLookupIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE identity_lookup");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "identity_lookup");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        List<TypeRegistration> types = new ArrayList<>(IdentityModel.registrations());
        types.add(new TypeRegistration("Subject", IdentityModel.DOMAIN,
                IdentityClass.IDENTIFIER, java.util.Set.of(EE, LICENCE),
                Handling.operational(), SUBJECT, List.of()));
        store = new PgObjectStore(ds, types);
    }

    private static String subject(String system, String value) {
        return store.put(PutRequest.create("Subject",
                ("{\"identifier\":[{\"system\":\"" + system + "\",\"value\":\"" + value + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: a proven claim finds its record without the caller writing a query")
    void aClaimFindsItsRecord() {
        String id = subject(EE, "38001010021");

        Resolution resolution = Identities.resolve(store, "Subject",
                List.of(IdentityClaim.authenticated(EE, "38001010021")));

        assertEquals(id, resolution.certain().orElseThrow());
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: a claim nobody holds resolves to nothing, which is an ordinary answer")
    void anUnknownClaimFindsNobody() {
        Resolution resolution = Identities.resolve(store, "Subject",
                List.of(IdentityClaim.authenticated(EE, "39912310099")));

        assertEquals(List.of(), resolution.candidates());
        assertTrue(resolution.certain().isEmpty());
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: the lookup folds in what somebody already decided, without being asked")
    void priorDecisionsAreAppliedAutomatically() {
        String id = subject(LICENCE, "K5150001");
        IdentityClaim claim = IdentityClaim.authenticated(LICENCE, "K5150001");

        assertEquals(id, Identities.resolve(store, "Subject", List.of(claim))
                .certain().orElseThrow(), "resolves cleanly before anybody has judged it");

        Adjudications.record(store, Adjudication.created("someone-else", List.of(id),
                List.of(claim), "reception-desk-7", NOW, "same licence number, different person"));

        Resolution after = Identities.resolve(store, "Subject", List.of(claim));

        assertTrue(after.certain().isEmpty(),
                "the caller did nothing differently — the recall is attached to the lookup, so "
                        + "forgetting it is not an option a caller has");
        assertTrue(after.candidates().get(0).previouslyRejected());
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: claims pointing at different records leave the choosing to a person")
    void twoRecordsMeanNobodyIsCertain() {
        String estonian = subject(EE, "38001010022");
        String licensed = subject(LICENCE, "K5150002");

        Resolution resolution = Identities.resolve(store, "Subject", List.of(
                IdentityClaim.authenticated(EE, "38001010022"),
                IdentityClaim.authenticated(LICENCE, "K5150002")));

        assertTrue(resolution.certain().isEmpty());
        assertEquals(2, resolution.candidates().size());
        assertTrue(resolution.candidates().stream()
                .map(c -> c.subjectId()).toList().containsAll(List.of(estonian, licensed)));
        assertTrue(resolution.needsAdjudication());
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: presenting nothing asks nothing of the store")
    void noClaimsIsNotAQuery() {
        Resolution resolution = Identities.resolve(store, "Subject", List.of());

        assertEquals(List.of(), resolution.candidates());
    }
}
