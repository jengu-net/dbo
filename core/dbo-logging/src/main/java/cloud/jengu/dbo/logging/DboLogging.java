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
 *   <li>{@code dbo.log.level.<prefix>} — the level for loggers under one name,
 *       longest prefix winning. {@code dbo.log.level.ca.uhn.fhir=info} says it
 *       for the FHIR stack alone.</li>
 * </ul>
 *
 * <h2>Foreign loggers are quiet unless asked</h2>
 *
 * <p>{@code dbo.log.level} governs this product's own loggers. Everything else
 * — the container, the FHIR stack, the connection pool — is WARN unless a
 * prefix says otherwise, because the rule for what INFO means here is a rule
 * about this runtime's events: startup with its resolved posture, tenant
 * lifecycle, shutdown, and things that went wrong, never per-request or
 * per-item. A pool announcing every connection it opens and a parser
 * announcing every context it builds are both per-item by nature, and both
 * were in the stream at INFO while it claimed not to be.
 *
 * <p>Their warnings still arrive. What is dropped is a third party's idea of
 * what is worth saying when nothing is wrong.
 */
public final class DboLogging {

    /** Ordered, so a configured level admits everything above it. */
    public enum Level {
        ERROR, WARN, INFO, DEBUG, TRACE
    }

    /** The prefix this product's own loggers share. */
    private static final String OURS = "cloud.jengu.dbo";

    /** What a logger that is nobody's business here says when nothing is wrong. */
    private static final Level FOREIGN = Level.WARN;

    private static final String PREFIX_PROPERTY = "dbo.log.level.";

    private static final Level LEVEL = parseLevel(
            System.getProperty("dbo.log.level", "info"));

    /** Explicit per-prefix levels, longest match winning. */
    private static final java.util.Map<String, Level> BY_PREFIX = readPrefixLevels();
    private static final boolean JSON = !"text".equalsIgnoreCase(
            System.getProperty("dbo.log.format", "json"));

    private DboLogging() {
    }

    public static boolean enabled(String logger, Level level) {
        return level.ordinal() <= levelFor(logger).ordinal();
    }

    /** The level in force for one logger name. */
    static Level levelFor(String logger) {
        String name = logger == null ? "" : logger;
        Level chosen = null;
        int longest = -1;
        for (java.util.Map.Entry<String, Level> entry : BY_PREFIX.entrySet()) {
            String prefix = entry.getKey();
            if (name.startsWith(prefix) && prefix.length() > longest) {
                chosen = entry.getValue();
                longest = prefix.length();
            }
        }
        if (chosen != null) {
            return chosen;
        }
        return name.startsWith(OURS) ? LEVEL : FOREIGN;
    }

    private static java.util.Map<String, Level> readPrefixLevels() {
        java.util.Map<String, Level> levels = new java.util.LinkedHashMap<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX_PROPERTY) && key.length() > PREFIX_PROPERTY.length()) {
                levels.put(key.substring(PREFIX_PROPERTY.length()),
                        parseLevel(System.getProperty(key)));
            }
        }
        return java.util.Map.copyOf(levels);
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
