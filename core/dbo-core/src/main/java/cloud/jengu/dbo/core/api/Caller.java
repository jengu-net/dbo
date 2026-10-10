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
    /** Whether the run in progress is the store's own machinery rather than work. */
    private static final ThreadLocal<Boolean> MACHINERY = new ThreadLocal<>();

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
        MACHINERY.remove();
    }

    /** The run in progress on this thread, or null outside one. */
    public static String run() {
        return RUN.get();
    }

    public static void clearRun() {
        RUN.remove();
        MACHINERY.remove();
    }

    /**
     * The run in progress on this thread until the answer is closed, and then
     * whichever run was in progress before it: a pass that calls into work of
     * its own hands the thread back as it found it.
     */
    public static InRun underRun(String runKey) {
        return marked(runKey, null);
    }

    /**
     * The same, for the store's own machinery: what it writes names the run,
     * and what it reads is not a disclosure. A stream reads what it is about
     * to write to and a configuration pass what it is about to replace; nobody
     * is handed anything, and recording those reads would fill the trail with
     * the machinery's own bookkeeping.
     */
    public static InRun writingFor(String runKey) {
        return marked(runKey, Boolean.TRUE);
    }

    /**
     * Whether a read now is made for a piece of work, and so is recorded as a
     * disclosure: there is a run, and it is not the machinery's own.
     */
    public static boolean readingForWork() {
        return RUN.get() != null && !Boolean.TRUE.equals(MACHINERY.get());
    }

    private static InRun marked(String runKey, Boolean machinery) {
        String outer = RUN.get();
        Boolean outerMachinery = MACHINERY.get();
        RUN.set(runKey);
        MACHINERY.set(machinery);
        return () -> {
            if (outer == null) {
                RUN.remove();
            } else {
                RUN.set(outer);
            }
            if (outerMachinery == null) {
                MACHINERY.remove();
            } else {
                MACHINERY.set(outerMachinery);
            }
        };
    }

    /** A run marked in progress, until closed. */
    @FunctionalInterface
    public interface InRun extends AutoCloseable {
        @Override
        void close();
    }

    /** Never null: outside an authenticated request the actor is "system". */
    public static String current() {
        String actor = CURRENT.get();
        return actor != null ? actor : "system";
    }

    /**
     * The actor a serving surface set, or null outside an authenticated
     * request — where {@link #current()} says "system".
     *
     * <p>For recording who holds something rather than who did something. An
     * audit entry with no request behind it is honestly the system's; a claim
     * with none behind it is held by no client, and recording "system" would
     * make it a name a credential could be issued under.
     */
    public static String authenticated() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        ON_BEHALF_OF.remove();
        RUN.remove();
        MACHINERY.remove();
    }
}
