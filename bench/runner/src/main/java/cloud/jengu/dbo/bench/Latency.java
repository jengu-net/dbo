package cloud.jengu.dbo.bench;

import java.util.Arrays;

/**
 * Samples, and the percentiles that describe them.
 *
 * <p>Percentiles rather than a mean, because a mean over a latency
 * distribution says almost nothing: one stalled write in a thousand moves it
 * by a rounding error while being exactly the event a caller notices. p99 is
 * the number that decides whether a deployment feels quick.
 *
 * <p>Every sample is kept rather than summarised as it arrives. A run is
 * minutes and a few million longs, which is cheaper than the argument about
 * whether an approximate histogram lost the tail.
 */
final class Latency {

    private long[] samples = new long[1024];
    private int count;
    private long started = -1;
    private long ended;

    void record(long nanos) {
        if (started < 0) {
            started = System.nanoTime();
        }
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count++] = nanos;
        ended = System.nanoTime();
    }

    int count() {
        return count;
    }

    /** Operations per second over the wall clock this recorder covered. */
    double perSecond() {
        double seconds = (ended - started) / 1e9;
        return seconds <= 0 ? 0 : count / seconds;
    }

    /** @param q 0.5 for the median, 0.99 for the tail */
    double millis(double q) {
        if (count == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1e6;
    }

    double maxMillis() {
        return millis(1.0);
    }

    String json() {
        return Json.object(
                Json.field("count", count),
                Json.field("perSecond", round(perSecond())),
                Json.field("p50", round(millis(0.5))),
                Json.field("p95", round(millis(0.95))),
                Json.field("p99", round(millis(0.99))),
                Json.field("max", round(maxMillis())));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
