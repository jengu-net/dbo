package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

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
 *
 * <p>It is a <b>sweep</b> in the run model's sense (#46, #69): one durable run
 * per tenant domain, found rather than started, and every pass is a checkpoint
 * on it. A rule that cannot be applied stays open as an item until a pass stops
 * finding it — so a person fixes the world and the next round closes the card
 * (REQ-DBO-PROC-CLOSE-BY-RE-EVALUATION), rather than a count being returned to
 * a caller who has already gone.
 */
public final class RetentionSweep {

    /** What this sweep is, in the catalogue every run names. */
    public static final String PROCESS = "dbo.policy.retention";

    /** Its one step: the removal itself. */
    public static final String STEP = "remove";

    private final DataSource ds;
    private final String domain;
    private final TenantPolicies policies;
    private final PolicyObjectStore auditSink;
    private final Runs runs;

    public RetentionSweep(DataSource dataSource, String domain, TenantPolicies policies,
            PolicyObjectStore auditSink, Runs runs) {
        this.ds = dataSource;
        this.domain = domain;
        this.policies = policies;
        this.auditSink = auditSink;
        this.runs = runs;
    }

    /**
     * One pass over the sweep.
     *
     * <p>A rule that fails does not stop the ones after it: the pass records
     * what it could not do and carries on, because a sweep that abandons the
     * round on the first fault leaves everything after that rule unswept for as
     * long as the fault lasts.
     *
     * @return ids removed this pass
     */
    public int sweepOnce() {
        Run sweep = runs.sweep(PROCESS, STEP, domain, List.of(domain));
        Runs.Pass pass = runs.pass(sweep);
        int removed = 0;
        // Everything this pass audits is part of this run, so the removals are
        // reachable from the run rather than only from the ids they name.
        cloud.jengu.dbo.core.api.Caller.setRun(sweep.key());
        try {
            for (Map.Entry<String, TenantPolicies.Retention> rule : policies.retention().entrySet()) {
                if (rule.getValue().removeAfter() == null) {
                    continue;
                }
                Instant ceiling = Instant.now().minus(rule.getValue().removeAfter());
                try {
                    removed += sweepType(rule.getKey(), ceiling, rule.getValue());
                } catch (RuntimeException e) {
                    pass.item(rule.getKey(), Failure.of(e), String.valueOf(e.getMessage()));
                }
            }
        } finally {
            cloud.jengu.dbo.core.api.Caller.clearRun();
        }
        pass.counted("removed", removed).done();
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
