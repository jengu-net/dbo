package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.index.DefinitionRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the two checks after fixed and pattern would actually cost.
 *
 * <p>Cardinality, fixed and pattern are answered from the index because
 * everything they need is a column. The other two named on this item's
 * critical path are not like that, and neither had been costed: a required
 * binding needs CODES, which are not definitions at all, and a slice needs a
 * PREDICATE evaluated, which the database gets from Postgres's jsonpath and
 * an index in heap would have to evaluate itself.
 *
 * <p>So this counted rather than built, in the shape the rest of this item
 * was decided in: what is there, how much of it is the easy form, and what
 * the residue is. A checker written before the count would have been a guess
 * about which half of the work matters.
 *
 * <p><b>Both are built now, and this is what holds them up.</b> The two
 * numbers it records are the premises they rest on — that everything a
 * required binding names is content the tenant already holds, and that every
 * predicate is the one form — so each is asserted rather than printed. A
 * version that changed either would otherwise move a checker from answering
 * to guessing without anything saying so.
 *
 * <p>Shared, and a face root, because both questions are about what a version
 * says rather than about a tenant that holds it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatTheRestOfACheckerWouldNeedIT {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /** What {@link SharedTenants.Shape#R4_FACE_ROOT} declares it operates on. */
    private static final Set<String> DECLARED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Patient", "Observation"));

    private static final ObjectMapper JSON = new ObjectMapper();

    static SharedTenants.Tenant tenant;
    static Set<String> closure;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : DECLARED) {
            seeds.add(PREFIX + type);
        }
        closure = DefinitionRows.closureOf(source(), seeds);
    }

    @Test
    @DisplayName("what a required binding would cost to answer in heap, counted rather than "
            + "guessed at")
    void whatARequiredBindingWouldCost() throws Exception {
        int required = count("""
                SELECT count(*) FROM definitions.definition_element
                 WHERE canonical = ANY(?) AND binding_strength = 'required'
                   AND binding_valueset IS NOT NULL""");
        Set<String> valueSets = strings("""
                SELECT DISTINCT binding_valueset FROM definitions.definition_element
                 WHERE canonical = ANY(?) AND binding_strength = 'required'
                   AND binding_valueset IS NOT NULL""");

        // A value set is held as its COMPOSE and not as an expansion, which is
        // the whole finding: the index holds which value set an element is
        // bound to, and nothing holds what is in it.
        Map<String, Integer> byShape = new TreeMap<>();
        int held = 0;
        long codes = 0;
        int onlyWithoutTheVersion = 0;
        for (String url : valueSets) {
            String compose = compose(url);
            if (compose == null && url.indexOf('|') > 0) {
                // A binding names a canonical WITH its version —
                // ...|4.0.1 — and the terminology is keyed by url. Asked as
                // written, every one of these reads as content the tenant does
                // not hold, which would have been the wrong conclusion
                // entirely: it holds them, under the name without the version.
                compose = compose(url.substring(0, url.indexOf('|')));
                if (compose != null) {
                    onlyWithoutTheVersion++;
                }
            }
            if (compose == null) {
                byShape.merge("not held by this tenant", 1, Integer::sum);
                continue;
            }
            held++;
            // The store keeps a compose under its own names — includes and
            // excludes — rather than FHIR's singular ones. Read with the wrong
            // ones this reported every value set as a shape it did not
            // recognise, which is the same silent wrong answer the |version
            // above would have given.
            JsonNode json = JSON.readTree(compose);
            boolean filters = false;
            boolean nested = false;
            boolean enumerated = false;
            boolean wholeSystem = false;
            for (JsonNode include : json.path("includes")) {
                filters |= include.has("filter");
                nested |= include.has("valueSet");
                enumerated |= include.has("concept");
                wholeSystem |= include.has("system") && !include.has("concept")
                        && !include.has("filter");
            }
            if (!json.path("excludes").isEmpty()) {
                byShape.merge("has an exclude", 1, Integer::sum);
            }
            if (filters) {
                byShape.merge("a filter, which is an expansion", 1, Integer::sum);
            } else if (nested) {
                byShape.merge("another value set, which is an expansion", 1, Integer::sum);
            } else if (enumerated) {
                byShape.merge("codes written out", 1, Integer::sum);
            } else if (wholeSystem) {
                byShape.merge("a whole code system", 1, Integer::sum);
                codes += count("""
                        SELECT count(*) FROM definitions.term_concept WHERE system = ANY(?)""",
                        systemsOf(json));
            } else {
                byShape.merge("something else", 1, Integer::sum);
            }
        }

        // What the tenant holds AT ALL, because "none of the 43" means one
        // thing if the terminology tables are full and another if they are
        // empty, and the second would be a fact about this tenant rather than
        // about what a binding check needs.
        int allValueSets = scalar("SELECT count(*) FROM definitions.term_valueset");
        int allConcepts = scalar("SELECT count(*) FROM definitions.term_concept");

        System.out.printf("%n=== what a required binding would need, over %s's closure ===%n"
                + "elements with a required binding %d, naming %d distinct value sets%n"
                + "of those, held by this tenant    %d%n"
                + "codes behind the whole-system ones %d%n"
                + "this tenant holds %d value sets and %d concepts in all%n"
                + "found only after dropping a |version from the binding %d%n",
                tenant.code(), required, valueSets.size(), held, codes,
                allValueSets, allConcepts, onlyWithoutTheVersion);
        byShape.forEach((shape, n) -> System.out.printf("  %-44s %d%n", shape, n));

        assertTrue(required > 0, "a version with no required bindings is not a version");
        assertTrue(valueSets.size() > 10,
                "too few value sets for the shape count to say anything: " + valueSets.size());
        // THE ANSWER, locked rather than printed: everything a required
        // binding in this closure names is content the tenant already holds.
        // If that stops being true, whatever answers a binding in heap is
        // answering about content that is not there, and this says so here
        // rather than in a wrong verdict on somebody's write.
        assertEquals(valueSets.size(), held,
                "a required binding names a value set this tenant does not hold");
        // And the codes behind them are the cost. Six hundred is the finding:
        // a required-binding check over a tenant's closure is not an
        // expansion problem, it is a few hundred strings beside the index.
        assertTrue(codes > 0 && codes < 100_000,
                "the codes behind the required bindings are not what was measured: " + codes);
    }

    @Test
    @DisplayName("what a slice would cost to answer in heap: the shapes the located steps "
            + "actually take")
    void whatASliceWouldCost() {
        // A row's steps are jsonpaths relative to its parent instance: one
        // normally, several for a choice, one with a predicate for a slice.
        // The database hands them to Postgres. Anything in heap evaluates
        // them itself, so what matters is how many shapes there are.
        int plain = 0;
        int choice = 0;
        int sliced = 0;
        int unlocatable = 0;
        Map<String, Integer> predicates = new TreeMap<>();
        List<String> beyondEquality = new ArrayList<>();
        // Over everything the tenant holds rows for and not only the closure:
        // a slice is something a PROFILE writes, and the closure of six
        // declared types is base definitions almost entirely. Counting only
        // there would answer that there is no slicing in FHIR.
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT steps FROM definitions.definition_element")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Array array = rs.getArray(1);
                    Object[] steps = array == null ? new Object[0] : (Object[]) array.getArray();
                    if (steps.length == 0) {
                        unlocatable++;
                        continue;
                    }
                    boolean predicate = false;
                    for (Object step : steps) {
                        if (step != null && String.valueOf(step).contains("?")) {
                            predicate = true;
                            String one = String.valueOf(step);
                            predicates.merge(shapeOf(one), 1, Integer::sum);
                            // What an evaluator would have to understand, read
                            // off the predicate itself rather than off the
                            // elided shape: anything but equality joined by
                            // and is a second form to write.
                            String test = one.substring(one.indexOf('?'));
                            if (test.contains("like_regex") || test.contains("||")
                                    || test.contains("exists") || test.contains(">")
                                    || test.contains("<") || test.contains("!=")
                                    || test.contains("starts with")) {
                                beyondEquality.add(one);
                            }
                        }
                    }
                    if (predicate) {
                        sliced++;
                    } else if (steps.length > 1) {
                        choice++;
                    } else {
                        plain++;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the located steps failed", e);
        }

        int all = plain + choice + sliced + unlocatable;
        System.out.printf("%n=== what a slice would need, over everything %s holds ===%n"
                + "one plain step   %5d  (%4.1f%%)%n"
                + "several, a choice %4d  (%4.1f%%)%n"
                + "with a predicate  %4d  (%4.1f%%)%n"
                + "no step at all    %4d  (%4.1f%%)%n", tenant.code(),
                plain, 100.0 * plain / all, choice, 100.0 * choice / all,
                sliced, 100.0 * sliced / all, unlocatable, 100.0 * unlocatable / all);
        predicates.forEach((shape, n) -> System.out.printf("  %-40s %d%n", shape, n));

        assertTrue(all > 500, "too few rows for the shape count to say anything: " + all);
        assertTrue(sliced > 0, "no predicate at all, so the shapes say nothing");
        // THE ANSWER, locked: every predicate in the whole of what this tenant
        // holds is equality, optionally conjoined. Not a comparison, not a
        // regex, not an existence test. So what a slice needs in heap is an
        // evaluator for one form over a path of a few segments, and the
        // moment a sixth shape appears this fails and says which.
        assertEquals(List.of(), beyondEquality,
                "a predicate appeared that is not equality, so a checker in heap needs more "
                        + "than the one form: " + beyondEquality);
    }

    /** A predicate reduced to its form, so a hundred slices are a handful of shapes. */
    private static String shapeOf(String step) {
        return step.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("^[^?]*\\?", "? ");
    }

    private static Set<String> systemsOf(JsonNode compose) {
        Set<String> systems = new LinkedHashSet<>();
        for (JsonNode include : compose.path("includes")) {
            if (include.hasNonNull("system")) {
                systems.add(include.get("system").asText());
            }
        }
        return systems;
    }

    private static String compose(String url) {
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT compose::text FROM definitions.term_valueset WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading a value set's compose failed", e);
        }
    }

    private static int count(String sql) {
        return count(sql, closure);
    }

    /** A count over the whole of a table, which takes no canonical. */
    private static int scalar(String sql) {
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("counting failed", e);
        }
    }

    private static int count(String sql, Set<String> over) {
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", over.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("counting failed", e);
        }
    }

    private static Set<String> strings(String sql) {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", closure.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading failed", e);
        }
        return out;
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
