package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.transport.Handover;
import cloud.jengu.dbo.sync.Lanes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A place's work, handed to the tenant it is a place of, up the lane it
 * already holds: the runs it performed, what each produced, and their
 * trail — what it made and nothing it was given.
 *
 * <p><b>Sent is what the tenant took.</b> A batch is marked sent here only
 * once the tenant answered that it applied it, so a link that drops
 * mid-push loses nothing: the same batch goes again next round, and the
 * tenant, which applies a batch once however often it arrives, says it held
 * it already. While the link is down the work waits here, and it goes when
 * the link is back.
 *
 * <p>The processes that travel are the ones the tenant declares steps of.
 * A place's own housekeeping is not its tenant's business.
 */
final class RunsPusher {

    /** The peer a place's lane keeps its position for: home. */
    static final String HOME = "home";
    /** Items per push: what one message carries, under what the tenant takes at once. */
    private static final int AT_ONCE = 200;
    /** Pushes per round, so a long-away place catches up without holding the round. */
    private static final int MOST_PER_ROUND = 50;

    private final Lanes lanes;
    private final Handover home;
    private final Set<String> processes;

    RunsPusher(Lanes lanes, Handover home, Set<String> processes) {
        this.lanes = lanes;
        this.home = home;
        this.processes = Set.copyOf(processes);
    }

    /** The processes of the steps a tenant declares: what its places send home. */
    static Set<String> processesOf(TenantSpec spec) {
        Set<String> processes = new LinkedHashSet<>();
        for (TenantSpec.Step step : spec.steps()) {
            processes.add(cloud.jengu.dbo.core.process.StepId.of(step.code()).processId());
        }
        return processes;
    }

    /**
     * Hands what the tenant has not taken yet. Returns how many items went.
     *
     * @throws cloud.jengu.dbo.core.api.StoreUnreachableException when the
     *         tenant cannot be reached; what was not taken waits here
     */
    int round() {
        if (processes.isEmpty()) {
            return 0;
        }
        int handed = 0;
        for (int push = 0; push < MOST_PER_ROUND; push++) {
            Lanes.Batch batch = lanes.outboundHome(HOME, AT_ONCE, processes);
            if (batch.isEmpty()) {
                // Nothing of the declared work in this stretch of the feed:
                // moved past, so it is not read again, and the round ends.
                lanes.sent(HOME, batch);
                break;
            }
            Handover.Taken taken = home.handed(RecordWire.encode(batch));
            lanes.sent(HOME, batch);
            handed += batch.items().size();
            if (!taken.refused().isEmpty()) {
                // Refused by name, item by item, and not handed again: a record
                // the tenant will not hold is not held the next time either,
                // and a lane that stalled on it would carry nothing after it.
                // Said, so somebody reads which and why.
                throw new IllegalStateException("the tenant refused " + taken.refused().size()
                        + " of what this place handed: " + taken.refused());
            }
        }
        return handed;
    }
}
