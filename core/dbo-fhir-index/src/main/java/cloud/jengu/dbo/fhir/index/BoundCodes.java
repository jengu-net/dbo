package cloud.jengu.dbo.fhir.index;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The codes behind the required bindings an index carries.
 *
 * <p>Definitions say WHICH value set binds an element and never what is in
 * one, so an answerer that decides a required binding in its own process
 * holds codes — which are a different kind of content, and the reason this is
 * beside the index rather than in it.
 *
 * <p><b>It is small, and that was measured before it was built.</b> Over a
 * face root's closure: 58 elements carry a required binding, naming 43
 * distinct value sets, every one of them a whole code system with no filter
 * and no nesting, and 598 codes behind the lot. A few hundred strings beside
 * an index of a few hundred kilobytes — not an expansion engine.
 *
 * <p><b>What it cannot judge, it says it cannot.</b> The answer is a
 * {@code Boolean} and null is a real answer: the value set is not held, or
 * none of the systems it is built from are, or it is larger than this was
 * willing to hold. Unresolvable is not invalid, and a store that refused what
 * it merely does not hold would refuse a tenant's own vocabulary. That is the
 * same rule {@code dbo.in_value_set} follows, deliberately, because the two
 * are compared.
 *
 * <p><b>No JSON parser</b>, and no driver beyond {@code java.sql}: a compose
 * is taken apart by the database on the way out, which is where the JSON
 * already is.
 */
public final class BoundCodes {

    /**
     * How many codes one value set may bring before it is left unjudged.
     *
     * <p>The measured case is 598 across all of them, so nothing here is near
     * it. What the cap is for is the case that is not measured — a binding to
     * a value set built over a whole clinical terminology — where holding it
     * would cost more than the index this sits beside. Declining is the
     * honest answer and is counted, so a deployment can see it happened
     * rather than wonder why nothing was ever refused.
     */
    public static final int DEFAULT_CAP = 20_000;

    private record Include(String system, Set<String> codes, String isA) {
    }

    private final Map<String, List<Include>> includes;
    private final Map<String, List<Include>> excludes;
    private final Map<String, Set<String>> bySystem;
    private final Set<String> declined;
    private final int codes;

    private BoundCodes(Map<String, List<Include>> includes, Map<String, List<Include>> excludes,
            Map<String, Set<String>> bySystem, Set<String> declined, int codes) {
        this.includes = includes;
        this.excludes = excludes;
        this.bySystem = bySystem;
        this.declined = declined;
        this.codes = codes;
    }

    /** How many value sets are answerable. */
    public int valueSets() {
        return includes.size();
    }

    /** How many codes are held. */
    public int codes() {
        return codes;
    }

    /** The value sets that were too large to hold, by name. */
    public Set<String> declined() {
        return Set.copyOf(declined);
    }

    /**
     * Whether this code is in that value set: true, false, or null where
     * nothing here can say.
     */
    public Boolean contains(String valueSet, String system, String code) {
        String url = withoutVersion(valueSet);
        List<Include> included = includes.get(url);
        if (included == null) {
            return null;
        }
        for (Include out : excludes.getOrDefault(url, List.of())) {
            if ((system == null || system.equals(out.system())) && bySystem.containsKey(out.system())
                    && (out.codes() == null || out.codes().contains(code))) {
                return false;
            }
        }
        boolean named = false;
        boolean judged = false;
        for (Include in : included) {
            if (system != null && !system.equals(in.system())) {
                continue;
            }
            named = true;
            Set<String> held = bySystem.get(in.system());
            if (held == null) {
                continue;
            }
            if (in.isA() != null) {
                // A hierarchy this does not hold. Naming the system is not
                // enough to judge an is-a, so it stays unjudged rather than
                // guessing at a subsumption.
                continue;
            }
            judged = true;
            if (in.codes() != null ? in.codes().contains(code) : held.contains(code)) {
                return true;
            }
        }
        // A code from a system the value set is not built from is not in it,
        // and that is knowable without holding anything.
        if (system != null && !named) {
            return false;
        }
        return judged ? Boolean.FALSE : null;
    }

    /** Everything the required bindings in this index name. */
    public static BoundCodes over(DataSource ds, DefinitionIndex index) {
        return over(ds, index, DEFAULT_CAP);
    }

