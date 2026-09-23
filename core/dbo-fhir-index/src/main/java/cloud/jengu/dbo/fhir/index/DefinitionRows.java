package cloud.jengu.dbo.fhir.index;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The index as a projection of the rows a definition was expanded into.
 *
 * <p>The database is the specification and the source; the index is a
 * projection of it. One expansion feeds both — the cutter writes
 * {@code definitions.definition_element}, the SQL checks read those rows, and
 * this reads the same rows into arrays. A second expansion, out of the
 * packages, would be a second specification with nothing comparing them.
 *
 * <p>Reading the packages instead is what the spike this replaces did, and it
 * cannot supply the three things the design needs: the base-and-overlay
 * split, since a tenant's own profiles are rows and were never in a package;
 * the face image path, since an image carries rows; and a narrowed closure,
 * since what a tenant holds is what arrived rather than what HL7 published.
 *
 * <p><b>No driver.</b> Everything here is {@code java.sql} against whatever
 * {@link DataSource} the caller has. The statements are prepared even where
 * they take no value, because this store never holds a {@link
 * java.sql.Statement} at all (REQ-DBO-CORE-PARAMETERIZED-SQL) and a ratchet
 * reads the bytecode rather than the intent.
 */
public final class DefinitionRows {

    private DefinitionRows() {
    }

