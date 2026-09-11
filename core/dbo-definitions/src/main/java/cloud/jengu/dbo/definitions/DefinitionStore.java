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
            String sourceId,
            long sourceVersion,
            List<DefinitionElement> elements) {}

    private static final int BATCH = 1_000;

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
                        INSERT INTO state.definition_element (
                          canonical, element_id, definition_version, structure_type, kind,
                          source_id, source_version, ordinal, path, parent_id, steps,
                          min_occurs, max_occurs, types, fixed, pattern,
                          binding_strength, binding_valueset, unenforceable)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?)""")) {
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
        ps.setString(6, definition.sourceId());
        ps.setLong(7, definition.sourceVersion());
        ps.setInt(8, ordinal);
        ps.setString(9, element.path());
        ps.setString(10, element.parentId());
        ps.setArray(11, c.createArrayOf("text", element.steps().toArray()));
        ps.setInt(12, element.min());
        if (element.max() == null) {
            ps.setNull(13, java.sql.Types.INTEGER);
        } else {
            ps.setInt(13, element.max());
        }
        ps.setString(14, Json.types(element.types()));
        ps.setString(15, element.fixedJson());
        ps.setString(16, element.patternJson());
        ps.setString(17, element.bindingStrength());
        ps.setString(18, element.bindingValueSet());
        ps.setString(19, element.unenforceable());
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
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM state.definition_element WHERE canonical = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("text", canonicals.toArray()));
            ps.executeUpdate();
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
                     "SELECT canonical, max(source_version) FROM state.definition_element"
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
                       FROM state.definition_element
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

    /** How many elements are held, over every definition. */
    public long count() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state.definition_element");
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
                    """
                    CREATE TABLE IF NOT EXISTS state.definition_element (
                      canonical          text    NOT NULL,
                      element_id         text    NOT NULL,
                      definition_version text,
                      structure_type     text,
                      kind               text,
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
                            + " ON state.definition_element (canonical, parent_id)")) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the definition schema setup failed", e);
        }
    }
}
