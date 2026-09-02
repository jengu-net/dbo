package cloud.jengu.dbo.runner.http;

import java.util.Optional;

/**
 * The participation surface's vocabulary, in one place.
 *
 * <p>Both ends read it: the handler routes on it and the client posts to it.
 * Two spellings of one verb is how a surface comes to answer 404 for a lane
 * that is mounted and working, and the same argument that gave the edge
 * channel one handshake codec gives this one enum.
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
    RELEASE_LAPSED("release-lapsed"),
    DECLARE("declare"),
    INTRODUCE("introduce"),
    WITHDRAW("withdraw"),
    ROUTES("routes"),
    INPUTS("inputs"),
    /** The same run's inputs, sealed to the asker's enrolment key: what a keyed participant gets. */
    SEALED("sealed"),
    /** The asker opened one sealed document — the access entry, from where the key was used. */
    OPENED("opened");

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
    public static final String DECLARED = "declared";
    public static final String STEP = "step";
    /**
     * The trackables a participant reports behind it. No observer
     * travels with them: the handler's lane stamps its own participant, so
     * an attestation cannot be forged by the side making the claim.
     */
    public static final String BEHIND = "behind";
    /** Every answer's one field, so an empty answer is still a shape. */
    public static final String REFERENCE = "reference";
    public static final String HEAD = "head";
    public static final String PREVIOUS = "previous";
    public static final String LINK = "link";
    public static final String SIGNATURE = "signature";
    public static final String RESULT = "result";
    /** A refusal travels as a refusal: this flag, and why. */
    public static final String REFUSED = "refused";

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
