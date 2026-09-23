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
                              FROM jsonb_array_elements(types) WITH ORDINALITY AS a(t, n))
                      FROM definitions.definition_element
                     WHERE canonical = ANY(?)
                     ORDER BY canonical, ordinal""")) {
                ps.setArray(1, textArray(c, canonicals));
                try (ResultSet rs = ps.executeQuery()) {
                    String open = null;
                    while (rs.next()) {
                        String canonical = rs.getString(1);
                        if (!canonical.equals(open)) {
                            index.structure(canonical);
                            open = canonical;
                        }
                        int max = rs.getInt(5);
                        if (rs.wasNull()) {
                            max = DefinitionIndex.UNBOUNDED;
                        }
                        index.element(rs.getString(3), rs.getInt(4), max,
                                codes(rs.getArray(10)), rs.getString(6), rs.getString(7),
                                rs.getString(8), rs.getString(9));
                        for (DefinitionIndex.Invariant rule
                                : invariants.getOrDefault(canonical + "|" + rs.getString(2),
                                        List.of())) {
                            index.invariant(rule.key(), rule.severity(), rule.expression());
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
                SELECT canonical, element_id, key, severity, expression
                  FROM definitions.definition_invariant
                 WHERE canonical = ANY(?)
                 ORDER BY canonical, element_id, key""")) {
            ps.setArray(1, textArray(c, canonicals));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byElement.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2),
                                    k -> new ArrayList<>())
                            .add(new DefinitionIndex.Invariant(
                                    rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        }
        return byElement;
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
