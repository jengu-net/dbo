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
 * How work reaches whoever does it, and how they say what happened.
 *
 * <p>A run says who may take it; this is how a taker — a service, a worker, a
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
 * behind NAT, on remote sites, and offline for a weekend. Pulling makes an
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
    private final String client;

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
        this(runs, feed, participant, steps, identity, null);
    }

    /**
     * @param client the credential this participant asks under, as the
     *               authority read it, or null for one in the node's own
     *               process. With the executor's name it is who a run
     *               addressed to one participant is for.
     */
    public Participation(Runs runs, ChangeFeed feed, String participant, Set<String> steps,
            Executor identity, String client) {
        this.runs = runs;
        this.feed = feed;
        this.participant = participant;
        this.steps = Set.copyOf(steps);
        this.identity = identity;
        this.client = client;
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
                    // What automation may take, and nothing else: a run open
                    // to people alone was offered here too, and the claim
                    // that followed made it automation's.
                    .filter(run -> run.forAutomation(now))
                    // And only what is for this participant. Passed over for
                    // good on this cursor, which is right: it never becomes
                    // this participant's, and the one it names reads it on
                    // its own cursor whenever it comes back.
                    .filter(run -> run.forParticipant(client, identity.name()))
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
        return client == null ? runs.claim(run, identity, holdFor)
                : runs.claim(run, identity, holdFor, client);
    }

    /**
     * Progress, which extends the claim — never a tick (see {@link Runs#checkpoint}).
     *
     * <p>Said as this participant's holder, under the claim {@code run}
     * carries: a claim housekeeping handed back, or one another participant
     * took since — a replica of this one among them — is refused, and nothing
     * is written in its name.
     *
     * @param run the run as its claim, or the last verb said about it, handed
     *            it back
     * @throws Runs.NotHeld when the run no longer stands under that claim
     */
    public Run checkpoint(Run run, java.util.Map<String, Long> counts, Duration holdFor) {
        return runs.checkpoint(run, identity, counts, holdFor);
    }

    /**
     * Hands back everything whose claim has lapsed, and readies what was held
     * back and is due, so it can be taken again.
     *
     * <p>Anybody may run this — it is the tenant's own housekeeping rather than
     * the dead participant's, which is the point: the participant that needed
     * noticing is the one that cannot notice.
     */
    public static int releaseLapsed(Runs runs) {
        Instant now = Instant.now();
        List<Run> lapsed = runs.lapsed(now);
        // A lapse is a failure like any other, routed by what the step
        // declared: an executor that died said nothing about why, so it goes
        // back to automation only where the step said a lapse will pass.
        // Each handed back only if it still lapsed as found: a holder that
        // reported meanwhile, or a participant that took it, wrote first.
        int handedBack = 0;
        for (Run run : lapsed) {
            if (runs.handBack(run, now).isPresent()) {
                handedBack++;
            }
        }
        // And what was held back and is due, offered again.
        runs.due(now);
        return handedBack;
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
