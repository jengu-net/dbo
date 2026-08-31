package cloud.jengu.dbo.telemetry;

import java.time.Duration;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Where numbers about this node go (#161).
 *
 * <p><b>Why a seam of our own rather than a metrics library's API.</b> The
 * engine's rule is that it carries no dependency it cannot justify aloud, and
 * an interface with three methods is not a dependency. It also keeps the
 * vocabulary ours: what may be measured and how it may be labelled is a
 * decision this repository has made and enforces ({@link Label}), not one a
 * library makes for it.
 *
 * <p><b>The emitter always runs; only the exporter is absent.</b> A collector
 * exists in a deployment and not on a developer's machine, so emission that
 * switched itself off without one would be a code path exercised nowhere but
 * production — which is the last place this codebase wants to first run
 * something. {@link #none()} is a real implementation that discards, so the
 * call, its labels and their construction happen everywhere; what differs
 * between a laptop and a deployment is only where the numbers go.
 *
 * <p><b>What this is not.</b> It is lossy by design and nothing decides
 * anything on it. A run's state is a record in a tenant's store, and a reader
 * that needs to know whether work is claimable asks the store — never this.
 *
 * <p>Found through {@link ServiceLoader}, the way the logging binding is, so
 * a deployment installs an exporter and the code that emits does not change.
 */
public interface Telemetry {

    /** Something happened, once or {@code delta} times. */
    void counted(String name, long delta, Labels labels);

    /** Something took this long — a distribution, not a gauge of the last one. */
    void observed(String name, Duration took, Labels labels);

    /** Something is currently this many. Absence of a value is not zero. */
    void level(String name, long value, Labels labels);

    /** Discards everything, and is the default rather than a fallback. */
    static Telemetry none() {
        return Discarding.INSTANCE;
    }

    /**
     * The exporter this runtime has, or one that discards.
     *
     * <p>Resolution failure is not an outage: a node whose exporter cannot be
     * loaded keeps serving and stops reporting, because the alternative is a
     * store that refuses to run when a monitoring stack is missing.
     */
    static Telemetry installed() {
        try {
            return ServiceLoader.load(Telemetry.class).findFirst().orElse(Discarding.INSTANCE);
        } catch (RuntimeException | ServiceConfigurationError unavailable) {
            return Discarding.INSTANCE;
        }
    }

    /** The default. Not a null object standing in for a mistake — the ordinary case. */
    final class Discarding implements Telemetry {

        private static final Discarding INSTANCE = new Discarding();

        private Discarding() {
        }

        @Override
        public void counted(String name, long delta, Labels labels) {
        }

        @Override
        public void observed(String name, Duration took, Labels labels) {
        }

        @Override
        public void level(String name, long value, Labels labels) {
        }
    }
}
