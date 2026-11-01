package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.tenant.api.RegisterStanding;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One tenant's register as its listeners are told it: read on the spot, and
 * told again only when what stands differs from what they were told last.
 *
 * <p>Kept across a tenant's rebuild, so a listener outlives a redeclaration;
 * dropped when the tenant is retracted.
 */
final class StandingWatch implements RegisterStanding {

    private static final Logger LOG = LoggerFactory.getLogger(StandingWatch.class);

    private final String tenant;
    private final Supplier<Standing> reading;
    private final List<Consumer<Standing>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** What stands as listeners were last told it, without when the place last heard. */
    private Standing told;

    StandingWatch(String tenant, Supplier<Standing> reading) {
        this.tenant = tenant;
        this.reading = reading;
    }

    @Override
    public Standing now() {
        return reading.get();
    }

    @Override
    public synchronized AutoCloseable whenChanged(Consumer<Standing> listener) {
        if (told == null) {
            told = withoutHearing(reading.get());
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Tells every listener what stands, where it differs from what they were told. */
    synchronized void tell() {
        if (listeners.isEmpty()) {
            return;
        }
        Standing now = reading.get();
        if (withoutHearing(now).equals(told)) {
            return;
        }
        told = withoutHearing(now);
        for (Consumer<Standing> listener : listeners) {
            try {
                listener.accept(now);
            } catch (RuntimeException failed) {
                // One listener's failure is its own: the others are told, and
                // the store's round goes on.
                LOG.warn("a listener of tenant {}'s register failed: {}", tenant,
                        failed.toString());
            }
        }
    }

    /**
     * What stands, without when a place last heard its origin: hearing it
     * again with nothing changed is not a change.
     */
    private static Standing withoutHearing(Standing standing) {
        return new Standing(standing.purposes(), standing.steps(), standing.authorisedAt(),
                null);
    }
}
