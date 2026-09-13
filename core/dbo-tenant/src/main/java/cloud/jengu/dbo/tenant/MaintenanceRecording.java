package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Moving a tenant's data, as a record rather than as a log line.
 *
 * <p>A backup and a restore are the two operations an operator is most
 * likely to be asked about afterwards — whether one ran, when, how long it
 * took, whether it finished — and they were the two that left nothing behind
 * but a line in whatever log the node happened to be writing. A log is a
 * copy. This is the original, in the same history as everything else the
 * deployment did.
 *
 * <p><b>In the managing tenant, both of them.</b> A backup is work about the
 * tenant it copies and could live in that tenant's own store, but a restore
 * may be creating the tenant and has nowhere to write until it has nearly
 * finished. Splitting them by where they happen to be possible would put two
 * halves of one question in two places.
 *
 * <p><b>A deployment with nobody managing records nothing.</b> Not a failure
 * and not a fallback to somewhere invented: the same rule the serving sweep
 * already keeps, because recording what happened is not a condition of
 * doing it.
 */
final class MaintenanceRecording {

    /** Moving a tenant's data about: the archive that leaves and the one that lands. */
    static final String PROCESS = "dbo.tenant.archive";

    private final Supplier<Optional<Runs>> runs;
    private final String tenant;

    MaintenanceRecording(Supplier<Optional<Runs>> runs, String tenant) {
        this.runs = runs;
        this.tenant = tenant;
    }

    /** Nothing to write to, which is the ordinary case for a mechanic. */
    static MaintenanceRecording none() {
        return new MaintenanceRecording(Optional::empty, "unknown");
    }

    /**
     * One move, opened.
     *
     * <p>Keyed by the tenant and the step so an operator asking "what
     * happened to this tenant" is asking one question. A failure to record
     * is not a failure to move: this returns something that does nothing
     * rather than refusing the operation.
     */
    Recorded open(String step, String kind) {
        try {
            Runs held = runs.get().orElse(null);
            if (held == null) {
                return Recorded.NOTHING;
            }
            // Keyed the way the deployment's other histories are —
            // process, step, scope — because a key is what an operator asks
            // by, and one shaped differently is one nobody finds.
            Run run = held.pipeline(PROCESS, step, PROCESS + "/" + step + "/" + tenant);
            Recorded recorded = new Recorded(held, run);
            // After the run exists, and not as part of making it: a counter
            // name is validated as a path, and one this refused used to
            // discard the whole recording — the run was written and then
            // never closed, because the throw happened between the two.
            recorded.counted("kind_" + kind.replaceAll("[^A-Za-z0-9]", "_"));
            return recorded;
        } catch (RuntimeException unrecordable) {
            return Recorded.NOTHING;
        }
    }

    /** One move, in progress. */
    static final class Recorded {

        private static final Recorded NOTHING = new Recorded(null, null);

        private final Runs runs;
        private Run run;

        private Recorded(Runs runs, Run run) {
            this.runs = runs;
            this.run = run;
        }

        /** What this move was, as a count of one, and never at the cost of the run. */
        void counted(String name) {
            if (runs == null) {
                return;
            }
            try {
                run = runs.tally(run, Map.of(name, 1L));
            } catch (RuntimeException ignored) {
                // a name the store will not take is not a move that failed
            }
        }

        /**
         * How far in, for a restore somebody is waiting on.
         *
         * <p>Only the name. Where the step declares its milestones the store
         * computes the position over that order and refuses a name outside
         * it, so an executor that guessed at "three of five" could not make
         * the count disagree with the declaration.
         */
        void milestone(String name) {
            if (runs == null) {
                return;
            }
            try {
                run = runs.milestone(run, name, Map.of(),
                        java.time.Instant.now().plus(java.time.Duration.ofMinutes(5)));
            } catch (RuntimeException ignored) {
                // a milestone that cannot be written is not a move that failed
            }
        }

        void closed() {
            if (runs == null) {
                return;
            }
            try {
                run = runs.closed(run);
            } catch (RuntimeException ignored) {
                // as above: the bytes moved whatever the record says
            }
        }

        /**
         * Said as a release rather than a close, because a released run is
         * the one a supervisor sees as owed. What it carries is the shape of
         * the failure and never the step's own words about a payload.
         */
        void failed(String because) {
            if (runs == null) {
                return;
            }
            try {
                run = runs.released(run, because);
            } catch (RuntimeException ignored) {
                // nothing left to do about it here
            }
        }
    }
}
