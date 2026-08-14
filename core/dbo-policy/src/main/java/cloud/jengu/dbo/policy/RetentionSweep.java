package cloud.jengu.dbo.policy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The declarative removal timeframe, executed (§15.3): objects whose LAST
 * version is older than the type's {@code removeAfter} are removed from
 * state AND history — the one sanctioned mutation of history, and every
 * removal is audited (target id + rule, never the data). Idempotent; run
 * at tenant bring-up (which makes a restored pre-sweep archive come up
 * already swept) and periodically thereafter.
 */
public final class RetentionSweep {

    private final DataSource ds;
    private final String domain;
    private final TenantPolicies policies;
    private final PolicyObjectStore auditSink;

    public RetentionSweep(DataSource dataSource, String domain, TenantPolicies policies,
            PolicyObjectStore auditSink) {
        this.ds = dataSource;
        this.domain = domain;
        this.policies = policies;
        this.auditSink = auditSink;
    }

    /** @return ids removed this pass */
    public int sweepOnce() {
        int removed = 0;
        for (Map.Entry<String, TenantPolicies.Retention> rule : policies.retention().entrySet()) {
            if (rule.getValue().removeAfter() == null) {
                continue;
            }
            Instant ceiling = Instant.now().minus(rule.getValue().removeAfter());
            removed += sweepType(rule.getKey(), ceiling, rule.getValue());
        }
        return removed;
    }

    private int sweepType(String typeName, Instant ceiling, TenantPolicies.Retention rule) {
        List<String> expired = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id FROM state.%s_data
                    WHERE type = ? AND last_updated < ? LIMIT 500""".formatted(domain))) {
                ps.setString(1, typeName);
                ps.setTimestamp(2, java.sql.Timestamp.from(ceiling));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expired.add(rs.getString(1));
                    }
                }
            }
            for (String id : expired) {
                c.setAutoCommit(false);
                try {
                    for (String sql : List.of(
                            "DELETE FROM history.%s_history WHERE id = ?::uuid".formatted(domain),
                            "DELETE FROM state.%s_identifier WHERE object_id = ?::uuid".formatted(domain),
                            "DELETE FROM state.%s_reference WHERE owner_id = ?::uuid".formatted(domain),
                            "DELETE FROM state.%s_data WHERE id = ?::uuid".formatted(domain))) {
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setString(1, id);
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
                auditSink.record("retention-remove", typeName, id,
                        "removeAfter=" + rule.removeAfter());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("retention sweep failed for " + typeName, e);
        }
        return expired.size();
    }
}
