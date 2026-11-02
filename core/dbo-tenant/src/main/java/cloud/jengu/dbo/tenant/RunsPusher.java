package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.transport.Handover;
import cloud.jengu.dbo.sync.Lanes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

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
 * <p>The processes that travel are the ones the tenant declares steps of,
 * as its declaration stands now. A place's own housekeeping is not its
 * tenant's business.
 *
 * <p><b>A process declared later loses nothing.</b> The position home was
 * read for the processes declared then, so runs of one declared since were
 * passed over, and the feed behind the position may have been let go of.
 * Those runs are still in the store, and are handed from there once.
 */
final class RunsPusher {

    /** The peer a place's lane keeps its position for: home. */
    static final String HOME = "home";
    /** Items per push: what one message carries, under what the tenant takes at once. */
    private static final int AT_ONCE = 200;
    /**
     * Stretches of the feed read per round, so a long-away place catches up
     * without holding the round. Counted whether or not a stretch held
     * anything to hand: a place writes more of its own housekeeping than of
     * its tenant's work, and a round that ended at the first stretch of it
     * fell further behind every round.
     */
    private static final int MOST_PER_ROUND = 50;

    private final Lanes lanes;
    private final Handover home;
    private final Supplier<Set<String>> processes;

    /** @param processes the processes the tenant declares steps of, asked each round */
    RunsPusher(Lanes lanes, Handover home, Supplier<Set<String>> processes) {
        this.lanes = lanes;
        this.home = home;
        this.processes = processes;
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
        Set<String> declared = Set.copyOf(processes.get());
        if (declared.isEmpty()) {
            return 0;
        }
        int handed = caughtUp(declared);
        for (int read = 0; read < MOST_PER_ROUND; read++) {
            String from = lanes.open(HOME).ourCursor();
            Lanes.Batch batch = lanes.outboundHome(HOME, AT_ONCE, declared);
            if (batch.isEmpty()) {
                // Nothing of the declared work in this stretch of the feed:
                // moved past, so it is not read again.
                lanes.sent(HOME, batch);
                if (java.util.Objects.equals(from, batch.cursor())) {
                    break; // the end of the feed
                }
                continue;
            }
            List<String> refused = new ArrayList<>();
            handed += handed(batch, refused);
            lanes.sent(HOME, batch);
            refusedSaid(refused);
        }
        return handed;
    }

    /**
     * The runs of each process declared since the position home last said
     * what it was read for, handed from the store; then the position says
     * it is read for what is declared now.
     */
    private int caughtUp(Set<String> declared) {
        Lanes.Lane lane = lanes.open(HOME);
        if (lane.covers() == null) {
            // A position from before positions said what they were read for,
            // taken to have been read for what is declared now: nothing says
            // otherwise, and handing every run again would be a replay of the
            // place's whole history to find none.
            lanes.covering(HOME, declared);
            return 0;
        }
        Set<String> covered = Set.copyOf(lane.covers());
        if (covered.equals(declared)) {
            return 0;
        }
        int handed = 0;
        List<String> refused = new ArrayList<>();
        for (String process : new TreeSet<>(declared)) {
            if (!covered.contains(process)) {
                handed += lanes.everyRunOf(HOME, process, AT_ONCE,
                        batch -> handed(batch, refused));
            }
        }
        // Covered before anything refused is said, so a refused run is not
        // handed again every round: it is not held the next time either.
        lanes.covering(HOME, declared);
        refusedSaid(refused);
        return handed;
    }

    private int handed(Lanes.Batch batch, List<String> refused) {
        Handover.Taken taken = home.handed(RecordWire.encode(batch));
        refused.addAll(taken.refused());
        return batch.items().size();
    }

    /**
     * Refused by name, item by item, and not handed again: a record the
     * tenant will not hold is not held the next time either, and a lane that
     * stalled on it would carry nothing after it. Said, so somebody reads
     * which and why.
     */
    private static void refusedSaid(List<String> refused) {
        if (!refused.isEmpty()) {
            throw new IllegalStateException("the tenant refused " + refused.size()
                    + " of what this place handed: " + refused);
        }
    }
}
