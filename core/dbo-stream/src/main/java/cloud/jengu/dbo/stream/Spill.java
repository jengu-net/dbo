package cloud.jengu.dbo.stream;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Where an answer too large to travel in the message waits for its asker.
 *
 * <p>An answer on this wire is an event on the door's workflow, which is a row
 * in the substrate's own tables. That is the right place for a verb's answer
 * and the wrong place for a run's inputs. Three reasons, none of them purity:
 * the substrate re-reads a workflow's inputs when it recovers one, so large
 * bytes are read again on every recovery; it keeps them under <b>its</b>
 * retention rather than the tenant's, so they outlive the work that caused
 * them by a rule nobody here set; and Postgres will TOAST a large value into a
 * side store of its own, which has none of a blob store's lifecycle and none
 * of its accounting.
 *
 * <p>So above a threshold the bytes are written here instead and the message
 * carries their key. This is a table beside the door on the same substrate —
 * <b>on the substrate</b>, because the point of this binding is that a runner
 * touches one thing, and a spill to the tenant's own blob door would be the
 * connection back into the tenant that this whole carrier exists to remove.
 *
 * <p><b>What is spilled is what would have travelled.</b> The same bytes, the
 * same sealed carrier form, moved and not transformed. That is the whole of
 * why erasure reaches a spilled payload: identifying elements are under the
 * person's key inside the seal, so destroying that key leaves a spilled copy
 * in the state the store's own records are left in by a shred. A spill that
 * unsealed to store, or stored a second rendering, would have been a copy with
 * a different reach and erasure would have a hole exactly where the large
 * payloads are.
 *
 * <p><b>Taken once.</b> A row is read and deleted in the same statement, so
 * the bytes live until the asker collects them rather than until a retention
 * rule elsewhere gets to them. What is left behind is what nobody came back
 * for — an asker that timed out, a generation that closed — and that is swept
 * by age.
 */
final class Spill {

    /**
     * Above this, an answer travels by reference.
     *
     * <p>Chosen well above a verb's answer and well below a document: a poll,
     * a claim, a checkpoint and a refusal are hundreds of bytes and must not
     * pay for a table, while a sealed record is the thing this exists for.
     * Postgres starts TOASTing around two kilobytes, so anything at this size
     * was already going to a side store — the question was only whether it
     * went to one with a lifecycle.
     */
    static final int SPILL_OVER = 64 * 1024;

    /** How long an uncollected answer is kept before it is somebody's litter. */
    private static final Duration UNCOLLECTED = Duration.ofHours(6);

    private final DataSource substrate;

    Spill(DataSource substrate) {
        this.substrate = substrate;
    }

    /**
     * The table, beside the substrate's own in its schema.
     *
     * <p>Created by whoever touches it first and not by a migration: this
     * bundle is dropped into a container that already has a substrate, and a
     * carrier that required a schema change before it could carry anything
     * would not be droppable.
     */
    private void ensureTable(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                CREATE TABLE IF NOT EXISTS dbos.dbo_lane_spill (
                  key text PRIMARY KEY,
                  tenant text NOT NULL,
                  body text NOT NULL,
                  spilled_at timestamptz NOT NULL DEFAULT now()
                )""")) {
            ps.execute();
        }
    }

    /** Keeps an answer for its asker, and answers with the key that names it. */
    String keep(String tenant, String key, String body) {
        try (Connection c = substrate.getConnection()) {
            ensureTable(c);
            sweep(c);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO dbos.dbo_lane_spill (key, tenant, body)
                    VALUES (?, ?, ?)
                    ON CONFLICT (key) DO UPDATE SET body = EXCLUDED.body""")) {
                ps.setString(1, key);
                ps.setString(2, tenant);
                ps.setString(3, body);
                ps.executeUpdate();
            }
            return key;
        } catch (SQLException failed) {
            throw new IllegalStateException(tenant + ": the answer did not spill", failed);
        }
    }

    /**
     * The asker's side: the bytes, and they are gone.
     *
     * <p>Read and deleted in one statement so two askers cannot both be
     * handed them — which cannot happen, because a key is an ask's id, but a
     * statement that relies on that is a statement that stops being true when
     * somebody changes how ids are made.
     */
    Optional<String> take(String key) {
        try (Connection c = substrate.getConnection()) {
            ensureTable(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM dbos.dbo_lane_spill WHERE key = ? RETURNING body")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("the spilled answer '" + key
                    + "' could not be collected", failed);
        }
    }

    /**
     * Drops what nobody came back for.
     *
     * <p>On the writing side rather than on a timer: a door that is spilling
     * is a door that is being used, and a sweep that needed its own thread
     * would be a thread per tenant asleep for six hours at a time.
     */
    private void sweep(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM dbos.dbo_lane_spill WHERE spilled_at < now() - ?::interval")) {
            ps.setString(1, UNCOLLECTED.toHours() + " hours");
            ps.executeUpdate();
        }
    }
}
