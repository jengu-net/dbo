package cloud.jengu.dbo.sync.http;

import java.util.Optional;

/**
 * The replication surface's vocabulary, in one place.
 *
 * <p>Both ends read it, for the same reason the participation surface's does:
 * two spellings of one verb is how a surface answers 404 for something that is
 * mounted and working.
 */
public enum LanesVerbs {

    /** The lane with a peer, minting an epoch the first time. */
    OPEN("open"),
    /** What this appliance has told the peer, and what it heard back. */
    LANE("lane"),
    /** Records where the far side said it had reached. */
    MARK("mark"),
    /** What the peer does not have yet: runs since its cursor, and what they name. */
    OUTBOUND("outbound"),
    /** Accepted by the far side, so the next batch starts after it. */
    SENT("sent"),
    /** Applies what a peer sent. */
    APPLY("apply"),
    /** Removes what arrived for work that is over. */
    REVOKE("revoke");

    public static final String PEER = "peer";
    public static final String LIMIT = "limit";
    public static final String PROCESSES = "processes";
    public static final String TYPES = "types";
    public static final String MARKER = "marker";
    public static final String BATCH = "batch";
    /** Every answer's one field, so an empty answer is still a shape. */
    public static final String RESULT = "result";
    /** A refusal travels as a refusal: this flag, and why. */
    public static final String REFUSED = "refused";
    public static final String REASON = "reason";

    private final String path;

    LanesVerbs(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<LanesVerbs> ofPath(String path) {
        for (LanesVerbs verb : values()) {
            if (verb.path.equals(path)) {
                return Optional.of(verb);
            }
        }
        return Optional.empty();
    }
}
