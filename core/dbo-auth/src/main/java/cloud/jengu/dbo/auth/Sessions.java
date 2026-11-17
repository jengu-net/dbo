package cloud.jengu.dbo.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Where signing in and out is recorded: each session as a run of its own, so
 * what the authority reads to sign somebody in is done for it, and the
 * session's end is on the trail however it ended.
 *
 * <p>Supplied by whatever keeps the tenant's runs, because this bundle keeps
 * none. Without one, nothing is recorded and no session is ever found ended.
 */
public interface Sessions {

    /** The process a sign-in is recorded under. */
    String PROCESS = "dbo.auth";

    /** The step: one run per session, from signing in to its end. */
    String SIGN_IN = "sign-in";

    /** How a session ended. */
    enum Ending {
        SIGNED_OUT("signed out"),
        TIMED_OUT("timed out"),
        REVOKED("revoked");

        private final String said;

        Ending(String said) {
            this.said = said;
        }

        public String said() {
            return said;
        }
    }

    /** Why a sign-in was refused. Never the secret, nor the login offered. */
    enum Refusal {
        NO_SUCH_LOGIN("no such login"),
        WRONG_SECRET("wrong secret"),
        ASSERTION_NOT_ACCEPTED("the broker's assertion was not accepted"),
        NO_SUCH_PERSON("nobody here with that identity"),
        NOTHING_GRANTED("nothing granted");

        private final String said;

        Refusal(String said) {
            this.said = said;
        }

        public String said() {
            return said;
        }
    }

    /**
     * A session's run, as the authority finds it: its key, or null where
     * nothing records sessions.
     */
    record Held(String run) {}

    /**
     * A person signed in: the session's run, opened for it and lasting until
     * {@code until} unless it is kept.
     *
     * @return the run's key, or null where nothing records sessions
     */
    String signedIn(String sid, String personId, String clientId, Instant until);

    /**
     * A session kept by a refresh. A session signed in before its sign-in
     * was recorded is opened here.
     *
     * @return its run, or empty when the session has ended and may not be
     *         kept
     */
    Optional<Held> kept(String sid, String personId, String clientId, Instant until);

    /**
     * A session's run as it stands, kept no longer.
     *
     * @return its run, which has none for a session signed in before its
     *         sign-in was recorded, or empty when the session has ended
     */
    Optional<Held> found(String sid);

    /**
     * A session ended.
     *
     * @param by who ended it, or null when nobody did
     * @return whether this ended it, rather than finding it ended or unknown
     */
    boolean ended(String sid, Ending how, String by);

    /** A sign-in refused, for the client that asked. */
    void refused(String clientId, Refusal why);

    /**
     * Every session nobody kept past its deadline, ended as timed out.
     *
     * @return how many were ended
     */
    int lapse(Instant now);

    /** Nothing recorded, and no session ever found ended. */
    Sessions NONE = new Sessions() {
        @Override
        public String signedIn(String sid, String personId, String clientId, Instant until) {
            return null;
        }

        @Override
        public Optional<Held> kept(String sid, String personId, String clientId,
                Instant until) {
            return Optional.of(new Held(null));
        }

        @Override
        public Optional<Held> found(String sid) {
            return Optional.of(new Held(null));
        }

        @Override
        public boolean ended(String sid, Ending how, String by) {
            return false;
        }

        @Override
        public void refused(String clientId, Refusal why) {
        }

        @Override
        public int lapse(Instant now) {
            return 0;
        }
    };
}
