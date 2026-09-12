package cloud.jengu.dbo.definitions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The expanded definitions of one tenant: element per row, in its own
 * database beside the records the elements are about.
 *
 * <p>Rows rather than a document because the reader is the database
 * (REQ-DBO-VER-A-DEFINITION-IS-EXPANDED-WHEN-IT-ARRIVES). What is here is
 * derived — every row comes from a StructureDefinition the tenant holds, and
 * a reindex rebuilds the lot from those records — so this table is a
 * projection in the sense REQ-DBO-CORE-PAYLOAD-IS-TRUTH means it, and
 * nothing is authored here.
 *
 * <p><b>Per tenant, not per version.</b> A tenant's own profiles sit beside
 * the version's in one set, which is what a checker needs to walk, and a
 * tenant's archive is complete on its own. The version's share is some
 * thirteen thousand rows — a few megabytes against absolute isolation and no
 * cross-database reference in a store whose tenancy exists to avoid one.
 */
public final class DefinitionStore {

    /** One definition's expansion, as it goes in. */
    public record Expanded(
            String canonical,
            String version,
            String structureType,
            String kind,
            String base,
            String derivation,
            String sourceId,
            long sourceVersion,
            List<DefinitionElement> elements,
            List<DefinitionInvariant> invariants) {}

    private static final int BATCH = 1_000;

    /**
     * What shape these rows are in.
     *
     * <p>Bumped whenever a release changes what a row carries. These rows are
     * derived from records the tenant still holds, so the cheap and correct
     * answer to "the columns changed" is to drop them and let the face expand
     * the definitions again — migrating them would be preserving a copy
     * against the thing it was copied from.
     */
    /**
     * The shape the rows are taken apart into.
     *
     * <p>Public because an image carries it: rows expanded by one shape and
     * read by the checks of another are wrong in a way nothing reports, so
     * the number travels with the bytes and is compared before they load.
     */
    public static final int SHAPE = 3;

    private final DataSource ds;

    public DefinitionStore(DataSource dataSource) {
        this.ds = dataSource;
        ensureSchema();
    }

    // --------------------------------------------------------------- write

