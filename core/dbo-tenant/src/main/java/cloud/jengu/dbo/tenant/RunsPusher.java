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
 *
 * <p><b>What was handed before marking is asked about once.</b> A place marks
 * what its tenant takes from it, and one that was serving before it marked
 * handed its work home in rounds that marked nothing. So it asks its tenant
 * which of its unmarked records it holds, a page at a time from where the lane
 * says it got to, and marks those; what the tenant does not hold stays
 * unmarked. The lane keeps how far it asked, so a restart neither asks again
 * nor starts over.
 *
 * <p><b>What the tenant declined is said here.</b> A tenant does not take a
 * record it holds one of its own in the place of, and says so in its answer.
 * The place notes each, as nothing will mark it held elsewhere and it stays
 * here for good. What the tenant declined before it knew a record whose
 * identity is internal by its id alone is handed again once, from what its
 * trail says it declined, and the tenant takes what it now would.
 *
 * <p><b>What stays here is said.</b> A place handing its trail and none of
 * its work looked healthy, because nothing that stays is a failure. So the
 * pusher keeps why: processes it read past that are not the store's own, or
 * a tenant declaring nothing.
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
     * fell further behind every round. Past this many the declared work is
     * found in the store instead.
     */
    private static final int MOST_PER_ROUND = 50;
    /**
     * The store's own processes: its streams, sweeps and bring-ups, which
     * stay here without a word, being nobody's work but this node's.
     */
    private static final String STORES = "dbo.";
    /**
     * How long before its event a run caught up from the store may be
     * stamped: the run when it was written, the event when its transaction
     * committed. Generous, as a run handed twice is held once.
     */
    private static final java.time.Duration SKEW = java.time.Duration.ofMinutes(5);
    /** Records asked about at once: only their versions travel. */
    private static final int ASKED_AT_ONCE = 500;
    /** Pages asked about per round, so a place with years behind it does not hold the round. */
    private static final int ASKED_PER_ROUND = 10;

    /** What this place says of its own records beside them. */
    interface Marks {

        /**
         * This place's records no mark covers, a page at a time.
         *
         * @param after the id of the last record asked of, or null from the first
         * @return each {@code type/id/version}, in id order
         */
        List<String> unmarkedAfter(String after, int limit);

        /** Notes what the tenant kept on the place, each {@code type/id/version Type/id}. */
        void declined(List<String> kept);

        /** The types of the records the tenant kept on the place, as they stand. */
        Set<String> declinedTypes();
    }

    private final Lanes lanes;
    private final Handover home;
    private final Supplier<Set<String>> processes;
    private final Marks marks;
    /** Why what was handed before marking is not asked about yet, or null. */
    private volatile String unasked;
    /** Why what the tenant addresses to this place is not taken, or null. */
    private volatile String untaken;
    /** Why what the tenant declined before is not handed again yet, or null. */
    private volatile String unhanded;
    /** Why what this place's own work names and it let go of is not brought back, or null. */
    private volatile String unbrought;
    /** The processes of runs read past and not handed, until the tenant declares them. */
    private final Set<String> passedOver = new java.util.concurrent.ConcurrentSkipListSet<>();
    private volatile boolean nothingDeclared;

    /** @param processes the processes the tenant declares steps of, asked each round */
    RunsPusher(Lanes lanes, Handover home, Supplier<Set<String>> processes) {
        this(lanes, home, processes, null, null);
    }

    /**
     * @param before the pusher this one replaces, whose account of what stays
     *        here carries over: the runs it read past are behind the position
     *        now, and would not be read past again to be said
     * @param marks what this place says of its own records, or null for a pusher
     *        that asks nothing about them and notes nothing
     */
    RunsPusher(Lanes lanes, Handover home, Supplier<Set<String>> processes, RunsPusher before,
            Marks marks) {
        this.lanes = lanes;
        this.home = home;
        this.processes = processes;
        this.marks = marks;
        if (before != null) {
            passedOver.addAll(before.passedOver);
        }
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
        taken();
        broughtBack();
        askedOnce();
        handedAgainOnce();
        Set<String> declared = Set.copyOf(processes.get());
        nothingDeclared = declared.isEmpty();
        if (nothingDeclared) {
            return 0;
        }
        passedOver.removeAll(declared);
        int handed = caughtUp(declared);
        boolean[] ended = {false};
        for (int read = 0; read < MOST_PER_ROUND && !ended[0]; read++) {
            Lanes.Batch batch = lanes.outboundHome(HOME, AT_ONCE, declared, process -> {
                if (!process.startsWith(STORES)) {
                    passedOver.add(process);
                }
            }, () -> ended[0] = true);
            if (batch.isEmpty()) {
                // Nothing of the declared work in this stretch of the feed:
                // moved past, so it is not read again.
                lanes.sent(HOME, batch);
                continue;
            }
            List<String> refused = new ArrayList<>();
            handed += handed(batch, refused);
            lanes.sent(HOME, batch);
            refusedSaid(refused);
        }
        if (!ended[0]) {
            // Further behind than a round reads: the declared work is found in
            // the store instead, and the position goes to the head. Read on,
            // weeks of housekeeping would stand before the newest run for as
            // many rounds as it took, and every stream waits on the round.
            List<String> refused = new ArrayList<>();
            handed += lanes.caughtUpToTheHead(HOME, declared, SKEW, AT_ONCE,
                    batch -> handed(batch, refused));
            refusedSaid(refused);
        }
        return handed;
    }

    /**
     * Takes the work the tenant addressed to this place, stretch by stretch,
     * and lets go of what came with work that is over. Each batch is applied
     * before the next ask tells the tenant it was, so a link that drops
     * between the two hands the same batch again, and it applies once.
     *
     * <p>A tenant that hands no work leaves the round as it was, said: what
     * this place performs of its own still goes home.
     */
    private void taken() {
        String through = null;
        List<String> refused = new ArrayList<>();
        try {
            for (int read = 0; read < MOST_PER_ROUND; read++) {
                Lanes.Batch batch = RecordWire.decode(home.toPerform(through), Lanes.Batch.class);
                if (batch == null) {
                    break;
                }
                if (!batch.isEmpty()) {
                    refused.addAll(lanes.performing(HOME, batch).refused());
                }
                // Read to the end: the tenant answered from where it stands,
                // and that is where this ask said it stands.
                if (batch.cursor() == null || batch.cursor().equals(through)) {
                    break;
                }
                through = batch.cursor();
            }
            untaken = refused.isEmpty() ? null
                    : "this place could not apply " + refused.size() + " of it: " + refused;
        } catch (cloud.jengu.dbo.core.api.StoreUnreachableException away) {
            throw away;
        } catch (RuntimeException unanswered) {
            untaken = String.valueOf(unanswered.getMessage());
        }
        lanes.revoke();
    }

    /**
     * What of this place's work stays here, and why, or empty when all of it
     * goes. Said as one sentence, so that what changed is a different one.
     */
    String keptHere() {
        List<String> why = new ArrayList<>();
        if (nothingDeclared) {
            why.add("its tenant declares no step, so none of its work goes home");
        }
        if (unasked != null) {
            why.add("what it handed home before it marked stays unmarked, as its tenant did not"
                    + " say what it holds of it: " + unasked);
        }
        if (untaken != null) {
            why.add("the work its tenant addresses to it is not taken: " + untaken);
        }
        if (unbrought != null) {
            why.add("what its own work names and it let go of is not brought back from its "
                    + "tenant: " + unbrought);
        }
        if (unhanded != null) {
            why.add("what its tenant declined before it knew an internal record by its id alone"
                    + " is not handed again yet: " + unhanded);
        }
        Set<String> declined = marks == null ? Set.of() : marks.declinedTypes();
        if (!declined.isEmpty()) {
            why.add("records of " + declined + " stay here, as its tenant holds others of its own"
                    + " in their place");
        }
        if (!passedOver.isEmpty()) {
            why.add("runs of " + passedOver + " stay here, as its tenant declares no step of "
                    + (passedOver.size() == 1 ? "it" : "them"));
        }
        return String.join("; ", why);
    }

    /**
     * Brings back from the tenant what the work open here names and this
     * place let go of, as the tenant holds it as this place's: kept here while
     * the work is open, and let go of when it ends. A run naming a record the
     * tenant does not hold either ends failed, saying which.
     *
     * <p>A tenant that cannot answer fails nothing: the work waits, said, and
     * is brought once the tenant answers.
     */
    private void broughtBack() {
        java.util.Map<String, List<String>> wanted = lanes.unheldByOpenWork(AT_ONCE);
        if (wanted.isEmpty()) {
            unbrought = null;
            return;
        }
        try {
            Lanes.Batch brought = RecordWire.decode(
                    home.brought(List.copyOf(wanted.keySet())), Lanes.Batch.class);
            if (brought == null) {
                unbrought = "its tenant did not answer what it holds of it";
                return;
            }
            List<String> refused = lanes.broughtBack(HOME, brought, wanted).refused();
            unbrought = refused.isEmpty() ? null
                    : "this place could not apply " + refused.size() + " of it: " + refused;
        } catch (cloud.jengu.dbo.core.api.StoreUnreachableException away) {
            throw away;
        } catch (RuntimeException unanswered) {
            unbrought = String.valueOf(unanswered.getMessage());
        }
    }

    /**
     * Asks the tenant which of this place's unmarked records it holds, from
     * where the lane says the asking got to, and marks what it answers.
     *
     * <p>Marked before the lane moves, as a batch's answer is. A tenant that
     * cannot answer leaves the asking where it was, said, and the work goes
     * home as ever: what the tenant took since marking began is marked anyway.
     */
    private void askedOnce() {
        if (marks == null) {
            return;
        }
        String through = lanes.open(HOME).askedHeld();
        if (Lanes.Lane.ASKED_OF_ALL.equals(through)) {
            return;
        }
        try {
            for (int page = 0; page < ASKED_PER_ROUND; page++) {
                List<String> asked = marks.unmarkedAfter(through, ASKED_AT_ONCE);
                if (asked.isEmpty()) {
                    lanes.askedHeld(HOME, Lanes.Lane.ASKED_OF_ALL);
                    break;
                }
                lanes.heldAt(HOME, home.holds(asked));
                String last = asked.get(asked.size() - 1);
                through = last.substring(last.indexOf('/') + 1, last.lastIndexOf('/'));
                lanes.askedHeld(HOME, through);
            }
            unasked = null;
        } catch (cloud.jengu.dbo.core.api.StoreUnreachableException away) {
            throw away;
        } catch (RuntimeException unanswered) {
            unasked = String.valueOf(unanswered.getMessage());
        }
    }

    /**
     * Hands the tenant again, once, what its trail says it kept on this place,
     * from where the lane says the handing got to: the tenant takes what it
     * now would, and what it still declines is noted as any decline is.
     *
     * <p>Refused records are said rather than thrown: the round's own work
     * still goes, and they are not handed a third time.
     */
    private void handedAgainOnce() {
        if (marks == null) {
            return;
        }
        String through = lanes.open(HOME).handedAgain();
        if (Lanes.Lane.ASKED_OF_ALL.equals(through)) {
            return;
        }
        List<String> refused = new ArrayList<>();
        try {
            for (int page = 0; page < ASKED_PER_ROUND; page++) {
                List<String> declined = home.declined(through, AT_ONCE);
                if (declined.isEmpty()) {
                    lanes.handedAgain(HOME, Lanes.Lane.ASKED_OF_ALL);
                    break;
                }
                Lanes.Batch again = lanes.again(HOME, declined);
                if (!again.isEmpty()) {
                    handed(again, refused);
                }
                String last = declined.get(declined.size() - 1);
                through = last.substring(last.indexOf('/') + 1);
                lanes.handedAgain(HOME, through);
            }
            unhanded = refused.isEmpty() ? null
                    : "the tenant refused " + refused.size() + " of them: " + refused;
        } catch (cloud.jengu.dbo.core.api.StoreUnreachableException away) {
            throw away;
        } catch (RuntimeException unanswered) {
            unhanded = String.valueOf(unanswered.getMessage());
        }
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
        // Before the position moves: what the tenant holds is marked here as
        // held there, so retention on this place may let it go and never
        // lets go of anything else.
        lanes.heldAt(HOME, taken.held());
        if (marks != null) {
            marks.declined(taken.kept());
        }
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
