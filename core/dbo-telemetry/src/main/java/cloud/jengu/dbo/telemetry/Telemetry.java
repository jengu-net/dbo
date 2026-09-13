package cloud.jengu.dbo.telemetry;

import java.time.Duration;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Where numbers about this node go.
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

    /**
     * A document this node rendered, sent as {@code signal}, answering
     * whether the collector took it.
     *
     * <p><b>Why an exporter carries this at all.</b> Counters are emitted a
     * number at a time and an exporter batches them; a trace is not — it is
     * projected from records that already exist, in whatever shape the
     * protocol wants, by the part of the runtime that can read them. All an
     * exporter adds is somewhere to put it and the credentials to get there,
     * which is exactly what a caller with the records does not have.
     *
     * <p>Refusing is the default, so an exporter that only counts is
     * complete, and a caller reads the answer rather than assuming: a
     * document that did not land has not been reported and the records it
     * came from are still there to render again.
     */
    default boolean sent(String document, String signal) {
        return false;
    }

    /**
     * Everything held, posted now, answering whether it landed.
     *
     * <p>An exporter batches on an interval because a post per measurement
     * would cost more than the thing being measured. That is right while a
     * process keeps running and wrong at the end of one: a short-lived
     * process — a benchmark, a job, a one-shot import — can do all of its
     * work and exit inside a single interval, and every number it took goes
     * with it. Nothing says so, which is the worst part: the collector shows
     * no gap, because a series that never arrived looks like a series nobody
     * emitted.
     *
     * <p>Refusing is the default, so an exporter that holds nothing is
     * complete and a caller that flushes costs nothing where there is
     * nothing to flush.
     */
    default boolean flushed() {
        return false;
    }

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
