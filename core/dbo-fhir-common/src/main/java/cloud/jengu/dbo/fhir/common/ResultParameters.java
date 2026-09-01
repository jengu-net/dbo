package cloud.jengu.dbo.fhir.common;

/**
 * How a caller says how many and in what order — once, for every surface.
 *
 * <p>{@code _count} and {@code _sort} are not filters and not per-type: they
 * shape a RESULT, and they mean the same thing whatever is being read. dbo
 * learned that the expensive way. The ordinary resource path parsed them
 * itself, the audit trail parsed its own filters and refused everything else,
 * and a client met two contracts on one server depending on which types
 * happened to sit behind a surface — "the last N events, newest first", the
 * query an audit page IS, worked on one path and answered 400 on the other
 *.
 *
 * <p>So the spelling lives here and the surfaces decide only what they can
 * honour. A surface that cannot order by a field refuses that field; none of
 * them gets to disagree about what {@code -date} means, or about a count
 * being a positive number.
 */
public final class ResultParameters {

    public static final String COUNT = "_count";
    public static final String SORT = "_sort";

    private ResultParameters() {
    }

    /** Whether a parameter shapes the result rather than filtering it. */
    public static boolean shapesTheResult(String name) {
        return COUNT.equals(name) || SORT.equals(name);
    }

    /**
     * How many, bounded.
     *
     * @param fallback what the surface returns when the caller says nothing
     * @param ceiling  the most it will return however large the ask — a
     *                 collection that grows without limit has no "all of it"
     *                 that is also a page
     */
    public static int count(String value, String typeName, int fallback, int ceiling) {
        if (value == null) {
            return fallback;
        }
        try {
            return Math.clamp(Integer.parseInt(value.trim()), 1, ceiling);
        } catch (NumberFormatException e) {
            throw new UnknownSearchParameterException(typeName, COUNT + "=" + value);
        }
    }

    /** Which field, and which way round. */
    public record Sort(String field, boolean descending) {}

    /**
     * @return the ordering asked for, or null when the caller asked for none —
     *         the surface's own default then stands, and it is the surface's
     *         business what that is
     */
    public static Sort sort(String value, String typeName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.contains(",")) {
            // Refused rather than half-applied: answering the first key of a
            // multi-key sort is a different order than the one asked for, and
            // the caller cannot see which they got.
            throw new UnknownSearchParameterException(typeName, SORT + "=" + value);
        }
        boolean descending = value.startsWith("-");
        return new Sort(descending ? value.substring(1) : value, descending);
    }
}
