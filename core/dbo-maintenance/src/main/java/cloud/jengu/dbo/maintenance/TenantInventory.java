package cloud.jengu.dbo.maintenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a tenant holds, counted where the counting is cheap.
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

    /**
     * One shape-stock line (REQ-DBO-SHAPE-STOCK-COUNTED): how many live
     * objects of this type are stamped with this profile at this version —
     * or, when {@code version} is null, DECLARE the profile and carry no
     * stamp at all, which is the number a migration actually cares about.
     * The same report runs before and after one and diffs line by line.
     */
    public record ShapeLine(String domain, String typeName, String profile, String version,
                            long count) {}

    /** Counts the shape stock in every domain the tenant holds. */
    public static List<ShapeLine> shapes(DataSource ds) {
        List<ShapeLine> lines = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            for (String domain : TenantExport.domainsOf(c)) {
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT d.type, s.entry, count(*)
                        FROM state.%s_data d, jsonb_array_elements_text(d.shape) s(entry)
                        WHERE NOT d.deleted GROUP BY 1, 2 ORDER BY 1, 2""".formatted(domain));
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String entry = rs.getString(2);
                        int bar = entry.lastIndexOf('|');
                        lines.add(new ShapeLine(domain, rs.getString(1),
                                bar > 0 ? entry.substring(0, bar) : entry,
                                bar > 0 ? entry.substring(bar + 1) : "",
                                rs.getLong(3)));
                    }
                }
                // Declared-but-unstamped: the profile claim is in the
                // envelope, the stamp column is empty. Counted from the same
                // rows the query bounds walk, so query and report agree.
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT d.type, p->>'v', count(*)
                        FROM state.%s_data d, jsonb_array_elements(d.envelope->'_profile') p
                        WHERE NOT d.deleted AND (d.shape IS NULL OR d.shape = '[]'::jsonb)
                        GROUP BY 1, 2 ORDER BY 1, 2""".formatted(domain));
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(new ShapeLine(domain, rs.getString(1), rs.getString(2),
                                null, rs.getLong(3)));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shape inventory failed", e);
        }
        return lines;
    }

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

    /** One consumer of a domain's feed, and how far behind it is. */
    public record Delivery(String domain, String consumer, long lag) {}

    /**
     * Who reads each domain's feed, and how far behind they are.
     *
     * <p>Reported because it is what an operator needs after a restore and
     * cannot otherwise see: delivery state never travels in an archive, so
     * "did the restore leave my consumers somewhere sensible" has no answer
     * from the archive itself. A consumer at the head has nothing queued for
     * it, which is what a restore should produce — anything else means events
     * from before the backup are about to be sent a second time.
     */
    public static List<Delivery> deliveryOf(DataSource ds) {
        List<Delivery> delivery = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            for (String domain : TenantExport.domainsOf(c)) {
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT k.name,
                               (SELECT count(*) FROM state.%s_outbox o
                                WHERE (o.xact_id, o.seq) > (k.cursor_xid, k.seq))
                        FROM state.%s_consumer k
                        ORDER BY k.name""".formatted(domain, domain));
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        delivery.add(new Delivery(domain, rs.getString(1), rs.getLong(2)));
                    }
                } catch (SQLException noConsumerTable) {
                    // a domain nobody subscribes to has none, which is an
                    // answer rather than a failure
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("delivery inventory failed", e);
        }
        return delivery;
    }

    /** The inventory as the maintenance surface serves it. */
    public static String json(List<Line> lines) {
        return json(lines, List.of());
    }

    public static String json(List<Line> lines, List<Delivery> delivery) {
        return json(lines, delivery, List.of());
    }

    public static String json(List<Line> lines, List<Delivery> delivery,
            List<ShapeLine> shapes) {
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
        out.append("],\"delivery\":[");
        for (int i = 0; i < delivery.size(); i++) {
            Delivery d = delivery.get(i);
            out.append(i > 0 ? "," : "")
                    .append("{\"domain\":").append(Names.quote(d.domain()))
                    .append(",\"consumer\":").append(Names.quote(d.consumer()))
                    .append(",\"lag\":").append(d.lag())
                    .append('}');
        }
        out.append("],\"shapes\":[");
        for (int i = 0; i < shapes.size(); i++) {
            ShapeLine s = shapes.get(i);
            out.append(i > 0 ? "," : "")
                    .append("{\"domain\":").append(Names.quote(s.domain()))
                    .append(",\"name\":").append(Names.quote(s.typeName()))
                    .append(",\"profile\":").append(Names.quote(s.profile()))
                    .append(",\"version\":")
                    .append(s.version() == null ? "null" : Names.quote(s.version()))
                    .append(",\"count\":").append(s.count())
                    .append('}');
        }
        return out.append("]}").toString();
    }
}
