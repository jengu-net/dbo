package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A unit of many writes is applied as a set — one statement per table for
 * all of them — and a set must decide exactly what the writes would have
 * decided one by one: an object it already holds is rewritten, not
 * duplicated; an identity claimed by somebody else refuses the unit; and an
 * identity claimed twice within the unit is a conflict between its two
 * makers, not a database error on the way in.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AUnitOfWritesDecidesLikeEachWriteWouldIT {

    private static final String EID = "https://ee.ee/eid";
    private static final String TYPE = "Practitioner";

    static PgObjectStore store;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AUnitOfWritesDecidesLikeEachWriteWouldIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.identifier(TYPE, EID)));
        store = new PgObjectStore(ds, declared.registrations());
        declared.store(store, "https://dbo.test/fhir");
    }

    private static PutRequest practitioner(String id, String value) {
        return new PutRequest(TYPE, id, null, ("{\"resourceType\":\"Practitioner\",\"identifier\":[{\"system\":\""
                + EID + "\",\"value\":\"" + value + "\"}]}").getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> holding(String value) {
        return store.getByIdentifier(TYPE, List.of(new Identifier(EID, value))).stream()
                .map(o -> o.id()).toList();
    }

    @Test
    @DisplayName("a unit that rewrites what the store holds advances each version and moves the claims")
    void aUnitThatRewritesWhatItHoldsAdvancesTheVersionsAndMovesTheClaims() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        List<PutResult> first = store.transact(List.of(practitioner(a, "one-1"), practitioner(b, "one-2")));
        assertTrue(first.get(0).created() && first.get(1).created());
        List<PutResult> second = store.transact(List.of(practitioner(a, "two-1"), practitioner(b, "two-2")));
        assertEquals(List.of(2L, 2L), second.stream().map(PutResult::versionId).toList());
        assertEquals(List.of(), holding("one-1"), "the claim the rewrite gave up is still held");
        assertEquals(List.of(a), holding("two-1"));
        assertEquals(List.of(b), holding("two-2"));
        assertEquals(2, store.history(TYPE, a).size(), "history is written for a rewrite in a unit");
    }

    @Test
    @DisplayName("a unit claiming somebody else's identity is refused whole")
    void aUnitClaimingSomebodyElsesIdentityIsRefusedWhole() {
        String holder = UUID.randomUUID().toString();
        store.put(practitioner(holder, "held"));
        String newcomer = UUID.randomUUID().toString();
        String bystander = UUID.randomUUID().toString();
        IdentityConflictException refused = assertThrows(IdentityConflictException.class, () ->
                store.transact(List.of(practitioner(bystander, "innocent"), practitioner(newcomer, "held"))));
        assertEquals(holder, refused.existingId());
        assertEquals(List.of(holder), holding("held"));
        assertTrue(store.get(TYPE, bystander).isEmpty(), "a unit refused for one write kept another");
    }

    @Test
    @DisplayName("a unit claiming one identity twice is a conflict between its two makers")
    void aUnitClaimingOneIdentityTwiceIsAConflictBetweenItsTwoMakers() {
        String x = UUID.randomUUID().toString();
        String y = UUID.randomUUID().toString();
        IdentityConflictException refused = assertThrows(IdentityConflictException.class, () ->
                store.transact(List.of(practitioner(x, "twice"), practitioner(y, "twice"))));
        assertEquals(x, refused.existingId());
        assertEquals(y, refused.claimingId());
        assertEquals(List.of(), holding("twice"));
    }
}
