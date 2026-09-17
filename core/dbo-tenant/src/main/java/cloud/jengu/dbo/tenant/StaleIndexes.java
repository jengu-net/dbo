package cloud.jengu.dbo.tenant;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenants whose index is stale because a reindex did not finish.
 *
 * <p>Held against the tenant rather than against the feed. The feed's events
 * are acknowledged <em>before</em> the rebuild runs — deliberately, so a broken
 * profile is not re-read forever — which left a reindex that failed with
 * nothing to bring it back: the index stayed stale behind one warning, and a
 * stale envelope does not make a search slow, it makes it <b>miss</b>.
 *
 * <p>Small enough to be obvious, and separate so it can be stated as behaviour
 * rather than as three lines inside a loop.
 */
final class StaleIndexes {

    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /**
     * Remembers that this tenant's index needs rebuilding.
     *
     * @return whether this is news — false when it was already pending, which
     *         is how the warning is said once rather than every round. A log
     *         that repeats itself every few seconds stops being read, and the
     *         recovery is the line worth keeping.
     */
    boolean note(String code) {
        return pending.add(code);
    }

    /**
     * Whether a rebuild should run for this tenant now.
     *
     * <p>True when something arrived that needs indexing, and true while a
     * previous attempt is outstanding even though nothing new arrived — which
     * is the whole point: the events that would have triggered it are gone.
     */
    boolean needsRebuild(String code, boolean somethingArrived) {
        return somethingArrived || pending.contains(code);
    }

    /**
     * Records that this tenant's index is current again.
     *
     * @return whether it had been outstanding, so the recovery can be reported
     *         once, by the round that achieved it
     */
    boolean cleared(String code) {
        return pending.remove(code);
    }

    /** What is outstanding, for anything that reports on a deployment. */
    Set<String> outstanding() {
        return Set.copyOf(pending);
    }
}
