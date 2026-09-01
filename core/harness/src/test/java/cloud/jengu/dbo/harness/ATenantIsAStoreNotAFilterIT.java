package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two tenants, one identifier, and no way from either store to the other's row.
 *
 * <p>{@link TheEngineApiCannotNameATenantTest} says a caller cannot ask for
 * another tenant's data because the API has nowhere to put the request. This
 * drives the other end: even holding an identifier that certainly exists —
 * because we just wrote it next door — the neighbouring store answers empty.
 *
 * <p>The identifier is deliberately the <b>same</b> in both stores. A test
 * using different ids proves only that a store cannot find something that does
 * not exist, which is not a claim about isolation at all. Writing one id into
 * two tenants and getting two different records back is the difference between
 * "scoped by a predicate somebody remembered" and "scoped by which database
 * the handle opens".
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATenantIsAStoreNotAFilterIT {

    private static final String EID = "https://ee.ee/eid";

    static PgObjectStore uks;
    static PgObjectStore kaks;

    @BeforeAll
    void up() {
        uks = storeOn("ATenantIsAStoreNotAFilterIT_uks");
        kaks = storeOn("ATenantIsAStoreNotAFilterIT_kaks");
    }

    private static PgObjectStore storeOn(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor(database));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return new PgObjectStore(ds,
                new R4Personality(List.of(FhirTypeConfig.identifier("Patient", EID)))
                        .registrations());
    }

    private static byte[] patient(String eid, String family) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}"""
                .formatted(EID, eid, family).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("one identifier written into two tenants is two records, and neither "
            + "store can reach the other's")
    @Proving(DboPromises.TEN_STRUCTURAL_SCOPING)
    void theSameIdInTwoTenantsIsTwoRecords() {
        String id = UUID.randomUUID().toString();

        uks.put(new PutRequest("Patient", id, null, patient("38001010001", "Uksik")));
        kaks.put(new PutRequest("Patient", id, null, patient("47102030004", "Kaksik")));

        String fromUks = new String(uks.get("Patient", id).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        String fromKaks = new String(kaks.get("Patient", id).orElseThrow().payload(),
                StandardCharsets.UTF_8);

        assertTrue(fromUks.contains("Uksik") && !fromUks.contains("Kaksik"),
                "the first tenant's store answered with the other tenant's record: " + fromUks);
        assertTrue(fromKaks.contains("Kaksik") && !fromKaks.contains("Uksik"),
                "the second tenant's store answered with the other tenant's record: " + fromKaks);
    }

    @Test
    @DisplayName("a record that exists next door is simply absent, identifier and all")
    @Proving(DboPromises.TEN_STRUCTURAL_SCOPING)
    void whatOnlyTheNeighbourHoldsIsNotThere() {
        String eid = "39912310007";
        PutResult onlyNextDoor = kaks.put(PutRequest.create("Patient", patient(eid, "Naaber")));

        Optional<StoredObject> byId = uks.get("Patient", onlyNextDoor.id());
        List<StoredObject> byIdentifier = uks.getByIdentifier("Patient",
                List.of(new cloud.jengu.dbo.core.api.Identifier(EID, eid)));

        assertTrue(byId.isEmpty(),
                "a neighbouring tenant's record was readable by its id, which is the "
                        + "cross-tenant read this design exists to make unreachable");
        assertTrue(byIdentifier.isEmpty(),
                "the identifier lookup crossed the tenant boundary, which is worse than "
                        + "the id lookup: an identifier is a thing an outsider can guess");
        assertEquals(1, kaks.getByIdentifier("Patient",
                        List.of(new cloud.jengu.dbo.core.api.Identifier(EID, eid))).size(),
                "and the record must genuinely exist next door, or this test is asserting "
                        + "that nothing can be found anywhere");
    }

    @Test
    @DisplayName("a search sees one tenant's rows because it can see no others")
    @Proving(DboPromises.TEN_STRUCTURAL_SCOPING)
    void aSearchIsScopedByWhichHandleAnsweredIt() {
        long before = uks.count(Criteria.of("Patient"));

        for (int i = 0; i < 3; i++) {
            kaks.put(PutRequest.create("Patient", patient("5000102000" + i, "Kolmik" + i)));
        }

        assertEquals(before, uks.count(Criteria.of("Patient")),
                "rows written to another tenant changed this tenant's count, so the two "
                        + "are sharing storage that a predicate is separating");
        assertTrue(uks.select(Criteria.of("Patient")).stream()
                        .noneMatch(o -> new String(o.payload(), StandardCharsets.UTF_8)
                                .contains("Kolmik")),
                "a select returned the neighbour's rows");
    }
}
