package cloud.jengu.dbo.work;

/**
 * How a run knows it is finished (REQ-DBO-PROC-RUN-KINDS).
 *
 * <p>The distinction is not bookkeeping. A reconciler modelled as a pipeline is
 * a run that never ends, and its needs-a-person queue fills with work that is
 * merely still converging — which is how a queue stops being read.
 */
public enum RunKind {

    /** Closes when every item is terminal: a delivery, an import, an ingest. */
    PIPELINE,

    /**
     * Closes when the world agrees: a bring-up, a retention pass, a
     * configuration application, a stream catching up with its upstream. One
     * durable run per (process, scope), checkpointed — the operator's question
     * is about the thing, not about the pass.
     */
    SWEEP;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static RunKind of(String wire) {
        return valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
