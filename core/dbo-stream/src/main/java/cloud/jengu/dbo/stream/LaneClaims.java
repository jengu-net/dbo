package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Run;

import java.time.Duration;
import java.util.Optional;

/**
 * The tenant's own lane, with the run held on it.
 *
 * <p>This is the whole of what makes a fleet step safe: the lane it hands over
 * is the tenant's, so everything done through it afterwards meets the rules an
 * outcome from a participant on a port meets. Whether a machine may close this
 * step, whether the report is in order for the state the run is in, and who is
 * recorded as having performed it are all decided there and none of them are
 * decided here.
 *
 * <p><b>It claims before it hands over</b>, because the hold is what entitles
 * the performer both to the data and to reporting: a report on a run nobody
 * holds is refused, and rightly. The joiner claims nothing precisely so that
 * the claim happens here, where the work is about to be done.
 */
public final class LaneClaims implements FleetPerformer.Claims {

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

    private final Lanes lanes;
    private final Finder finder;

    public LaneClaims(Lanes lanes, Finder finder) {
        this.lanes = lanes;
        this.finder = finder;
    }

    @Override
    public Optional<FleetPerformer.Held> held(String tenant, String step, String runKey,
            Duration holdFor) {
        Optional<Lane> lane = lanes.into(tenant, step);
        Optional<Run> run = finder.byKey(tenant, runKey);
        if (lane.isEmpty() || run.isEmpty()) {
            // A tenant that has gone, or a run that has. Neither is this
            // consumer's to resolve and neither is an error here: the item
            // waits, which is what an item nobody has taken looks like.
            return Optional.empty();
        }
        Optional<Run> held = lane.get().claim(run.get(), holdFor);
        if (held.isEmpty()) {
            // Somebody else holds it, or it is no longer claimable. Refusing
            // is the right answer: two performers reporting on one run is the
            // thing the hold exists to stop.
            return Optional.empty();
        }
        return Optional.of(new FleetPerformer.Held(lane.get(), held.get()));
    }
}