    /**
     * Every structure the named ones are composed of, including themselves.
     *
     * <p><b>Composition is followed, reference is not.</b> An element typed
     * {@code HumanName} reaches {@code HumanName}. An element typed
     * {@code Reference(Condition)} reaches {@code Reference} and stops: a
     * target profile constrains what may be pointed at rather than naming
     * something this tenant operates on, and {@code Condition} enters only
     * where the tenant declares it. That is the one rule a closure would be
     * meaningless without, and it is what makes a tenant's share of a version
     * a fourteenth rather than all of it.
     *
     * <p>The walk is over rows that already carry every edge it needs, so it
     * is one statement rather than a fetch per structure.
     */
    public static Set<String> closureOf(DataSource ds, Collection<String> declared) {
        Set<String> closure = new LinkedHashSet<>();
        if (declared.isEmpty()) {
            return closure;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     WITH RECURSIVE closure(canonical) AS (
                         SELECT unnest(?)
                       UNION
                         -- One self-reference, because Postgres allows one:
                         -- both edges an element offers come out of one lateral.
                         SELECT reached.canonical
                           FROM closure c
                           JOIN definitions.definition_element e
                             ON e.canonical = c.canonical
                          CROSS JOIN LATERAL (
                                SELECT 'http://hl7.org/fhir/StructureDefinition/'
                                       || (t ->> 'code')
                                  FROM jsonb_array_elements(e.types) AS t
                                 WHERE t ->> 'code' ~ '^[A-Z]'
                                UNION ALL
                                SELECT e.base_definition WHERE e.base_definition IS NOT NULL
                          ) AS reached(canonical)
                          WHERE reached.canonical IS NOT NULL
                     )
                     SELECT canonical FROM closure ORDER BY canonical""")) {
            ps.setArray(1, textArray(c, declared));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    closure.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "walking the closure of " + declared.size() + " declared types failed", e);
        }
        return closure;
    }

    /**
     * What a dependent needs from this upstream, as a set of names.
     *
     * <p><b>A filter is not a predicate that travels.</b> It is a set of
     * names, computed once, agreed between the two ends: the dependent says
     * which types it operates on, the upstream answers with the canonicals,
     * and the upstream then selects by name rather than executing anybody
     * else's query. Computed HERE and not there because a tenant cannot
     * compute the closure of definitions it does not hold — which is the
     * whole reason this is derivable rather than written.
     *
     * <p><b>It closes over grains, not records.</b> Terminology's grain is a
     * code system together with the value sets that draw on it, and that is a
     * promise this store already keeps. A manifest naming half a grain would
     * produce a stream that breaks on arrival, so the grain is closed here,
     * where the manifest is computed, rather than discovered there.
     *
     * @param structures the definitional closure of the declared types
     * @param valueSets  every value set a required binding in that closure
     *                   names, and every one that draws on a code system
     *                   those name
     * @param codeSystems the systems those value sets are built from
     */
    public record Manifest(Set<String> structures, Set<String> valueSets,
            Set<String> codeSystems) {

        /** How many names the dependent would be sent. */
        public int size() {
            return structures.size() + valueSets.size() + codeSystems.size();
        }
    }

    /** The manifest for a dependent that declares these types. */
    public static Manifest manifestFor(DataSource ds, Collection<String> declared) {
        Set<String> structures = closureOf(ds, declared);
        Set<String> valueSets = new LinkedHashSet<>();
        Set<String> codeSystems = new LinkedHashSet<>();
        if (structures.isEmpty()) {
            return new Manifest(structures, valueSets, codeSystems);
        }
        try (Connection c = ds.getConnection()) {
            // Every value set a REQUIRED binding names. A weaker binding is
            // advice, and advice a dependent cannot answer is not a stream it
            // needs.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT DISTINCT split_part(binding_valueset, '|', 1)
                      FROM definitions.definition_element
                     WHERE canonical = ANY(?) AND binding_strength = 'required'
                       AND binding_valueset IS NOT NULL""")) {
                ps.setArray(1, textArray(c, structures));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        valueSets.add(rs.getString(1));
                    }
                }
            }
            // The grain, closed both ways and to a fixed point: a value set
            // brings the systems it is built from, and a system brings the
            // value sets that draw on it.
            int before;
            do {
                before = valueSets.size() + codeSystems.size();
                codeSystems.addAll(systemsOf(c, valueSets));
                valueSets.addAll(drawingOn(c, codeSystems));
            } while (valueSets.size() + codeSystems.size() > before);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "computing the manifest for " + declared.size() + " declared types failed", e);
        }
        return new Manifest(structures, valueSets, codeSystems);
    }

    private static Set<String> systemsOf(Connection c, Set<String> valueSets)
            throws SQLException {
        Set<String> systems = new LinkedHashSet<>();
        if (valueSets.isEmpty()) {
            return systems;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT part ->> 'system'
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> 'includes', '[]'::jsonb)) AS part
                 WHERE vs.url = ANY(?) AND part ->> 'system' IS NOT NULL""")) {
            ps.setArray(1, textArray(c, valueSets));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    systems.add(rs.getString(1));
                }
            }
        }
        return systems;
    }

    private static Set<String> drawingOn(Connection c, Set<String> systems) throws SQLException {
        Set<String> valueSets = new LinkedHashSet<>();
        if (systems.isEmpty()) {
            return valueSets;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT vs.url
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> 'includes', '[]'::jsonb)) AS part
                 WHERE part ->> 'system' = ANY(?)""")) {
            ps.setArray(1, textArray(c, systems));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    valueSets.add(rs.getString(1));
                }
            }
        }
        return valueSets;
    }

    /**
     * The named structures, as an index.
     *
     * <p>Named rather than all of them: what pays is one index per tenant
     * over the closure of what it declared, because a face whose tenants
     * between them declared every resource would reach almost the whole
     * corpus and share nothing worth sharing.
     *
     * <p>Structures nothing has rows for are simply absent, which is the
     * honest answer — a tenant holds what arrived.
     */
    public static DefinitionIndex over(DataSource ds, Collection<String> canonicals) {
        DefinitionIndex.Builder index = DefinitionIndex.builder();
        if (canonicals.isEmpty()) {
            return index.build();
        }
        try (Connection c = ds.getConnection()) {
            Map<String, List<DefinitionIndex.Invariant>> invariants = invariantsOf(c, canonicals);
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT canonical, element_id, path, min_occurs, max_occurs,
                           binding_strength, binding_valueset, fixed::text, pattern::text,
                           (SELECT array_agg(t ->> 'code' ORDER BY n)
                              FROM jsonb_array_elements(types) WITH ORDINALITY AS a(t, n)),
                           parent_id, steps, definition_version
                      FROM definitions.definition_element
                     WHERE canonical = ANY(?)
                     ORDER BY canonical, ordinal""")) {
                ps.setArray(1, textArray(c, canonicals));
                try (ResultSet rs = ps.executeQuery()) {
                    String open = null;
                    while (rs.next()) {
                        String canonical = rs.getString(1);
                        if (!canonical.equals(open)) {
                            index.structure(canonical, rs.getString(13));
                            open = canonical;
                        }
                        int max = rs.getInt(5);
                        if (rs.wasNull()) {
                            max = DefinitionIndex.UNBOUNDED;
                        }
                        index.element(rs.getString(2), rs.getString(11), rs.getString(3),
                                rs.getInt(4), max, codes(rs.getArray(10)), rs.getString(6),
                                rs.getString(7), rs.getString(8), rs.getString(9),
                                predicateOf(rs.getArray(12)));
                        for (DefinitionIndex.Invariant rule
                                : invariants.getOrDefault(canonical + "|" + rs.getString(2),
                                        List.of())) {
                            index.invariant(rule.key(), rule.severity(), rule.expression(), rule.path());
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "reading " + canonicals.size() + " definitions into an index failed", e);
        }
        return index.build();
    }

    /**
     * One search parameter, compiled.
     *
     * @param code      the parameter's own code, which names the envelope key
     * @param base      the resource type it asks after
     * @param kind      token, string, date, reference, uri, number — the typed
     *                  rule that turns a hit into what a search asks by
     * @param paths     the jsonpath selections whose items are the values
     * @param predicate a condition each hit must satisfy, or null
     */
    public record Parameter(String code, String base, String kind, List<String> paths,
            String predicate) {
    }

    /**
     * The search parameters a type is asked after by, as they were compiled
     * when they arrived.
     *
     * <p>Only the enforceable ones. A parameter the compiler refused by name
     * is one the database does not index either, so leaving it out here is
     * agreeing with the envelope that exists rather than losing a key.
     */
    public static List<Parameter> parametersFor(DataSource ds, Collection<String> types) {
        List<Parameter> out = new ArrayList<>();
        if (types.isEmpty()) {
            return out;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT code, base, kind, predicate,
                            ARRAY(SELECT jsonb_array_elements_text(paths))
                       FROM definitions.definition_parameter
                      WHERE base = ANY(?) AND unenforceable IS NULL
                      ORDER BY base, code""")) {
            ps.setArray(1, textArray(c, types));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Parameter(rs.getString(1), rs.getString(2), rs.getString(3),
                            codes(rs.getArray(5)), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "reading the compiled search parameters failed", e);
        }
        return out;
    }

    /**
     * The invariants, in one read rather than one per element.
     *
     * <p>{@code ele-1} is inherited onto nearly every element there is, so
     * there are as many invariant rows as elements and a fetch per element
     * would be the whole cost of building an index.
     */
    private static Map<String, List<DefinitionIndex.Invariant>> invariantsOf(
            Connection c, Collection<String> canonicals) throws SQLException {
        Map<String, List<DefinitionIndex.Invariant>> byElement = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT canonical, element_id, key, severity, expression, path
                  FROM definitions.definition_invariant
                 WHERE canonical = ANY(?)
                 ORDER BY canonical, element_id, key""")) {
            ps.setArray(1, textArray(c, canonicals));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byElement.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2),
                                    k -> new ArrayList<>())
                            .add(new DefinitionIndex.Invariant(rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return byElement;
    }

    /**
     * The predicate a slice is located by, out of the jsonpaths the row
     * carries, or null where the element claims every member.
     *
     * <p>A row's steps are jsonpaths relative to its parent instance: one
     * normally, several for a choice, one with a predicate for a slice. Only
     * the predicate is taken — where the member sits is something the walk
     * already knows from the path, and what it needs from the step is which
     * of the members there belong to this slice.
     */
    private static String predicateOf(Array steps) throws SQLException {
        if (steps == null) {
            return null;
        }
        for (Object step : (Object[]) steps.getArray()) {
            String one = step == null ? null : step.toString();
            int at = one == null ? -1 : one.indexOf('?');
            if (at >= 0) {
                return one.substring(at + 1).trim();
            }
        }
        return null;
    }

    private static List<String> codes(Array codes) throws SQLException {
        if (codes == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object code : (Object[]) codes.getArray()) {
            if (code != null) {
                out.add(code.toString());
            }
        }
        return out;
    }

    private static Array textArray(Connection c, Collection<String> of) throws SQLException {
        return c.createArrayOf("text", of.toArray(new String[0]));
    }
}
