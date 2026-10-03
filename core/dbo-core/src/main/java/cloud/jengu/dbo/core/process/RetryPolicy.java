package cloud.jengu.dbo.core.process;

import java.util.List;

/**
 * Which of a step's failures are worth another attempt by automation, after
 * how long, and how many times.
 *
 * <p><b>Declared, because only the step knows.</b> A fault the step says will
 * pass returns its task to the list held back for {@code after}, open to
 * automation again, and counted; past {@code attempts} it goes to a person.
 * Everything else a failure can be is not this policy's: a fault in the record
 * ends the task, and any other goes to people, because a fault nobody said
 * would pass is what somebody should see rather than what a machine should
 * keep trying.
 *
 * @param on       the faults that pass: {@code unreachable}, the store or the
 *                 system it depends on not answering, and {@code lapsed}, a
 *                 claim nobody extended. A lapse is not on by default: an
 *                 executor that died said nothing about why
 * @param after    how long the task is held back, as an ISO-8601 duration
 * @param attempts how many times automation is given it again before a person
 *                 is
 */
public record RetryPolicy(List<String> on, String after, int attempts) {

    /** The faults a step may declare as passing. */
    public static final List<String> FAULTS = List.of("unreachable", "lapsed");

    public RetryPolicy {
        on = on == null ? List.of() : List.copyOf(on);
        for (String fault : on) {
            if (!FAULTS.contains(fault)) {
                throw new IllegalArgumentException("a step retries on " + FAULTS
                        + ", and '" + fault + "' is not one of them");
            }
        }
        if (after == null) {
            after = "PT0S";
        }
        try {
            if (java.time.Duration.parse(after).isNegative()) {
                throw new IllegalArgumentException("a retry is held back for a while, not "
                        + "before it failed: " + after);
            }
        } catch (java.time.format.DateTimeParseException notADuration) {
            throw new IllegalArgumentException("a retry's 'after' is an ISO-8601 duration, "
                    + "such as PT1M, and '" + after + "' is not one", notADuration);
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("a step that retries gives automation at least "
                    + "one more attempt, and this one says " + attempts);
        }
    }

    /** Whether a fault of this kind is one the step said will pass. */
    public boolean passes(String fault) {
        return on.contains(fault);
    }

    /** How long a task is held back, as a duration. */
    public java.time.Duration delay() {
        return java.time.Duration.parse(after);
    }
}
