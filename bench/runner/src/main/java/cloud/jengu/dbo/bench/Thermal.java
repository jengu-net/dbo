package cloud.jengu.dbo.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the one thing that silently invalidates a Raspberry Pi benchmark.
 *
 * <p>A Pi under sustained load throttles within minutes without real cooling,
 * and it does not announce it. Throughput drifts <b>downward inside a single
 * run</b>, so the first repetition beats the last and the median describes the
 * heatsink rather than the code. A benchmark that does not watch for this is
 * not measuring what it claims to measure.
 *
 * <p>{@code vcgencmd get_throttled} returns a bitmask whose low bits are live
 * conditions and whose bits 16-19 <em>latch</em>: they record that a condition
 * happened at some point since boot. The latched bits are the ones that matter
 * to a run, because a run is judged after it finishes.
 */
final class Thermal {

    private final Thread sampler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile double maxTempC;
    private volatile boolean throttled;
    private volatile boolean underVoltage;
    private volatile boolean available;
    /** The latched word as it stood before the run, or -1 for not read. */
    private volatile long latchedAtStart = -1;

    Thermal() {
        sampler = Thread.ofVirtual().name("thermal").unstarted(this::sample);
    }

    void start() {
        // What the board had ALREADY latched, before this run touched it.
        // Bits 16-19 record that a condition happened at some point since
        // BOOT, so a Pi that browned out once while somebody plugged a disk
        // in reports every run after it as invalid — and a bench that cries
        // wolf about its own results is one nobody reads. What a run is
        // answerable for is what changed while it ran.
        latchedAtStart = latched();
        sampler.start();
    }

    /**
     * Stoppable whether or not it was ever started.
     *
     * <p>The run stops this from a {@code finally}, and a failure before the
     * sampler starts — provisioning a tenant, above all — reaches that
     * {@code finally} with an unstarted thread. Joining one throws, and the
     * throw REPLACES the exception that was already on its way out: the bench
     * then reports that a thread was not started, at the one moment somebody
     * needs to know why a tenant would not come up.
     */
    void stop() {
        running.set(false);
        if (sampler.getState() == Thread.State.NEW) {
            return;
        }
        try {
            sampler.join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sample() {
        while (running.get()) {
            readOnce();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        readOnce();
    }

    private void readOnce() {
        String throttleRaw = run("vcgencmd", "get_throttled");
        if (throttleRaw == null) {
            return;                       // not a Pi, or no userland; recorded as unavailable
        }
        available = true;
        int eq = throttleRaw.indexOf('=');
        if (eq >= 0) {
            try {
                long value = Long.decode(throttleRaw.substring(eq + 1).trim());
                // Against what was latched before, not against zero.
                long since = latchedAtStart < 0 ? value : value & ~latchedAtStart;
                underVoltage |= ((since >> 16) & 1) == 1;
                throttled |= ((since >> 17) & 1) == 1 || ((since >> 18) & 1) == 1;
            } catch (NumberFormatException ignored) {
                // a mask we cannot read is not a mask we may assume is clean,
                // but neither is it evidence of throttling; leave the flags
            }
        }
        String temp = run("vcgencmd", "measure_temp");
        if (temp != null) {
            String digits = temp.replaceAll("[^0-9.]", "");
            if (!digits.isEmpty()) {
                double c = Double.parseDouble(digits);
                if (c > maxTempC) {
                    maxTempC = c;
                }
            }
        }
    }

    /** The latched word alone, for the before-and-after comparison. */
    private static long latched() {
        String raw = run("vcgencmd", "get_throttled");
        if (raw == null) {
            return -1;
        }
        int eq = raw.indexOf('=');
        if (eq < 0) {
            return -1;
        }
        try {
            return Long.decode(raw.substring(eq + 1).trim());
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    private static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                return line;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True only when the machine could be watched AND stayed within its
     * budget.
     *
     * <p>Unavailable counts as not clean, which is the uncomfortable answer
     * and the right one: on a machine with no {@code vcgencmd} — a laptop, a
     * CI container — nothing can say whether the numbers were taken while it
     * was throttling. That is fine for developing the runner and disqualifying
     * for publishing a figure, so a run off the reference hardware comes back
     * marked invalid rather than quietly quotable.
     */
    boolean clean() {
        return available && !throttled && !underVoltage;
    }

    boolean available() {
        return available;
    }

    String json() {
        return Json.object(
                Json.field("available", available),
                Json.field("maxSocTempC", maxTempC),
                Json.field("throttledSinceBoot", throttled),
                Json.field("underVoltageSinceBoot", underVoltage));
    }
}
