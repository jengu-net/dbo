package cloud.jengu.dbo.work;

/**
 * Who holds a run now — the load-bearing field
 * (REQ-DBO-PROC-RUN-SAYS-WHO-HOLDS-IT).
 *
 * <p>Every other field on a run answers a question somebody asks after this
 * one. An operator opening a list is asking which work is waiting for a human,
 * and a list that answers anything else is answering a question nobody asked.
 */
public enum Holder {

    /** A step is executing. Nobody needs to do anything. */
    AUTOMATION,

    /**
     * Failed, and the next attempt is scheduled. Nobody's card: a queue that
     * collects transient faults stops being read
     * (REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS).
     */
    RETRY,

    /**
     * Automation is exhausted, or was never attempted. The state an operator
     * most wants, and the one a list of runs that worked silently omits.
     */
    PERSON,

    /** Done, or abandoned. Nothing is owed. */
    NOBODY;

    /**
     * Whether a run held this way is still owed anything — the one answer to
     * "is it over", which {@link Run#open} and the questions asked of many
     * runs at once both read, so the two cannot disagree about it.
     */
    public boolean owes() {
        return this != NOBODY;
    }

    /** Every holder that still owes something, in declaration order. */
    public static java.util.List<Holder> owing() {
        return java.util.Arrays.stream(values()).filter(Holder::owes).toList();
    }

    /** Lowercase: this crosses a wire and an envelope as a word, not a Java name. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The holder a run's status, eligibility and claimant come to.
     *
     * <p>Derived, never stored as a fact of its own: it answered three
     * questions in one word, and a word that is written beside the three can
     * disagree with them. Over is nobody's; a person holding it, or a run open
     * to people alone, is a person's; one held back is a retry; the rest is
     * automation's.
     */
    public static Holder derived(Status status, boolean automation, boolean heldByAPerson) {
        if (status == null || status.over()) {
            return NOBODY;
        }
        if (heldByAPerson || !automation) {
            return PERSON;
        }
        return status == Status.ON_HOLD ? RETRY : AUTOMATION;
    }

    static Holder of(String wire) {
        return wire == null ? NOBODY : valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
