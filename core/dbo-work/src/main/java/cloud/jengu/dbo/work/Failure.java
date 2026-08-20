package cloud.jengu.dbo.work;

/**
 * Which kind of failure this is, and therefore who it belongs to
 * (REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS).
 *
 * <p>A record that is wrong is a person's job. A store that is unavailable is a
 * retry and nobody's card. Only the first makes work: a queue that collects
 * transient faults becomes a graveyard, and a graveyard stops being read — after
 * which the one card that mattered is in it.
 *
 * <p>Classified from the <b>exception class</b> rather than from a message,
 * because the line is already drawn in the type system and a parallel taxonomy
 * would drift from it. A caller that knows better says so explicitly; a caller
 * that does not gets the safe reading.
 */
public enum Failure {

    /** The thing being processed is wrong, and re-running will not change that. */
    RECORD,

    /** Something the run depends on was unavailable, and may not be next time. */
    TRANSIENT;

    /**
     * The class of a thrown failure.
     *
     * <p>An {@link IllegalArgumentException} — which is what a refusal to accept
     * a payload arrives as here — is the record's. Anything else is treated as
     * transient, which is the safe direction: a transient fault wrongly called a
     * record fault puts a card in front of a person who can do nothing about it,
     * and does it once per occurrence.
     */
    public static Failure of(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                return RECORD;
            }
        }
        return TRANSIENT;
    }

    /** Who a failure of this class belongs to. */
    public Holder holder() {
        return this == RECORD ? Holder.PERSON : Holder.RETRY;
    }

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
