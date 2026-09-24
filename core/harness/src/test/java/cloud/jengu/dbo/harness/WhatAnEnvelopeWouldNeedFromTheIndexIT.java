package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.index.DefinitionRows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the searchable envelope would cost to build from the index, and why
 * its bar is not a checker's.
 *
 * <p>The other half of item 025's step 9. The first half — reading and writing
 * a document without the element model — was spiked and holds, and needed no
 * type knowledge at all. This half does: an envelope is the values a search
 * asks by, pulled out of a document by the search parameters the version
 * publishes, and those are compiled to jsonpath when they arrive exactly as
 * the invariants are.
 *
 * <p><b>A partial envelope is worse than a partial checker, and that is the
 * finding this exists to state.</b> A checker that declines a rule reports
 * nothing and the document is accepted, which is the same answer a correct
 * document gets — a lost refusal. An envelope that declines a parameter loses
 * a KEY, and a search by that key then finds nothing while looking exactly
 * like an answer. The database's own comment says it: a form the index does
 * not carry is a search that silently finds nothing, which is worse than an
 * error. So this counts what would have to be covered, rather than assuming a
 * majority is a good place to start.
 *
 * <p>What was built on the strength of this count is the second front end of
 * {@code DefinitionEnvelopes}, which drives the face's own typed rules from
 * the compiled parameters — not a builder of its own. Two answerers to one
 * specification is this store's arrangement on purpose; a third by accident
 * is not, and the count is what says the residue is small enough that the
 * existing rules can carry it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatAnEnvelopeWouldNeedFromTheIndexIT {

    /** What {@link SharedTenants.Shape#R4_FACE_ROOT} declares it operates on. */
    private static final List<String> DECLARED = List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Patient", "Observation");

    static SharedTenants.Tenant tenant;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
    }

    @Test
    @DisplayName("what the compiled search parameters ask of an evaluator, and how much of an "
            + "envelope a partial one would lose")
    void whatAnEnvelopeWouldCost() {
        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> uses = new TreeMap<>();
        int all = 0;
        int refused = 0;
        int withPredicate = 0;
        int navigationOnly = 0;
        List<String> beyondNavigation = new ArrayList<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT code, base, kind, unenforceable, predicate,
                            ARRAY(SELECT jsonb_array_elements_text(paths))
                       FROM definitions.definition_parameter
                      WHERE base = ANY(?)""")) {
            ps.setArray(1, c.createArrayOf("text", DECLARED.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all++;
                    byKind.merge(rs.getString(3) == null ? "?" : rs.getString(3), 1, Integer::sum);
                    if (rs.getString(4) != null) {
                        refused++;
                        continue;
                    }
                    if (rs.getString(5) != null) {
                        withPredicate++;
                    }
                    boolean plain = rs.getString(5) == null;
                    Object[] paths = (Object[]) rs.getArray(6).getArray();
                    for (Object path : paths) {
                        String one = String.valueOf(path);
                        for (String[] construct : new String[][] {
                                {"? (", "a filter"}, {"like_regex", "like_regex"},
                                {"starts with", "starts with"}, {"==", "equality"},
                                {".type()", "type()"}, {"exists(", "exists"}}) {
                            if (one.contains(construct[0])) {
                                uses.merge(construct[1], 1, Integer::sum);
                                plain = false;
                            }
                        }
                    }
                    if (plain) {
                        navigationOnly++;
                    } else {
                        beyondNavigation.add(rs.getString(2) + "." + rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the compiled parameters failed", e);
        }

        int compiled = all - refused;
        System.out.printf("%n=== what an envelope would need, over %s's declared types ===%n"
                + "parameters %d, of which the compiler refused %d by name%n"
                + "of the %d compiled: %d are plain navigation (%4.1f%%), %d carry a predicate%n",
                tenant.code(), all, refused, compiled, navigationOnly,
                100.0 * navigationOnly / compiled, withPredicate);
        byKind.forEach((kind, n) -> System.out.printf("  kind %-12s %4d%n", kind, n));
        uses.forEach((construct, n) -> System.out.printf("  uses %-12s %4d%n", construct, n));
        System.out.println("  beyond plain navigation: "
                + beyondNavigation.stream().limit(12).toList());

        assertTrue(all > 100, "too few parameters to say anything: " + all);
        // THE ASYMMETRY, asserted so it is not forgotten: what the compiler
        // already refused is a key the database does not carry either, so
        // those cost nothing to skip. Everything it DID compile is a key some
        // search asks by, and an evaluator covering only part of it would lose
        // the rest silently. So what is locked is the size of the residue —
        // the compiled parameters that need more than navigation — because
        // that is the list an envelope has to finish rather than approximate.
        assertTrue(beyondNavigation.size() < 20,
                "more parameters need more than navigation than was costed, so the residue an "
                        + "envelope has to cover is bigger than recorded: " + beyondNavigation);
        assertEquals(compiled, navigationOnly + beyondNavigation.size(),
                "a compiled parameter was counted as neither plain nor beyond plain");
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
