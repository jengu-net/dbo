package cloud.jengu.dbo.work;

/**
 * Where a run stands in its life, in the words a FHIR {@code Task} already
 * uses for it.
 *
 * <p>One of three facts a run keeps apart. Who is executing it is the
 * claimant, and who may take it next is its eligibility; this says only
 * whether the work is waiting, being done, held back for a while, or over —
 * and if over, how. Done and abandoned used to be one word, and a list of
 * finished work could not tell the two apart.
 */
public enum Status {

    /** Open and unclaimed: on the list, for whoever may take it. */
    READY("ready"),

    /** Claimed: somebody or something is executing it now. */
    IN_PROGRESS("in-progress"),

    /** Open and unclaimed, and not to be taken before its not-before. */
    ON_HOLD("on-hold"),

    /** Done. */
    COMPLETED("completed"),

    /** Ended because what it would do was refused, and trying again would be refused alike. */
    FAILED("failed"),

    /** Ended without being done. */
    CANCELLED("cancelled");

    private final String wire;

    Status(String wire) {
        this.wire = wire;
    }

    /** The word, as a Task's status spells it. */
    public String wire() {
        return wire;
    }

    /** Whether nothing more is owed: done, refused or abandoned. */
    public boolean over() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** The status a word names, or null for none. */
    public static Status of(String wire) {
        if (wire == null) {
            return null;
        }
        for (Status status : values()) {
            if (status.wire.equals(wire)) {
                return status;
            }
        }
        throw new IllegalArgumentException("no run status is called '" + wire + "'");
    }
}
