package cloud.jengu.dbo.terminology;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The normalized terminology store: concepts are rows, authoritative
 * (REQ-DBO-TERM-NATIVE-FORM); ingest is a streaming Postgres COPY — no
 * chunking, no bound-parameter ceiling (REQ-DBO-TERM-BULK-LOAD); operations
 * are served from the rows (REQ-DBO-TERM-OPERATIONS-FROM-NATIVE-FORM).
 */
public final class TerminologyStore {

    private final DataSource ds;

    public TerminologyStore(DataSource dataSource) {
        this.ds = dataSource;
        ensureSchema();
    }

    // --------------------------------------------------------------- ingest

    /** One system and the concepts it brings, for a bulk import. */
    public record System(String url, String version, List<Concept> concepts) {}

    /**
     * Replace-all import of MANY systems as one unit.
     *
     * <p>Same result as calling {@link #importSystem} for each, and a very
     * different cost. A baseline package carries some nine hundred code
     * systems and twenty thousand concepts, and per-system it was nine hundred
     * transactions to move them — measured at 2704ms of writing against 28ms
     * of parsing, which is round-trip overhead rather than work. Here it is
     * one transaction and one COPY stream: the system is a column in the row,
     * so the rows of every system travel together.
     *
     * @return how many concepts landed
     */
    public long importSystems(List<System> systems) {
        if (systems.isEmpty()) {
            return 0;
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM state.term_concept WHERE system = ANY (?)")) {
                    ps.setArray(1, c.createArrayOf("text",
                            systems.stream().map(System::url).toArray()));
                    ps.executeUpdate();
                }
                CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
                long rows = copy.copyIn(
                        "COPY state.term_concept (system, code, display, parent_code,"
                                + " designations, properties) FROM STDIN WITH (FORMAT csv)",
                        new SystemsCsvReader(systems));
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO state.term_system (url, version, concept_count, updated_at)
                        VALUES (?, ?, ?, now())
                        ON CONFLICT (url) DO UPDATE SET version = EXCLUDED.version,
                          concept_count = EXCLUDED.concept_count, updated_at = now()""")) {
                    for (System system : systems) {
                        ps.setString(1, system.url());
                        ps.setString(2, system.version());
                        ps.setLong(3, system.concepts().size());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
                return rows;
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException(
                    "terminology import failed for " + systems.size() + " systems", e);
        }
    }

    /** Replace-all import of one system's concepts, streamed through COPY in one transaction. */
    public long importSystem(String systemUrl, String version, Iterator<Concept> concepts) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM state.term_concept WHERE system = ?")) {
                    ps.setString(1, systemUrl);
                    ps.executeUpdate();
                }
                CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
                long rows = copy.copyIn(
                        "COPY state.term_concept (system, code, display, parent_code, designations, properties)"
                                + " FROM STDIN WITH (FORMAT csv)",
                        new ConceptCsvReader(systemUrl, concepts));
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO state.term_system (url, version, concept_count, updated_at)
                        VALUES (?, ?, ?, now())
                        ON CONFLICT (url) DO UPDATE SET version = EXCLUDED.version,
                          concept_count = EXCLUDED.concept_count, updated_at = now()""")) {
                    ps.setString(1, systemUrl);
                    ps.setString(2, version);
                    ps.setLong(3, rows);
                    ps.executeUpdate();
                }
                c.commit();
                return rows;
            } catch (Throwable t) {
                c.rollback();
                throw t;
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("terminology import failed for " + systemUrl, e);
        }
    }

    public void putValueSet(String url, String version, Compose compose) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.term_valueset (url, version, compose, updated_at)
                     VALUES (?, ?, ?::jsonb, now())
                     ON CONFLICT (url) DO UPDATE SET version = EXCLUDED.version,
                       compose = EXCLUDED.compose, updated_at = now()""")) {
            ps.setString(1, url);
            ps.setString(2, version);
            ps.setString(3, composeJson(compose));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("valueset registration failed", e);
        }
    }

    // ------------------------------------------------------------- queries

    public Optional<Concept> lookup(String system, String code) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT code, display, parent_code, designations::text, properties::text
                     FROM state.term_concept WHERE system = ? AND code = ?""")) {
            ps.setString(1, system);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readConcept(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("lookup failed", e);
        }
    }

    public boolean validateCode(String system, String code) {
        return lookup(system, code).isPresent();
    }

    /**
     * Whether this store holds the system at all, and at which version — the
     * question that separates "not a code of X" from "X is not here", which
     * are different facts with different fixes. Answered from the
     * system registry rather than by counting concepts, so an imported-empty
     * system is still a held system.
     */
    public Optional<String> systemVersion(String url) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT coalesce(version, '') FROM state.term_system WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("system lookup failed", e);
        }
    }

    public long conceptCount(String system) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM state.term_concept WHERE system = ?")) {
            ps.setString(1, system);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("count failed", e);
        }
    }

    public Optional<Compose> valueSetCompose(String url) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT compose::text FROM state.term_valueset WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(parseCompose(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("valueset read failed", e);
        }
    }

    public record Expansion(long total, List<ExpandedConcept> contains) {}

    public record ExpandedConcept(String system, String code, String display) {}

    /**
     * Expansion from compose rules. Offset paging is CORRECT here: an
     * expansion is a deterministic set, not a live query (§10's keyset rule
     * governs searches, not set materializations). {@code filter} is a
     * case-insensitive prefix on code or display.
     */
    public Expansion expand(Compose compose, String filter, int offset, int count) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT c.system, c.code, c.display FROM state.term_concept c WHERE (");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Compose.Include inc : compose.includes()) {
            if (!first) {
                sql.append(" OR ");
            }
            first = false;
            if (inc.isA() != null) {
                sql.append("""
                        (c.system = ? AND c.code IN (
                          WITH RECURSIVE sub AS (
                            SELECT code FROM state.term_concept WHERE system = ? AND code = ?
                            UNION ALL
                            SELECT ch.code FROM state.term_concept ch JOIN sub s
                              ON ch.parent_code = s.code AND ch.system = ?
                          ) SELECT code FROM sub))""");
                params.add(inc.system());
                params.add(inc.system());
                params.add(inc.isA());
                params.add(inc.system());
            } else if (!inc.codes().isEmpty()) {
                sql.append("(c.system = ? AND c.code = ANY (?))");
                params.add(inc.system());
                params.add(inc.codes().toArray(new String[0]));
            } else {
                sql.append("(c.system = ?)");
                params.add(inc.system());
            }
        }
        sql.append(')');
        for (Compose.Exclude ex : compose.excludes()) {
            sql.append(" AND NOT (c.system = ? AND c.code = ANY (?))");
            params.add(ex.system());
            params.add(ex.codes().toArray(new String[0]));
        }
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND (lower(c.code) LIKE ? OR lower(c.display) LIKE ?)");
            String prefix = filter.toLowerCase()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(prefix);
            params.add(prefix);
        }

        try (Connection c = ds.getConnection()) {
            long total;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM (" + sql + ") x")) {
                bind(c, ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<ExpandedConcept> contains = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    sql + " ORDER BY c.system, c.code OFFSET ? LIMIT ?")) {
                int n = bind(c, ps, params);
                ps.setInt(n++, offset);
                ps.setInt(n, count);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        contains.add(new ExpandedConcept(
                                rs.getString(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            return new Expansion(total, contains);
        } catch (SQLException e) {
            throw new IllegalStateException("expand failed", e);
        }
    }

    /** All concepts of a system, hierarchy fields included (resource reassembly). */
    public List<Concept> allConcepts(String system) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT code, display, parent_code, designations::text, properties::text
                     FROM state.term_concept WHERE system = ? ORDER BY code""")) {
            ps.setString(1, system);
            try (ResultSet rs = ps.executeQuery()) {
                List<Concept> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readConcept(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read failed", e);
        }
    }

    // ------------------------------------------------------------ plumbing

    private int bind(Connection c, PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            if (p instanceof String[] arr) {
                ps.setArray(i++, c.createArrayOf("text", arr));
            } else {
                ps.setObject(i++, p);
            }
        }
        return i;
    }

    private Concept readConcept(ResultSet rs) throws SQLException {
        return new Concept(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                Json.parseFlatMap(rs.getString(4)),
                Json.parseFlatMap(rs.getString(5)));
    }

    private String composeJson(Compose compose) {
        StringBuilder sb = new StringBuilder("{\"includes\":[");
        boolean first = true;
        for (Compose.Include inc : compose.includes()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"system\":").append(Json.quote(inc.system()));
            if (inc.isA() != null) {
                sb.append(",\"isA\":").append(Json.quote(inc.isA()));
            }
            if (!inc.codes().isEmpty()) {
                sb.append(",\"codes\":").append(Json.stringArray(inc.codes()));
            }
            sb.append('}');
        }
        sb.append("],\"excludes\":[");
        first = true;
        for (Compose.Exclude ex : compose.excludes()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"system\":").append(Json.quote(ex.system()))
              .append(",\"codes\":").append(Json.stringArray(ex.codes())).append('}');
        }
        return sb.append("]}").toString();
    }

    private Compose parseCompose(String json) {
        List<Compose.Include> includes = new ArrayList<>();
        for (Map<String, Object> inc : Json.parseObjectArray(json, "includes")) {
            includes.add(new Compose.Include(
                    (String) inc.get("system"),
                    Json.stringList(inc.get("codes")),
                    (String) inc.get("isA")));
        }
        List<Compose.Exclude> excludes = new ArrayList<>();
        for (Map<String, Object> ex : Json.parseObjectArray(json, "excludes")) {
            excludes.add(new Compose.Exclude(
                    (String) ex.get("system"),
                    Json.stringList(ex.get("codes"))));
        }
        return new Compose(includes, excludes);
    }

    private void ensureSchema() {
        try (Connection c = ds.getConnection()) {
            for (String ddl : List.of(
                    "CREATE SCHEMA IF NOT EXISTS state",
                    """
                    CREATE TABLE IF NOT EXISTS state.term_concept (
                      system text NOT NULL,
                      code text NOT NULL,
                      display text,
                      parent_code text,
                      designations jsonb,
                      properties jsonb,
                      PRIMARY KEY (system, code)
                    )""",
                    "CREATE INDEX IF NOT EXISTS term_concept_parent_ix ON state.term_concept (system, parent_code)",
                    "CREATE INDEX IF NOT EXISTS term_concept_display_ix ON state.term_concept (system, lower(display) text_pattern_ops)",
                    """
                    CREATE TABLE IF NOT EXISTS state.term_system (
                      url text PRIMARY KEY,
                      version text,
                      concept_count bigint NOT NULL,
                      updated_at timestamptz NOT NULL DEFAULT now()
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS state.term_valueset (
                      url text PRIMARY KEY,
                      version text,
                      compose jsonb NOT NULL,
                      updated_at timestamptz NOT NULL DEFAULT now()
                    )""")) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("terminology schema setup failed", e);
        }
    }

    /** Streams concepts as CSV rows for COPY without materializing the set. */
    /** Every system's rows, one stream — the system rides in the row. */
    private static final class SystemsCsvReader extends Reader {
        private final Iterator<System> systems;
        private ConceptCsvReader current;

        SystemsCsvReader(List<System> systems) {
            this.systems = systems.iterator();
        }

        @Override
        public int read(char[] buf, int off, int len) {
            while (true) {
                if (current != null) {
                    int n = current.read(buf, off, len);
                    if (n >= 0) {
                        return n;
                    }
                }
                if (!systems.hasNext()) {
                    return -1;
                }
                System next = systems.next();
                current = new ConceptCsvReader(next.url(), next.concepts().iterator());
            }
        }

        @Override
        public void close() {}
    }

    private static final class ConceptCsvReader extends Reader {
        private final String system;
        private final Iterator<Concept> concepts;
        private String pending = "";
        private int pos;

        ConceptCsvReader(String system, Iterator<Concept> concepts) {
            this.system = system;
            this.concepts = concepts;
        }

        @Override
        public int read(char[] buf, int off, int len) {
            if (pos >= pending.length()) {
                if (!concepts.hasNext()) {
                    return -1;
                }
                Concept concept = concepts.next();
                pending = csvRow(concept);
                pos = 0;
            }
            int n = Math.min(len, pending.length() - pos);
            pending.getChars(pos, pos + n, buf, off);
            pos += n;
            return n;
        }

        private String csvRow(Concept concept) {
            return String.join(",",
                    Json.csv(system),
                    Json.csv(concept.code()),
                    Json.csv(concept.display()),
                    Json.csv(concept.parentCode()),
                    Json.csv(Json.flatMapJson(concept.designations())),
                    Json.csv(Json.flatMapJson(concept.properties()))) + "\n";
        }

        @Override
        public void close() {}
    }
}
