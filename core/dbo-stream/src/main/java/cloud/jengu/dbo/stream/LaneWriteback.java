package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Run;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * A performer's report, carried by the tenant's own lane.
 *
 * <p>This is the whole of what makes the writeback safe: every verb below is
 * the lane's, so an outcome arriving from a fleet consumer meets the rules an
 * outcome from a participant on a port meets. Whether a machine may close this
 * step, whether the report is in order for the state the run is in, and who is
 * recorded as having performed it are all decided there and none of them are
 * decided here.
 *
 * <p><b>It claims before it reports</b>, because a report on a run nobody
 * holds is a report the lane refuses — and rightly: the hold is what says this
 * performer is the one whose account of the work counts. The joiner claims
 * nothing precisely so that the claim happens here, where the work is actually
 * being done.
 */
public final class LaneWriteback implements FleetWork.Writeback {

    /** How a tenant's lane is obtained, and by whom. */
    @FunctionalInterface
    public interface Lanes {
        Optional<Lane> into(String tenant, String stepCode);
    }

    /** How a run is found in the tenant that authored it. */
    @FunctionalInterface
    public interface Finder {
        Optional<Run> byKey(String tenant, String runKey);
    }

    /**
     * How long a performer holds a run while it works.
     *
     * <p>Long enough that ordinary work finishes inside it, and bounded so a
     * consumer that dies does not hold a tenant's run for ever: the hold
     * lapsing is how this store already says a performer stopped answering.
     */
    private static final Duration HOLD = Duration.ofMinutes(10);

    private final Lanes lanes;
    private final Finder finder;

    public LaneWriteback(Lanes lanes, Finder finder) {
        this.lanes = lanes;
        this.finder = finder;
    }

    @Override
    public Optional<FleetWork.Reporting> reporting(String tenant, String step, String runKey) {
        Optional<Lane> lane = lanes.into(tenant, step);
        Optional<Run> run = finder.byKey(tenant, runKey);
        if (lane.isEmpty() || run.isEmpty()) {
            // A tenant that has gone, or a run that has. Neither is this
            // consumer's to resolve and neither is an error here: the item
            // waits, which is what an item nobody has taken looks like.
            return Optional.empty();
        }
        Optional<Run> held = lane.get().claim(run.get(), HOLD);
        if (held.isEmpty()) {
            // Somebody else holds it, or it is no longer claimable. Refusing
            // to report is the right answer: two performers reporting on one
            // run is the thing the hold exists to stop.
            return Optional.empty();
        }
        return Optional.of(new OnTheLane(lane.get(), held.get()));
    }

    /** Each verb the lane's own, and the run it was given. */
    private static final class OnTheLane implements FleetWork.Reporting {

        private final Lane lane;
        private volatile Run run;

        OnTheLane(Lane lane, Run run) {
            this.lane = lane;
            this.run = run;
        }

        @Override
        public void closed(Map<String, Long> tally) {
            run = lane.checkpoint(run, tally, HOLD);
            lane.closed(run);
        }

        @Override
        public void checkpoint(Map<String, Long> counts) {
            run = lane.checkpoint(run, counts, HOLD);
        }

        @Override
        public void released(String reason) {
            lane.released(run, reason);
        }
    }
}
