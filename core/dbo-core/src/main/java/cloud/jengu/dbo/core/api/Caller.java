package cloud.jengu.dbo.core.api;

/**
 * The per-request caller identity (§15.1): set by the serving surface after
 * token validation, read by the audit layer, cleared when the request ends.
 * Engine APIs stay context-free; accountability is a cross-cutting concern
 * and this is its single, deliberately tiny seam.
 */
public final class Caller {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> ON_BEHALF_OF = new ThreadLocal<>();
    private static final ThreadLocal<String> RUN = new ThreadLocal<>();

    private Caller() {
    }

    public static void set(String actor) {
        CURRENT.set(actor);
        ON_BEHALF_OF.remove();
    }

    /** §16.4: a process acting in the name of a human — both are recorded. */
    public static void setChain(String actor, String onBehalfOf) {
        CURRENT.set(actor);
        ON_BEHALF_OF.set(onBehalfOf);
    }

    /** The human a process acts for, or null when the actor acts as itself. */
    public static String onBehalfOf() {
        return ON_BEHALF_OF.get();
    }

    /**
     * The run this work belongs to, so what a run did is navigable from the
     * run rather than only from the records it touched
     * (REQ-DBO-PROC-TRACE-JOIN).
     *
     * <p>Ambient for the same reason the actor is: a step calls the store the
     * way anything else does, and threading a run through every write would put
     * a process concern into every signature the engine has.
     */
    public static void setRun(String runKey) {
        RUN.set(runKey);
    }

    /** The run in progress on this thread, or null outside one. */
    public static String run() {
        return RUN.get();
    }

    public static void clearRun() {
        RUN.remove();
    }

    /** Never null: outside an authenticated request the actor is "system". */
    public static String current() {
        String actor = CURRENT.get();
        return actor != null ? actor : "system";
    }

    public static void clear() {
        CURRENT.remove();
        ON_BEHALF_OF.remove();
        RUN.remove();
    }
}
