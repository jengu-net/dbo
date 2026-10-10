package cloud.jengu.dbo.runner.transport;

import java.util.Optional;

/**
 * The participation surface's vocabulary, in one place.
 *
 * <p>Every transport reads it: a door routes on it and a lane posts to it.
 * Two spellings of one verb is how a surface comes to answer 404 for a lane
 * that is mounted and working.
 *
 * <p>There is deliberately no verb for {@code tenant()} or {@code identity()}.
 * A remote lane knows both without asking — they are what it was built with —
 * and a round trip to be told what you already said is a round trip that can
 * fail.
 */
public enum LaneVerbs {

    POLL("poll"),
    CLAIM("claim"),
    CHECKPOINT("checkpoint"),
    MILESTONE("milestone"),
    RELEASED("released"),
    CLOSED("closed"),
    /** Closed with a result the tenant is asked to write — or ended because it would not. */
    COMMITTED("committed"),
    RELEASE_LAPSED("release-lapsed"),
    /** The supervisory verb: a closed run made claimable again, with its reason. */
    REOPEN("reopen"),
    DECLARE("declare"),
    /** Still here, with what the worker says about itself: written nowhere, extending nothing. */
    HEARTBEAT("heartbeat"),
    INTRODUCE("introduce"),
    WITHDRAW("withdraw"),
    ROUTES("routes"),
    INPUTS("inputs"),
    /** The same run's inputs, sealed to the asker's enrolment key: what a keyed participant gets. */
    SEALED("sealed"),
    /** The asker opened one sealed document — the access entry, from where the key was used. */
    OPENED("opened"),
    /**
     * One of the run's documents with its person put back together, sealed to
     * the asker: the reassembly performed at the tenant, where it is recorded.
     */
    IDENTIFIED("identified"),
    /**
     * A chunk of what the tenant replicates to a second place of itself, from
     * where this participant last acknowledged. Not a participation verb: it
     * is admitted by the place scope and served beside the lane.
     */
    FEED_READ("feed-read"),
    /** How far the place has applied: its position moves forward, never back. */
    FEED_ACK("feed-ack"),
    /**
     * The tenant's own declaration, as the tenant serves it now: what a place
     * of it serves, unchanged, so the two never drift.
     */
    DECLARATION("declaration");

    /** Who is asking, as a feed consumer: this participant's own cursor. */
    public static final String PARTICIPANT = "participant";
    /** What claims and reports — name, version, provider, scope. */
    public static final String IDENTITY = "identity";
    public static final String STEPS = "steps";
    /**
     * What the asker wants this lane bounded to, within what its credential
     * already covers. Narrowing only — see the handler.
     */
    public static final String ENTITLED_STEPS = "entitledSteps";
    public static final String LIMIT = "limit";
    public static final String RUN = "run";
    public static final String HOLD_FOR_MILLIS = "holdForMillis";
    public static final String COUNTS = "counts";
    public static final String MILESTONE_NAME = "milestone";
    public static final String REASON = "reason";
    /**
     * What kind of failure a release is — {@code record}, {@code unreachable},
     * {@code lapsed} or {@code unknown} — which routes it. Absent is a
     * hand-back that is not a failure.
     */
    public static final String FAILURE = "failure";
    public static final String DECLARED = "declared";
    public static final String STEP = "step";
    /**
     * The routees a participant reports behind it. No reporter travels with
     * them: the handler's lane stamps its own participant, so a router cannot
     * report routees for somebody else.
     */
    public static final String BEHIND = "behind";
    /** What a heartbeat carries: one JSON object, nested, namespaced by contributor. */
    public static final String STATISTICS = "statistics";
    /** Every answer's one field, so an empty answer is still a shape. */
    public static final String REFERENCE = "reference";
    public static final String RECIPIENTS = "recipients";
    public static final String AUTHOR = "author";
    public static final String HEAD = "head";
    /** The records a result asks the tenant to write. */
    public static final String WRITES = "writes";
    public static final String PREVIOUS = "previous";
    public static final String LINK = "link";
    public static final String SIGNATURE = "signature";
    public static final String RESULT = "result";
    /**
     * Why an identifying read is being asked for — an HL7 PurposeOfUse code.
     * Stated by the asker and recorded by the tenant; stating it is not
     * authorisation, and what it does is put the reason in the trail.
     */
    public static final String PURPOSE = "purpose";
    /** A refusal travels as a refusal: this flag, and why. */
    public static final String REFUSED = "refused";
    /**
     * Beside {@link #REFUSED}: the verb was refused because the run is no
     * longer the asker's — somebody acted on it since it was claimed. A
     * holder told this drops the work and reports nothing, where any other
     * refusal is the work going wrong.
     */
    public static final String LOST = "lost";

    /** Which of the tenant's feeds a place reads: {@link Place#RECORDS} or {@link Place#DEFINITIONS}. */
    public static final String DOMAIN = "domain";
    /** The types a place asks for; the tenant answers with no more than it replicates. */
    public static final String TYPES = "types";
    public static final String ITEMS = "items";
    /** A feed position, as opaque on the wire as it is in-process. */
    public static final String CURSOR = "cursor";
    public static final String DRAINED = "drained";
    /**
     * Beside a read of the definitions: leave out what the tenant took from
     * its face root, because the place takes its face from a root of its own.
     */
    public static final String WITHOUT_FACE = "withoutFace";
    /** A declaration's text, as the tenant was declared with it. */
    public static final String DECLARATION_TEXT = "declaration";

    private final String path;

    LaneVerbs(String path) {
        this.path = path;
    }

    /** The path segment under the lane's base path. */
    public String path() {
        return path;
    }

    public static Optional<LaneVerbs> ofPath(String path) {
        for (LaneVerbs verb : values()) {
            if (verb.path.equals(path)) {
                return Optional.of(verb);
            }
        }
        return Optional.empty();
    }
}
