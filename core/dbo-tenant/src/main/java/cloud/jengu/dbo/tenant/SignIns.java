package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Sessions;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A tenant's sessions as its runs: each work that lasts from signing in to
 * its end, over the person signed in and asked for by the client they signed
 * in through.
 *
 * <p>The run store is asked for at each use rather than held, because the
 * authority is built before the tenant's engine is.
 */
final class SignIns implements Sessions {

    private static final Logger LOG = LoggerFactory.getLogger(SignIns.class);

    private final String tenant;
    private final Supplier<ObjectStore> store;

    SignIns(String tenant, Supplier<ObjectStore> store) {
        this.tenant = tenant;
        this.store = store;
    }

    @Override
    public String signedIn(String sid, String personId, String clientId, Instant until) {
        return runs().held(PROCESS, SIGN_IN, sid, "person", "Person/" + personId, clientId,
                until).key();
    }

    @Override
    public Optional<Held> kept(String sid, String personId, String clientId, Instant until) {
        Runs runs = runs();
        Optional<Run> found = runs.byKey(key(sid));
        if (found.isEmpty()) {
            return Optional.of(new Held(signedIn(sid, personId, clientId, until)));
        }
        return runs.kept(found.get(), until).map(run -> new Held(run.key()));
    }

    @Override
    public Optional<Held> found(String sid) {
        Optional<Run> found = runs().byKey(key(sid));
        if (found.isEmpty()) {
            return Optional.of(new Held(null));
        }
        return found.get().status().over() ? Optional.empty()
                : Optional.of(new Held(found.get().key()));
    }

    @Override
    public boolean ended(String sid, Ending how, String by) {
        Runs runs = runs();
        Optional<Run> found = runs.byKey(key(sid));
        if (found.isEmpty() || found.get().status().over()) {
            return false;
        }
        try {
            runs.ended(found.get(), how == Ending.REVOKED ? Status.CANCELLED : Status.COMPLETED,
                    by, how.said());
            return true;
        } catch (IllegalStateException endedMeanwhile) {
            return false;
        }
    }

    @Override
    public void refused(String clientId, Refusal why) {
        try {
            runs().turnedAway(PROCESS, SIGN_IN, clientId, why.said());
        } catch (RuntimeException unrecorded) {
            // The person is answered either way: a refusal the trail could not
            // take is still a refusal, and failing the page would tell them
            // nothing they can act on.
            LOG.warn("tenant {}: a refused sign-in was not recorded: {}", tenant,
                    unrecorded.toString());
        }
    }

    @Override
    public int lapse(Instant now) {
        Runs runs = runs();
        int ended = 0;
        for (Run session : runs.overdue(PROCESS, SIGN_IN, now)) {
            try {
                runs.ended(session, Status.COMPLETED, null, Ending.TIMED_OUT.said()
                        + ", not refreshed by " + session.assignment().until());
                ended++;
            } catch (IllegalStateException endedMeanwhile) {
                // signed out or revoked since it was found
            }
        }
        return ended;
    }

    private Runs runs() {
        ObjectStore runs = store.get();
        if (runs == null) {
            throw new IllegalStateException("tenant " + tenant + " keeps no runs yet");
        }
        return new Runs(runs);
    }

    private static String key(String sid) {
        return PROCESS + "/" + SIGN_IN + "/" + sid;
    }
}
