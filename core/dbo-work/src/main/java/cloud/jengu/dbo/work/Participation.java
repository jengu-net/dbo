package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How work reaches whoever does it, and how they say what happened (#77).
 *
 * <p>A run says who holds it; this is how a holder — a service, an edge, a
 * hospital's own system, a person at a screen — <b>gets</b> it. Without it
 * every place that does work needs a bespoke integration, and a tenant is a
 * store with no hands.
 *
 * <p><b>The primitive already exists.</b> This is the change feed's fifth use,
 * not a sixth mechanism: a named consumer, a cursor, an ack
 * (REQ-DBO-FEED-ONE-PRIMITIVE). Which also means each participant's backlog and
 * lag are observable without anything being built for them.
 *
 * <p><b>Pull, never push.</b> The store never opens a connection outwards —
 * work crosses a boundary as a declaration something else comes and takes,
 * never as a call the store makes — and participants are precisely the things
 * behind NAT, on edges, and offline for a weekend. Pulling makes an
 * offline participant a lagging cursor rather than an outage.
 *
 * <p><b>You scale by adding claimants, never by relaxing the claim.</b> Two
 * participants may see a run; one holds it.
 */
public final class Participation {

    private final Runs runs;
    private final ChangeFeed feed;
    private final String participant;
    private final Set<String> steps;
    private final Executor identity;

    /**
     * @param participant the feed consumer name — this participant's own
     *                    cursor, which is what makes its backlog visible and
     *                    what lets it be offline without being an outage
     * @param steps       the steps it holds. A participant sees the work of the
     *                    steps it holds and no other; what it may <em>claim</em>
     *                    is narrowed again by its credential, which is the
     *                    authority's business rather than this class's
     */
    public Participation(Runs runs, ChangeFeed feed, String participant, Set<String> steps,
            Executor identity) {
        this.runs = runs;
        this.feed = feed;
        this.participant = participant;
        this.steps = Set.copyOf(steps);
        this.identity = identity;
    }

    /**
     * The work waiting for this participant, from where it left off.
     *
     * <p>Acked as it is read: the cursor is about having <b>seen</b> the events,
     * not about having done the work. What stops work being lost when a
     * participant dies is the claim and its deadline, not the cursor — and a
     * cursor that only moved on completion would replay everything a slow
     * participant had already claimed.
     */
    public List<Run> poll(int limit) {
        FeedChunk<FeedItem> chunk = feed.readFor(participant, limit);
        if (chunk.items().isEmpty()) {
            return List.of();
        }
        List<Run> mine = new ArrayList<>();
        Instant now = Instant.now();
        for (FeedItem item : chunk.items()) {
            if (!WorkModel.TYPE.equals(item.typeName())) {
                continue;
            }
            runs.byId(item.objectId())
                    .filter(run -> steps.contains(run.step()))
                    .filter(run -> run.item() == null)
                    .filter(run -> !run.claimed(now))
                    .filter(Run::open)
                    .ifPresent(mine::add);
        }
        feed.ack(participant, chunk.nextCursor());
        return List.copyOf(mine);
    }

    /**
     * Takes one, or does not.
     *
     * @param holdFor how long this participant is claiming it for. Long enough
     *                to do the work, short enough that its death is noticed.
     */
    public Optional<Run> claim(Run run, Duration holdFor) {
        return runs.claim(run, identity, Instant.now().plus(holdFor));
    }

    /** Progress, which extends the claim — never a tick (see {@link Runs#checkpoint}). */
    public Run checkpoint(Run run, java.util.Map<String, Long> counts, Duration holdFor) {
        return runs.checkpoint(run, counts, Instant.now().plus(holdFor));
    }

    /**
     * Hands back everything whose claim has lapsed, so it can be taken again.
     *
     * <p>Anybody may run this — it is the tenant's own housekeeping rather than
     * the dead participant's, which is the point: the participant that needed
     * noticing is the one that cannot notice.
     */
    public static int releaseLapsed(Runs runs) {
        List<Run> lapsed = runs.lapsed(Instant.now());
        lapsed.forEach(run -> runs.released(run,
                "the claim lapsed at " + run.assignment().until()
                        + " — released, which is not the same as done"));
        return lapsed.size();
    }

    /**
     * How far behind this participant is: the answer to "which automation is
     * behind", per step, without anything being built for it.
     */
    public long lag() {
        return feed.lag(participant);
    }

    /** Where this participant has got to, which is what presence is read from. */
    public String cursor() {
        return feed.cursorOf(participant);
    }
}