    /**
     * Replace what is held for these definitions, as one unit.
     *
     * <p>Replace rather than merge: a definition that lost an element must
     * lose the row, and a checker reading a row for an element the current
     * definition does not have would enforce a rule nobody wrote.
     *
     * <p>One transaction for the batch, for the reason the terminology import
     * has one: a version arrives as seven hundred definitions at once, and a
     * transaction each is round trips rather than work. Batched statements
     * rather than a COPY stream, because a row here carries an array and two
     * jsonb columns, and getting those through CSV quoting correctly is a
     * cost with no measurement behind it — thirteen thousand rows go in once.
     */
    public void replaceAll(Collection<Expanded> definitions) {
        if (definitions.isEmpty()) {
            return;
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                forget(c, definitions.stream().map(Expanded::canonical).toList());
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO definitions.definition_element (
                          canonical, element_id, definition_version, structure_type, kind,
                          base_definition, derivation,
                          source_id, source_version, ordinal, path, parent_id, steps,
                          min_occurs, max_occurs, types, fixed, pattern,
                          binding_strength, binding_valueset, unenforceable)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?)""")) {
                    int pending = 0;
                    for (Expanded definition : definitions) {
                        int ordinal = 0;
                        for (DefinitionElement element : definition.elements()) {
                            bind(c, ps, definition, element, ordinal++);
                            ps.addBatch();
                            if (++pending == BATCH) {
                                ps.executeBatch();
                                pending = 0;
                            }
                        }
                    }
                    if (pending > 0) {
                        ps.executeBatch();
                    }
                }
                writeInvariants(c, definitions);
                c.commit();
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "expanding " + definitions.size() + " definitions failed", e);
        }
    }

    private void bind(Connection c, PreparedStatement ps, Expanded definition,
            DefinitionElement element, int ordinal) throws SQLException {
        ps.setString(1, definition.canonical());
        ps.setString(2, element.id());
        ps.setString(3, definition.version());
        ps.setString(4, definition.structureType());
        ps.setString(5, definition.kind());
        ps.setString(6, definition.base());
        ps.setString(7, definition.derivation());
        ps.setString(8, definition.sourceId());
        ps.setLong(9, definition.sourceVersion());
        ps.setInt(10, ordinal);
        ps.setString(11, element.path());
        ps.setString(12, element.parentId());
        ps.setArray(13, c.createArrayOf("text", element.steps().toArray()));
        ps.setInt(14, element.min());
        if (element.max() == null) {
            ps.setNull(15, java.sql.Types.INTEGER);
        } else {
            ps.setInt(15, element.max());
        }
        ps.setString(16, Json.types(element.types()));
        ps.setString(17, element.fixedJson());
        ps.setString(18, element.patternJson());
        ps.setString(19, element.bindingStrength());
        ps.setString(20, element.bindingValueSet());
        ps.setString(21, element.unenforceable());
    }

    private void writeInvariants(Connection c, Collection<Expanded> definitions)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO definitions.definition_invariant (
                  canonical, element_id, key, severity, expression, path, unenforceable)
                VALUES (?,?,?,?,?,?,?)""")) {
            int pending = 0;
            for (Expanded definition : definitions) {
                for (DefinitionInvariant rule : definition.invariants()) {
                    ps.setString(1, definition.canonical());
                    ps.setString(2, rule.elementId());
                    ps.setString(3, rule.key());
                    ps.setString(4, rule.severity());
                    ps.setString(5, rule.expression());
                    ps.setString(6, rule.path());
                    ps.setString(7, rule.unenforceable());
                    ps.addBatch();
                    if (++pending == BATCH) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    /** One definition's rules, in the order the definition states them. */
    public List<DefinitionInvariant> invariantsOf(String canonical) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT element_id, key, severity, expression, path, unenforceable
                       FROM definitions.definition_invariant WHERE canonical = ?
                      ORDER BY element_id, key""")) {
            ps.setString(1, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                List<DefinitionInvariant> rules = new ArrayList<>();
                while (rs.next()) {
                    rules.add(new DefinitionInvariant(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
                }
                return List.copyOf(rules);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the rules of " + canonical + " failed", e);
        }
    }

    /** Drop what is held for these definitions — a withdrawal, or a reindex. */
    public void forget(Collection<String> canonicals) {
        if (canonicals.isEmpty()) {
            return;
        }
        try (Connection c = ds.getConnection()) {
            forget(c, canonicals);
        } catch (SQLException e) {
            throw new IllegalStateException("forgetting expanded definitions failed", e);
        }
    }

    private void forget(Connection c, Collection<String> canonicals) throws SQLException {
        for (String table : List.of("definitions.definition_element", "definitions.definition_invariant")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + table + " WHERE canonical = ANY (?)")) {
                ps.setArray(1, c.createArrayOf("text", canonicals.toArray()));
                ps.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------------- read

    /**
     * Which record each held definition was expanded from.
     *
     * <p>What makes expansion happen once: the face compares this with what
     * the tenant holds and expands what moved, so a bring-up that changed
     * nothing writes nothing.
     */
    public Map<String, Long> expandedFrom() {
        Map<String, Long> held = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT canonical, max(source_version) FROM definitions.definition_element"
                     + " GROUP BY canonical");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                held.put(rs.getString(1), rs.getLong(2));
            }
            return held;
        } catch (SQLException e) {
            throw new IllegalStateException("reading what is expanded failed", e);
        }
    }

    /** One definition's elements, in the order the definition states them. */
    public List<DefinitionElement> elementsOf(String canonical) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT element_id, path, parent_id, steps, min_occurs, max_occurs,
                            types::text, fixed::text, pattern::text,
                            binding_strength, binding_valueset, unenforceable
                       FROM definitions.definition_element
                      WHERE canonical = ? ORDER BY ordinal""")) {
            ps.setString(1, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                List<DefinitionElement> elements = new ArrayList<>();
                while (rs.next()) {
                    elements.add(read(rs));
                }
                return List.copyOf(elements);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the elements of " + canonical + " failed", e);
        }
    }

    private static DefinitionElement read(ResultSet rs) throws SQLException {
        Object[] steps = (Object[]) rs.getArray(4).getArray();
        int max = rs.getInt(6);
        return new DefinitionElement(rs.getString(1), rs.getString(2), rs.getString(3),
                java.util.Arrays.stream(steps).map(String::valueOf).toList(),
                rs.getInt(5), rs.wasNull() ? null : max,
                Json.readTypes(rs.getString(7)),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12));
    }

    /**
     * The definition a resource of this type is validated against when it
     * claims no profile: the one that IS the type rather than a narrowing of
     * it.
     *
     * <p>Read from what the definitions say about themselves — a
     * specialization introduces a type, a constraint profiles one — rather
     * than from the shape of a url, because a canonical is a name and names
     * are a face's to choose. Two of them claiming one type is a fact about
     * what this tenant holds, and picking one would be choosing by accident
     * which rules apply.
     */
    public java.util.Optional<String> theTypeItself(String structureType) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT DISTINCT canonical FROM definitions.definition_element
                      WHERE structure_type = ? AND derivation = 'specialization'
                        AND parent_id IS NULL""")) {
            ps.setString(1, structureType);
            try (ResultSet rs = ps.executeQuery()) {
                String only = rs.next() ? rs.getString(1) : null;
                return rs.next() ? java.util.Optional.empty() : java.util.Optional.ofNullable(only);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the definition of " + structureType
                    + " failed", e);
        }
    }

    /**
     * What this release's functions make of a document under one definition,
     * as a count of findings — or nothing at all where the definition is not
     * held here, which is different from finding nothing.
     *
     * <p>A count rather than the findings: what is being measured is whether
     * the two checkers agree, and the findings themselves are about a
     * document, which in this store is a person.
     */
    public java.util.OptionalLong issuesUnder(byte[] document, String canonical) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement held = c.prepareStatement(
                    "SELECT 1 FROM definitions.definition_element WHERE canonical = ? LIMIT 1")) {
                held.setString(1, canonical);
                try (ResultSet rs = held.executeQuery()) {
                    if (!rs.next()) {
                        return java.util.OptionalLong.empty();
                    }
                }
            }
            // Errors only. A warning is advice and refuses nothing, and
            // counting it beside a toolchain verdict that means refusal
            // would manufacture a disagreement out of two things that never
            // disagreed.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM dbo.validate(?::jsonb, ?) WHERE severity = 'error'")) {
                ps.setString(1, new String(document, java.nio.charset.StandardCharsets.UTF_8));
                ps.setString(2, canonical);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? java.util.OptionalLong.of(rs.getLong(1))
                            : java.util.OptionalLong.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about a " + canonical
                    + " failed", e);
        }
    }

    /** How many elements are held, over every definition. */
    public long count() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM definitions.definition_element");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("counting expanded elements failed", e);
        }
    }

    // -------------------------------------------------------------- schema

    private void ensureSchema() {
        try (Connection c = ds.getConnection()) {
            // Prepared, though nothing here takes a value: the discipline is
            // that this store never holds a java.sql.Statement at all
            // (REQ-DBO-CORE-PARAMETERIZED-SQL), and a ratchet reads the
            // bytecode rather than the intent.
            for (String ddl : List.of(
                    "CREATE SCHEMA IF NOT EXISTS state",
                    "CREATE SCHEMA IF NOT EXISTS definitions",
                    """
                    CREATE TABLE IF NOT EXISTS definitions.definition_element (
                      canonical          text    NOT NULL,
                      element_id         text    NOT NULL,
                      definition_version text,
                      structure_type     text,
                      kind               text,
                      base_definition    text,
                      derivation         text,
                      source_id          text,
                      source_version     bigint  NOT NULL DEFAULT 0,
                      ordinal            int     NOT NULL,
                      path               text    NOT NULL,
                      parent_id          text,
                      steps              text[]  NOT NULL,
                      min_occurs         int     NOT NULL,
                      max_occurs         int,
                      types              jsonb   NOT NULL,
                      fixed              jsonb,
                      pattern            jsonb,
                      binding_strength   text,
                      binding_valueset   text,
                      unenforceable      text,
                      PRIMARY KEY (canonical, element_id)
                    )""",
                    // The two ways a checker reaches these rows: everything
                    // of one definition, and the children of one element.
                    "CREATE INDEX IF NOT EXISTS definition_element_by_parent"
                            + " ON definitions.definition_element (canonical, parent_id)",
                    """
                    CREATE TABLE IF NOT EXISTS definitions.definition_invariant (
                      canonical     text NOT NULL,
                      element_id    text NOT NULL,
                      key           text NOT NULL,
                      severity      text,
                      expression    text,
                      path          text,
                      unenforceable text,
                      PRIMARY KEY (canonical, element_id, key)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS definitions.definition_shape (
                      only_row int PRIMARY KEY DEFAULT 1 CHECK (only_row = 1),
                      shape    int NOT NULL
                    )""")) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
            }
            resetIfTheShapeMoved(c);
        } catch (SQLException e) {
            throw new IllegalStateException("the definition schema setup failed", e);
        }
    }

    /**
     * Rows from a release that carried different columns are dropped, not
     * migrated.
     *
     * <p>They are a projection of records this tenant still holds, so the
     * face expands them again on the pass that finds them missing. The
     * columns are added first because a table that exists is never recreated,
     * and then emptied because what is in them was written by a release that
     * did not have them.
     */
    private void resetIfTheShapeMoved(Connection c) throws SQLException {
        Integer held = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT shape FROM definitions.definition_shape WHERE only_row = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                held = rs.getInt(1);
            }
        }
        if (Integer.valueOf(SHAPE).equals(held)) {
            return;
        }
        for (String column : List.of("base_definition", "derivation")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "ALTER TABLE definitions.definition_element ADD COLUMN IF NOT EXISTS "
                    + column + " text")) {
                ps.execute();
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "TRUNCATE TABLE definitions.definition_element, definitions.definition_invariant")) {
            ps.execute();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO definitions.definition_shape (only_row, shape) VALUES (1, ?)
                ON CONFLICT (only_row) DO UPDATE SET shape = EXCLUDED.shape""")) {
            ps.setInt(1, SHAPE);
            ps.executeUpdate();
        }
    }
}
