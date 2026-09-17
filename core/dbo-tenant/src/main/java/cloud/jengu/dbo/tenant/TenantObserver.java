package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.feed.FeedItem;

import java.util.List;

/**
 * Something outside the runtime watching one of a tenant's streams.
 *
 * <p>Registered as a service with the domain it wants, the durable consumer
 * name it reads as, and — optionally — a filter over the tenant's published
 * facts saying which tenants it is for. A registration with no filter observes
 * every tenant that has the stream, which is a choice rather than a default.
 *
 * <pre>
 * dbo.tenant.domain   = work
 * dbo.tenant.consumer = billing
 * dbo.tenant.target   = (dbo.tenant.kind=ext.clinic)
 * </pre>
 *
 * <p><b>Why this and not a callback.</b> Where a durable feed already exists,
 * an observer consumes it. A callback would put another bundle in the path of
 * the work and would lose every event that happened while that bundle was
 * down — tolerable for something rare and re-derivable, wrong for anything
 * that bills. Reading the feed as a named consumer means an observer that was
 * absent resumes rather than missing the interval, and the position it resumes
 * from is the store's, not its own.
 *
 * <p>An observer sees what the stream carries and is handed no store, no
 * connection and no reference it can resolve. A primitive widened for one
 * caller is widened for every caller holding the scope, forever, and the
 * widening is invisible at the site that asked for it.
 *
 * <p>Delivery is at-least-once, so an observer is responsible for tolerating a
 * repeat: progress is acknowledged after it returns, and a batch it processed
 * before a crash arrives again.
 */
@FunctionalInterface
public interface TenantObserver {

    /**
     * Called with what the stream carried, in order, for one tenant.
     *
     * <p>Throwing leaves the batch unacknowledged: it will arrive again rather
     * than being skipped. Nothing here can stop the tenant serving.
     *
     * @param tenant the facts this observer was selected by
     * @param items  what the stream carried, never empty
     */
    void observed(TenantFacts tenant, List<FeedItem> items);
}
