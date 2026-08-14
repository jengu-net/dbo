package cloud.jengu.dbo.core.api;

/**
 * The per-request caller identity (§15.1): set by the serving surface after
 * token validation, read by the audit layer, cleared when the request ends.
 * Engine APIs stay context-free; accountability is a cross-cutting concern
 * and this is its single, deliberately tiny seam.
 */
public final class Caller {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Caller() {
    }

    public static void set(String actor) {
        CURRENT.set(actor);
    }

    /** Never null: outside an authenticated request the actor is "system". */
    public static String current() {
        String actor = CURRENT.get();
        return actor != null ? actor : "system";
    }

    public static void clear() {
        CURRENT.remove();
    }
}
