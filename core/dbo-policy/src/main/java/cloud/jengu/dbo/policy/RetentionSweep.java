package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.core.api.Domains;
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
 * <p>It is a <b>sweep</b> in the run model's sense: one durable run
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
    private final boolean atAPlace;
    private final Windows windows;

    /** Where the keys content of a kind is sealed under are kept, one per day. */
    @FunctionalInterface
    public interface Windows {

        /**
         * Destroys the kind's keys for every day before the one given.
         *
         * @return the days whose keys were destroyed now
         */
        List<java.time.LocalDate> destroyBefore(String kind, java.time.LocalDate day);
    }

    /** A sweep on the node the tenant's own records are kept on. */
    public RetentionSweep(DataSource dataSource, String domain, TenantPolicies policies,
            PolicyObjectStore auditSink, Runs runs) {
        this(dataSource, domain, policies, auditSink, runs, false);
    }

    /**
     * @param atAPlace whether this node serves a place of the tenant, which keeps
     *                 content for the kind's window at a place rather than at the
     *                 tenant
     */
    public RetentionSweep(DataSource dataSource, String domain, TenantPolicies policies,
            PolicyObjectStore auditSink, Runs runs, boolean atAPlace) {
        this(dataSource, domain, policies, auditSink, runs, atAPlace, null);
    }

    /**
     * @param windows where the tenant keeps the keys content of its kinds is sealed
     *                to its window under, or null for a tenant that holds no keys
     */
    public RetentionSweep(DataSource dataSource, String domain, TenantPolicies policies,
            PolicyObjectStore auditSink, Runs runs, boolean atAPlace, Windows windows) {
        this.windows = windows;
        this.ds = dataSource;
        this.domain = domain;
        this.policies = policies;
        this.auditSink = auditSink;
        this.runs = runs;
        this.atAPlace = atAPlace;
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
            for (Map.Entry<String, TenantPolicies.ContentKind> kind
                    : policies.content().entrySet()) {
                try {
                    removed += sweepContent(kind.getKey(), kind.getValue().keep(atAPlace));
                    if (kind.getValue().sealedToWindow() && windows != null) {
                        destroyWindows(kind.getKey(), kind.getValue().keep(atAPlace));
                    }
                } catch (RuntimeException e) {
                    pass.item("content " + kind.getKey(), Failure.of(e),
                            String.valueOf(e.getMessage()));
                }
            }
        } finally {
            cloud.jengu.dbo.core.api.Caller.clearRun();
        }
        pass.counted("removed", removed).done();
        return removed;
    }

    /**
     * Content of a kind put longer ago than its window, removed and audited
     * by key and rule as a record is — never with what it held.
     */
    private int sweepContent(String kind, java.time.Duration keep) {
        List<String> expired = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM state.blob WHERE key IN (
                       SELECT key FROM state.blob WHERE kind = ? AND written_at < ? LIMIT 500)
                     RETURNING key::text""")) {
            ps.setString(1, kind);
            ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now().minus(keep)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    expired.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("retention sweep failed for content '" + kind + "'",
                    e);
        }
        for (String key : expired) {
            auditSink.record("retention-remove", "Binary", key,
                    "kind=" + kind + " keep=" + keep + (atAPlace ? " at a place" : ""));
        }
        return expired.size();
    }

    /**
     * The kind's keys for every day whose window has passed for the last of
     * its content, destroyed and audited by kind and day.
     *
     * <p>After the rows, as the backstop rather than the removal: content put
     * on a day has gone by the time its day's key is, and what the key's
     * destruction reaches is every copy no sweep can — a backup taken last
     * month, a replica, an archive somebody kept.
     */
    private void destroyWindows(String kind, java.time.Duration keep) {
        java.time.LocalDate passed = java.time.LocalDate.ofInstant(Instant.now().minus(keep),
                java.time.ZoneOffset.UTC);
        for (java.time.LocalDate day : windows.destroyBefore(kind, passed)) {
            auditSink.record("retention-key-destroyed", "Binary", kind + "/" + day,
                    "kind=" + kind + " keep=" + keep + (atAPlace ? " at a place" : ""));
        }
    }

    private int sweepType(String typeName, Instant ceiling, TenantPolicies.Retention rule) {
        List<String> expired = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id FROM %s_data
                    WHERE type = ? AND last_updated < ? LIMIT 500""".formatted(Domains.tables(domain)))) {
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
                            "DELETE FROM %s_history WHERE id = ?::uuid".formatted(Domains.historyTables(domain)),
                            "DELETE FROM %s_identifier WHERE object_id = ?::uuid".formatted(Domains.tables(domain)),
                            "DELETE FROM %s_reference WHERE owner_id = ?::uuid".formatted(Domains.tables(domain)),
                            "DELETE FROM %s_data WHERE id = ?::uuid".formatted(Domains.tables(domain)))) {
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
