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

    /** Lowercase: this crosses a wire and an envelope as a word, not a Java name. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static Holder of(String wire) {
        return wire == null ? NOBODY : valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
