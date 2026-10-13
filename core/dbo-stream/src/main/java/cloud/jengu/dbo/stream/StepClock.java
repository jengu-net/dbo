package cloud.jengu.dbo.stream;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import dev.dbos.transact.execution.SchedulerService;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The clock a tenant's self-starting steps are fired by.
 */
public final class StepClock {

    /**
     * The shortest cadence a step may start on. Each tick is a run kept as
     * history, and a cadence below this is a feed nobody can read.
     */
    public static final Duration FASTEST = Duration.ofMinutes(1);

    /** How many consecutive ticks are looked at for the shortest gap between two. */
    private static final int LOOKED_AT = 16;

    private StepClock() {
    }

    /**
     * The ticks a rule had after one instant and up to another, inclusive:
     * how many, and the last of them.
     *
     * @param count how many ticks fell in the span, up to the cap
     * @param last  the last of them, or null when none did
     */
    public record Ticks(long count, java.time.Instant last) {
    }

    /** At most this many missed ticks are counted; a count at it means at least. */
    public static final long COUNTED = 100_000;

    /** The ticks of {@code rule} after {@code after} and up to {@code upTo}. */
    public static Ticks between(String rule, java.time.Instant after, java.time.Instant upTo) {
        ExecutionTime ticks = ExecutionTime.forCron(SchedulerService.CRON_PARSER.parse(rule));
        ZonedDateTime at = after.atZone(ZoneOffset.UTC);
        long count = 0;
        java.time.Instant last = null;
        while (count < COUNTED) {
            ZonedDateTime next = ticks.nextExecution(at).orElse(null);
            if (next == null || next.toInstant().isAfter(upTo)) {
                break;
            }
            count++;
            last = next.toInstant();
            at = next;
        }
        return new Ticks(count, last);
    }

    /** The first tick of {@code rule} after {@code after}, or null when it never fires again. */
    public static java.time.Instant next(String rule, java.time.Instant after) {
        return ExecutionTime.forCron(SchedulerService.CRON_PARSER.parse(rule))
                .nextExecution(after.atZone(ZoneOffset.UTC))
                .map(ZonedDateTime::toInstant).orElse(null);
    }

    /**
     * Refuses a calendar rule the scheduler would not fire, or would fire
     * more often than {@link #FASTEST}.
     *
     * <p>Read by the parser that fires it, so the dialect is the scheduler's
     * own: six fields, from seconds.
     */
    public static void refuse(String rule) {
        Cron cron;
        try {
            cron = SchedulerService.CRON_PARSER.parse(rule).validate();
        } catch (IllegalArgumentException wrong) {
            throw new IllegalArgumentException("'every' is '" + rule + "', which is not a "
                    + "calendar rule of six fields from seconds, such as '0 */5 * * * *': "
                    + wrong.getMessage(), wrong);
        }
        ExecutionTime ticks = ExecutionTime.forCron(cron);
        ZonedDateTime tick = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime first = ticks.nextExecution(tick).orElse(null);
        if (first == null) {
            throw new IllegalArgumentException("'every' is '" + rule + "', which never fires");
        }
        tick = first;
        for (int i = 0; i < LOOKED_AT; i++) {
            ZonedDateTime next = ticks.nextExecution(tick).orElse(null);
            if (next == null) {
                return;
            }
            if (Duration.between(tick, next).compareTo(FASTEST) < 0) {
                throw new IllegalArgumentException("'every' is '" + rule + "', which fires "
                        + Duration.between(tick, next).toSeconds() + " seconds apart, and a "
                        + "step starts at most once a minute");
            }
            tick = next;
        }
    }
}
