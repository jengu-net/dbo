package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Both ends of a wake-up inside one JVM: the store side tells it a run became
 * claimable, the runner side listens.
 *
 * <p>It is one object because the two ends have to be the same object — a host
 * that built two would have a store telling nobody and a runner listening to
 * nothing, and neither half would look wrong on its own. Wiring it is
 * therefore one line on each side of the tenant's bring-up, both naming this.
 *
 * <p>Nothing about the run travels. What a listener is told is <em>look
 * again</em>, and it then polls and claims through the ordinary path — because
 * the claim race is what decides who gets the work, and this must not become a
 * second answer to that.
 *
 * <p>A listener that throws is caught and the rest still run, for the reason
 * the store side already has: the work is real whether or not anybody was
 * told, and the taker's poll is underneath this.
 */
public final class InProcessWakeups implements Runs.Claimable, Wakeups {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(InProcessWakeups.class);

    private final List<Runnable> listening = new CopyOnWriteArrayList<>();

    @Override
    public void appeared(Run run) {
        for (Runnable listener : listening) {
            try {
                listener.run();
            } catch (RuntimeException failed) {
                // Said here because this is where a logger exists: the module
                // that owns the run record carries the core and nothing else,
                // so it cannot say that nobody was told. Somebody has to, or
                // a delivery mechanism goes missing in silence — which is the
                // shape of the defect this whole area came from.
                LOG.warn("a run became claimable and a listener failed: key={} {}",
                        run.key(), failed.getMessage());
            }
        }
    }

    @Override
    public AutoCloseable wake(Runnable woken) {
        listening.add(woken);
        return () -> listening.remove(woken);
    }

    /** Whether anything is listening — for a host deciding whether to bother. */
    public boolean listening() {
        return !listening.isEmpty();
    }
}