    /** Everything the required bindings in this index name, up to a cap per value set. */
    public static BoundCodes over(DataSource ds, DefinitionIndex index, int cap) {
        Set<String> wanted = new LinkedHashSet<>();
        for (int element = 0; element < index.elements(); element++) {
            if (index.bindingStrengthOf(element) == DefinitionIndex.REQUIRED
                    && index.bindingValueSetOf(element) != null) {
                wanted.add(withoutVersion(index.bindingValueSetOf(element)));
            }
        }
        Map<String, List<Include>> includes = new HashMap<>();
        Map<String, List<Include>> excludes = new HashMap<>();
        Map<String, Set<String>> bySystem = new HashMap<>();
        Set<String> declined = new LinkedHashSet<>();
        if (wanted.isEmpty()) {
            return new BoundCodes(includes, excludes, bySystem, declined, 0);
        }
        try (Connection c = ds.getConnection()) {
            read(c, wanted, "includes", includes);
            read(c, wanted, "excludes", excludes);
            Set<String> systems = new LinkedHashSet<>();
            for (List<Include> sets : includes.values()) {
                for (Include one : sets) {
                    if (one.system() != null && one.codes() == null) {
                        systems.add(one.system());
                    }
                }
            }
            int held = load(c, systems, bySystem, cap);
            // A value set whose system was declined is not answerable, and
            // says so by name rather than by answering false.
            for (Map.Entry<String, List<Include>> entry : includes.entrySet()) {
                for (Include one : entry.getValue()) {
                    if (one.codes() == null && one.system() != null
                            && !bySystem.containsKey(one.system()) && systems.contains(one.system())) {
                        declined.add(entry.getKey());
                    }
                }
            }
            return new BoundCodes(includes, excludes, bySystem, declined, held);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "reading the codes behind " + wanted.size() + " required bindings failed", e);
        }
    }

    private static void read(Connection c, Set<String> wanted, String half,
            Map<String, List<Include>> into) throws SQLException {
        // The compose is taken apart by the database, which is where the JSON
        // already is — this module carries no parser and is not going to.
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT vs.url, part ->> 'system', part ->> 'isA',
                       -- jsonb_exists rather than the ? operator: the driver
                       -- reads a ? in the SQL as a parameter placeholder and
                       -- refuses the statement for a missing value.
                       CASE WHEN jsonb_exists(part, 'codes')
                            THEN ARRAY(SELECT jsonb_array_elements_text(part -> 'codes'))
                            END
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> '%s', '[]'::jsonb)) AS part
                 WHERE vs.url = ANY(?)""".formatted(half))) {
            ps.setArray(1, c.createArrayOf("text", wanted.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Array codes = rs.getArray(4);
                    Set<String> written = null;
                    if (codes != null) {
                        written = new HashSet<>();
                        for (Object one : (Object[]) codes.getArray()) {
                            written.add(String.valueOf(one));
                        }
                    }
                    into.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new Include(rs.getString(2), written, rs.getString(3)));
                }
            }
        }
        // A value set with no half of this kind still has to be KNOWN, or a
        // binding to it reads as content the tenant does not hold.
        if ("includes".equals(half)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT url FROM definitions.term_valueset WHERE url = ANY(?)")) {
                ps.setArray(1, c.createArrayOf("text", wanted.toArray(new String[0])));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        into.computeIfAbsent(rs.getString(1), k -> new ArrayList<>());
                    }
                }
            }
        }
    }

    private static int load(Connection c, Set<String> systems, Map<String, Set<String>> into,
            int cap) throws SQLException {
        int held = 0;
        for (String system : systems) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM definitions.term_concept WHERE system = ?")) {
                ps.setString(1, system);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > cap) {
                        continue;
                    }
                }
            }
            Set<String> codes = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code FROM definitions.term_concept WHERE system = ?")) {
                ps.setString(1, system);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        codes.add(rs.getString(1));
                    }
                }
            }
            into.put(system, codes);
            held += codes.size();
        }
        return held;
    }

    /**
     * A binding names a canonical WITH its version and the terminology is
     * keyed without one.
     *
     * <p>Asked as written, every required binding a version states reads as
     * content the tenant does not hold — which is a confident wrong answer
     * rather than a missing one, and is how this was nearly recorded as a
     * measurement.
     */
    private static String withoutVersion(String canonical) {
        int bar = canonical.indexOf('|');
        return bar < 0 ? canonical : canonical.substring(0, bar);
    }
}
