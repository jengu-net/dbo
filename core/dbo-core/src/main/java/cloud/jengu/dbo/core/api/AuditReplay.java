package cloud.jengu.dbo.core.api;

import java.time.Instant;

/**
 * The one admitted path for an audit entry recorded on another appliance
 * (§7.8).
 *
 * <p>Direct writes to the audit type are refused, absolutely and for
 * everybody: the trail is written by the machinery, and a caller that could
 * append to it could write somebody else's history. Replication needs an
 * exception to that and nothing else needs one — so the exception is this
 * port rather than a widened refusal, a caller authority anyone could name,
 * or a store the lane holds beneath policy.
 *
 * <p><b>Narrow on purpose.</b> It admits one act — replaying an entry some
 * other appliance already recorded — and it cannot express any other. There
 * is no update, no delete, and no way to write an entry that did not come
 * from somewhere else, because every argument here is the source's.
 *
 * <p><b>What arrives is what was recorded.</b> The actor, the interaction and
 * everything else the source wrote travel as the source's bytes; the time is
 * the source's; the appliance is named. The arrival itself writes <b>no
 * second trail</b> — a copy of an event is not a new event, and a receiving
 * store that audited its own replication would grow one entry per entry
 * forever.
 *
 * <p><b>Effectively once.</b> A lane delivers at least once, because a
 * transport that guarantees less loses events and one that guarantees more
 * does not exist. The claim on the source's identity is what makes the second
 * delivery find the first instead of landing beside it.
 */
public interface AuditReplay {

    /**
     * Replays one entry another appliance recorded.
     *
     * @param sourceAppliance which appliance recorded it — carried onto the
     *                        entry, because "who did this" is only answerable
     *                        with "and where"
     * @param sourceEntryId   its id there, which is what the claim is made on
     * @param sourceVersion   the version it stands at there. Always the first
     *                        for a trail nobody may alter — but a replay that
     *                        assumed so would be asserting the rule rather
     *                        than carrying the fact, and the store's own
     *                        monotonic guard needs the number to be true
     * @param payload         the entry as the source wrote it, replayed rather
     *                        than rebuilt: an entry re-derived on arrival would
     *                        say what this side can express instead of what the
     *                        other side recorded
     * @param recordedAt      when the SOURCE recorded it, never the arrival —
     *                        the whole of what such an entry is evidence about
     * @return whether this side did not already hold it
     */
    boolean replayAuditEntry(String sourceAppliance, String sourceEntryId, long sourceVersion,
            byte[] payload, Instant recordedAt);
}
