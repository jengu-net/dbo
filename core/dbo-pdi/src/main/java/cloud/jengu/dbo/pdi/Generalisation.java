package cloud.jengu.dbo.pdi;

/**
 * The coarse form of an identifying element — what a reader without the key
 * sees in its place (ADR 0056 §7).
 *
 * <p>Coarse rather than absent, because some elements are identifying and
 * clinically load-bearing at once. The value is computed when the record is
 * written and kept in the clear beside the ciphertext, since a reader with no
 * key has nothing to derive it from.
 */
final class Generalisation {

    private Generalisation() {
    }

    /**
     * @return the coarse value, or {@code null} when the element has no known
     *         coarse form — in which case it is simply absent, because
     *         inventing a plausible-looking value would be worse than saying
     *         nothing
     */
    static Object of(String element, Object value) {
        if ("birthDate".equals(element)) {
            return year(String.valueOf(value));
        }
        return null;
    }

    /**
     * A birth date reduced to its year.
     *
     * <p>Year alone answers most of what a birth date is needed for — age for
     * dosing, screening intervals, growth expectations at any scale above the
     * neonatal — while being far too coarse to identify. Care that needs the
     * exact date asks for it and says why.
     *
     * <p>It is also still a valid FHIR {@code date}: the type admits
     * {@code YYYY}, {@code YYYY-MM} and {@code YYYY-MM-DD}, so the coarse form
     * is expressible in the standard's own type system rather than being a
     * shape violation somebody has to special-case.
     */
    private static String year(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        String head = date.substring(0, 4);
        return head.chars().allMatch(Character::isDigit) ? head : null;
    }
}
