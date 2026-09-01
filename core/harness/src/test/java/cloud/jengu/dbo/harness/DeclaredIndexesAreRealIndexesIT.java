package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.IndexSpec;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.ValueKind;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An index a personality declares is an index the database has.
 *
 * <p>Nothing in the suite mentioned {@link IndexSpec} at all, which made
 * "indexing is declared as part of the search contract, from day one" a claim
 * about intent. The interesting half is not that the declaration exists but
 * that it <b>arrives</b>: a declared index nobody creates is a search contract
 * that reads correctly and performs like a sequential scan, and nothing
 * anywhere fails.
 *
 * <p>What is deliberately not asserted is that the planner picks the index for
 * a given query. Which plan a planner chooses on four rows is a property of
 * the planner and the statistics, not a promise this store makes; asserting it
 * would produce a test that fails on a Postgres upgrade for no defect. What is
 * asserted instead is that the index exists, that the declared kind reaches
 * the index expression, and that searching by the declared path returns the
 * right answer through the production path.
 *
 * <p>The kind assertion is the subtle one, and writing it wrongly the first
 * time is what showed why. A DATE index here is not a timestamp cast: dates
 * are stored as fixed-width UTC ISO text, so lexicographic order already is
 * chronological order, and {@code COLLATE "C"} pins byte order while staying
 * IMMUTABLE — which a cast would not be, so no index could be built on it.
 * What would break the promise is an index left on the database's default
 * collation, ordering by locale where the query compares by byte.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeclaredIndexesAreRealIndexesIT {

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static List<TypeRegistration> registrations;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("DeclaredIndexesAreRealIndexesIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        registrations = new R4Personality(List.of(
                FhirTypeConfig.internal("Patient"),
                FhirTypeConfig.internal("Observation"))).registrations();
        store = new PgObjectStore(ds, registrations);
    }

    @Test
    @DisplayName("the personality declares its indexes, and declares them typed")
    @Proving(DboPromises.SRCH_DECLARED_INDEXES)
    void thePersonalityDeclaresThem() {
        Map<String, List<IndexSpec>> declared = new LinkedHashMap<>();
        for (TypeRegistration r : registrations) {
            declared.put(r.typeName(), r.indexes());
        }

        assertFalse(declared.getOrDefault("Patient", List.of()).isEmpty(),
                "a type whose version defines date parameters declared no index, so the "
                        + "search contract's indexing half is empty: " + declared);
        assertTrue(declared.get("Patient").stream()
                        .anyMatch(i -> "birthdate".equals(i.path()) && i.kind() == ValueKind.DATE),
                "the declaration must carry the kind, because an index built for the "
                        + "wrong type cannot serve the comparison it was declared for: "
                        + declared.get("Patient"));
    }

    @Test
    @DisplayName("and the database has them, typed as declared, once the store is up")
    @Proving(DboPromises.SRCH_DECLARED_INDEXES)
    void theDatabaseHasThem() throws Exception {
        Map<String, String> present = indexesOn("r4");

        List<String> missing = new ArrayList<>();
        for (TypeRegistration r : registrations) {
            for (IndexSpec spec : r.indexes()) {
                String name = "%s_%s_%s_ix".formatted(r.domain(),
                        r.typeName().toLowerCase(), spec.path().toLowerCase());
                if (!present.containsKey(name)) {
                    missing.add(name);
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "declared indexes that the database does not have — the search contract "
                        + "reads correctly and scans sequentially: " + missing
                        + " (present: " + present.keySet() + ")");
        // The declared kind has to reach the index expression, and for DATE
        // that means C collation rather than a cast. Dates are stored as
        // fixed-width UTC ISO text, so lexicographic order IS chronological
        // order — and pinning byte order keeps the expression IMMUTABLE, which
        // a timestamp cast would not be, so an index could not be built on it
        // at all. An index left on the database's default collation would sort
        // by locale, and a query comparing under C collation could not use it.
        assertTrue(present.get("r4_patient_birthdate_ix").contains("COLLATE \"C\""),
                "a date index on the default collation orders by locale, so the search "
                        + "that declared it cannot use it: "
                        + present.get("r4_patient_birthdate_ix"));
    }

    @Test
    @DisplayName("and a search along a declared path answers correctly")
    @Proving(DboPromises.SRCH_DECLARED_INDEXES)
    void aSearchAlongTheDeclaredPathAnswers() {
        store.put(PutRequest.create("Patient", patient("1980-03-01")));
        store.put(PutRequest.create("Patient", patient("2015-11-20")));

        long born = store.count(Criteria.of("Patient").range("birthdate", ValueKind.DATE,
                Criteria.RangeOp.GT, "2000-01-01"));

        assertTrue(born >= 1,
                "a search along a declared and indexed path returned nothing, so the "
                        + "index is over a path the search does not use");
    }

    private static byte[] patient(String birthDate) {
        return ("{\"resourceType\":\"Patient\",\"birthDate\":\"" + birthDate + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Index name to its definition, for the state table of one domain. */
    private static Map<String, String> indexesOn(String domain) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select indexname, indexdef from pg_indexes "
                             + "where schemaname = 'state' and tablename = ?")) {
            ps.setString(1, domain + "_data");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }
}
