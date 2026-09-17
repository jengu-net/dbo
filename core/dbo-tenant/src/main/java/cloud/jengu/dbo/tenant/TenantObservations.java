package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Who is watching which stream, and the reading of it.
 *
 * <p>One poller per observer per tenant, each reading as its own named
 * consumer, so two observers of the same stream cannot consume each other's
 * position and one falling behind does not hold the other up.
 *
 * <p>An observer whose domain this tenant does not have is not started at all
 * — a face root has no content domain, and starting a reader against a
 * relation that does not exist is precisely the defect this whole mechanism
 * came from.
 */
final class TenantObservations {

    private record Registered(TenantDomain domain, String consumer, Filter target,
            String name, TenantObserver observer) {
        boolean appliesTo(TenantFacts facts) {
            return target == null || target.matches((Map<String, ?>) facts.properties());
        }
    }

    /** How long a reader waits before looking again when it last found nothing. */
    private static final long IDLE_MILLIS = 1_000;
    /** How many items one read takes at a time. */
    private static final int BATCH = 200;

    private final List<Registered> registered = new CopyOnWriteArrayList<>();

    void register(TenantDomain domain, String consumer, String target, String name,
            TenantObserver observer) {
        if (consumer == null || consumer.isBlank()) {
            // Without one there is no durable position, and an observer
            // without a durable position is a callback wearing this name.
            throw new IllegalArgumentException(name + ": an observer reads as a named "
                    + "consumer, and none was given");
        }
        Filter filter = null;
        if (target != null && !target.isBlank()) {
            try {
                filter = FrameworkUtil.createFilter(target);
            } catch (InvalidSyntaxException e) {
                throw new IllegalArgumentException(
                        name + ": the target filter cannot be parsed: " + target, e);
            }
        }
        registered.add(new Registered(domain, consumer, filter, name, observer));
    }

    /**
     * Starts every observer that applies to this tenant, and returns what
     * stops them.
     *
     * @param feeds       how to obtain the feed of a domain on this tenant
     * @param failed      told when an observer throws, by name
     */
    List<AutoCloseable> startFor(TenantFacts facts, String recordDomain,
            BiFunction<TenantFacts, String, ChangeFeed> feeds,
            java.util.function.BiConsumer<String, Exception> failed) {
        List<AutoCloseable> running = new ArrayList<>();
        for (Registered one : registered) {
            if (!one.appliesTo(facts)) {
                continue;
            }
            String domain = one.domain().on(facts, recordDomain);
            if (domain == null) {
                // This tenant does not carry that stream. Not an error and
                // not a warning: an observer of content is simply not started
                // for a tenant that holds its records elsewhere.
                continue;
            }
            running.add(start(one, facts, feeds.apply(facts, domain), failed));
        }
        return running;
    }

    private AutoCloseable start(Registered one, TenantFacts facts, ChangeFeed feed,
            java.util.function.BiConsumer<String, Exception> failed) {
        java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        // One failure is reported, and the next identical one is not, because
        // the defect this replaces was a failure repeating once a second while
        // saying nothing. Repeating it once a second while saying everything
        // would be the same mistake from the other side.
        java.util.concurrent.atomic.AtomicBoolean reported =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = Thread.ofVirtual()
                .name("dbo-observer-" + facts.code() + "-" + one.consumer())
                .start(() -> {
                    while (running.get()) {
                        try {
                            FeedChunk<FeedItem> chunk = feed.readFor(one.consumer(), BATCH);
                            List<FeedItem> items = chunk == null ? List.of() : chunk.items();
                            if (items.isEmpty()) {
                                Thread.sleep(IDLE_MILLIS);
                                continue;
                            }
                            one.observer().observed(facts, items);
                            // Acknowledged only after it returned: a batch an
                            // observer did not survive arrives again rather
                            // than being lost.
                            feed.ack(one.consumer(), chunk.nextCursor());
                            reported.set(false);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (RuntimeException e) {
                            if (reported.compareAndSet(false, true)) {
                                failed.accept(one.name(), e);
                            }
                            try {
                                Thread.sleep(IDLE_MILLIS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                });
        return () -> {
            // Asked first, and only made to stop if it does not — the same
            // reason the subscription dispatcher is stopped this way. The loop
            // checks the flag and sleeps between passes, so an idle observer
            // stops on its own; interrupting one that is inside a JDBC call
            // closes the socket under pgjdbc and destroys a pooled connection
            // that had nothing wrong with it.
            running.set(false);
            try {
                thread.join(2_000);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
            }
        };
    }
}
