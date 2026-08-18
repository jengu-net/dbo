package cloud.jengu.dbo.logging;

/**
 * What the log emits, and at what level, decided once for the whole runtime.
 *
 * <p>Configured by system property so a deployment sets it the same way it
 * sets everything else, and read once at startup rather than per call.
 *
 * <ul>
 *   <li>{@code dbo.log.level} — {@code error|warn|info|debug|trace},
 *       default {@code info}. INFO is the production default deliberately: a
 *       box that says nothing while it works cannot be distinguished from a
 *       box that is stuck.</li>
 *   <li>{@code dbo.log.format} — {@code json|text}, default {@code json}.
 *       The deployment target is a cluster where something is always parsing;
 *       {@code text} is for reading over SSH on an edge box.</li>
 * </ul>
 */
public final class DboLogging {

    /** Ordered, so a configured level admits everything above it. */
    public enum Level {
        ERROR, WARN, INFO, DEBUG, TRACE
    }

    private static final Level LEVEL = parseLevel(
            System.getProperty("dbo.log.level", "info"));
    private static final boolean JSON = !"text".equalsIgnoreCase(
            System.getProperty("dbo.log.format", "json"));

    private DboLogging() {
    }

    public static boolean enabled(Level level) {
        return level.ordinal() <= LEVEL.ordinal();
    }

    public static boolean json() {
        return JSON;
    }

    public static Level level() {
        return LEVEL;
    }

    private static Level parseLevel(String raw) {
        try {
            return Level.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An unreadable level must not silence the log. Falling back to
            // INFO and saying so beats booting mute because of a typo.
            System.err.println("dbo.log.level=" + raw + " is not a level; using info");
            return Level.INFO;
        }
    }
}
