package cloud.jengu.dbo.maintenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a tenant holds, counted where the counting is cheap
 * (jengu-platform#866).
 *
 * <p>The report an operator sees before a move, and the same numbers used to
 * verify one afterwards. It is deliberately <b>counts, not contents</b>: a
 * verification that read every object would be a second full copy of a
 * hospital's data, performed to check the first one.
 *
 * <p>Three numbers per type, and the third is the one people forget.
 * <b>Versions</b> size the move, because the whole history travels — for a
 * tenant with years of data it exceeds the object count by an order of
 * magnitude, and a report showing only objects understates what the operator
 * is about to start.
 *
 * <p><b>Identified</b> is how many objects carry a claim in their type's
 * authoritative system. Those are the ones a per-object verification can
 * match across a round trip, since ids do not survive one and codes do. The
 * rest are not a failure — a type may legitimately have no natural code — but
 * they are the objects nobody can check individually, so they are counted
 * rather than glossed over.
 */
public final class TenantInventory {

    private TenantInventory() {
    }

    /** One type's line: what is there, how much of it can be checked. */
    public record Line(String domain, String typeName, long total, long identified,
                       long versions) {}

    /** Counts every live type in every domain the tenant holds. */
    public static List<Line> of(DataSource ds) {
        List<Line> lines = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            for (String domain : TenantExport.domainsOf(c)) {
                lines.addAll(of(c, domain));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("inventory failed", e);
        }
        return lines;
    }

    private static List<Line> of(Connection c, String domain) throws SQLException {
        List<Line> lines = new ArrayList<>();
        // One pass per domain rather than one per type: the counts are a
        // grouped scan, and asking the database N times for what it can answer
        // once is how a report becomes the expensive part of a migration.
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.type,
                       count(*) AS total,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM state.%s_identifier i
                           WHERE i.object_id = d.id AND i.identity)) AS identified,
                       COALESCE((SELECT count(*) FROM history.%s_history h
                                 WHERE h.type = d.type), 0) AS versions
                FROM state.%s_data d
                WHERE NOT d.deleted
                GROUP BY d.type
                ORDER BY d.type""".formatted(domain, domain, domain));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lines.add(new Line(domain, rs.getString(1), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4)));
            }
        }
        return lines;
    }

    /** The inventory as the maintenance surface serves it. */
    public static String json(List<Line> lines) {
        StringBuilder out = new StringBuilder("{\"types\":[");
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            out.append(i > 0 ? "," : "")
                    .append("{\"domain\":").append(Names.quote(line.domain()))
                    .append(",\"name\":").append(Names.quote(line.typeName()))
                    .append(",\"total\":").append(line.total())
                    .append(",\"identified\":").append(line.identified())
                    .append(",\"versions\":").append(line.versions())
                    .append('}');
        }
        return out.append("]}").toString();
    }
}
