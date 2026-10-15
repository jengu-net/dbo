package cloud.jengu.dbo.work;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

/**
 * What tells a tenant that a tick of one of its self-starting steps is due.
 *
 * <p>The store's own is DBOS, over the tenant's database. A framework hosting the store may
 * bring its own, and decides only when a tick is due: a tick becomes a run keyed by the step
 * and the tick's instant, so a scheduler that tells every node finds one run; the store reads
 * whether a step is paused when the tick arrives, and reckons what was missed from the ticks
 * it holds.
 *
 * <p>A rule is six fields from seconds — second, minute, hour, day of month,
 * month, day of week — and has already been checked to fire at most once a
 * minute.
 */
public interface StepScheduler {

    /**
     * Opens one tenant's clock.
     *
     * @param tenant   the tenant's code
     * @param database the tenant's own database, for a scheduler that keeps
     *                 its state beside the tenant's; one that does not ignores it
     * @param tick     what a due tick is handed to
     */
    Clock open(String tenant, DataSource database, Tick tick);

    /** A due tick. */
    @FunctionalInterface
    interface Tick {
        /**
         * A tick of {@code step}, scheduled for {@code at}. Called once or
         * more per tick, on any node; returns once the run of it is authored.
         */
        void due(String step, Instant at);
    }

    /** One tenant's clock. */
    interface Clock extends AutoCloseable {

        /**
         * Brings the clock into line with the steps declared now, as step code
         * to rule: a step not named stops, a step named starts or changes.
         */
        void apply(Map<String, String> rules);

        /** Stops the clock; no tick is handed over after this returns. */
        @Override
        void close();
    }
}
