package cloud.jengu.dbo.work;

/**
 * Where a rule was declared, general to local (ADR 0059).
 *
 * <p>The chain terminology and configuration already walk. Order is
 * specificity, and it is an order rather than a set because resolution asks
 * "who is the most local candidate here" — a question a set cannot answer.
 */
public enum ScopeClass {

    /** The deployment's own: what everything gets when nothing local is declared. */
    BASELINE,

    /** A jurisdiction (§17) — its terminology, its brokers, its rules. */
    ZONE,

    /** One organisation's local arrangement. */
    ORGANISATION;

    /** Whether this class is at least as local as another. */
    boolean asLocalAs(ScopeClass other) {
        return ordinal() >= other.ordinal();
    }

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
