package cloud.jengu.dbo.work;

/**
 * One place on the chain: a class and whose it is.
 *
 * <p>The code is the zone's or the organisation's own — {@code ee},
 * {@code hogwarts} — and the baseline has none, because there is only one of
 * it and naming it would invite a second.
 */
public record Scope(ScopeClass at, String code) {

    public static final Scope BASELINE = new Scope(ScopeClass.BASELINE, null);

    public static Scope zone(String code) {
        return new Scope(ScopeClass.ZONE, code);
    }

    public static Scope organisation(String code) {
        return new Scope(ScopeClass.ORGANISATION, code);
    }

    /** How a scope is written where it is recorded and queried. */
    public String wire() {
        return code == null ? at.wire() : at.wire() + ":" + code;
    }

    static Scope of(String wire) {
        if (wire == null) {
            return null;
        }
        int colon = wire.indexOf(':');
        return colon < 0
                ? new Scope(ScopeClass.valueOf(wire.toUpperCase(java.util.Locale.ROOT)), null)
                : new Scope(ScopeClass.valueOf(
                        wire.substring(0, colon).toUpperCase(java.util.Locale.ROOT)),
                        wire.substring(colon + 1));
    }
}
