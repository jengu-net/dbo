package cloud.jengu.dbo.core.face;

/**
 * How a declared identifying element is made coarser — supplied by the
 * <b>face</b>, called by the engine (§12).
 *
 * <p>The engine decides <em>that</em> an element is generalised rather than
 * removed, because that is a data-handling decision. It cannot decide
 * <em>how</em>: knowing that a birth date is a date, and that its useful
 * coarse form is the year, is knowledge about a domain's shapes. A face for
 * another domain coarsening a postcode brings its own rule and must not
 * inherit a rule about dates.
 *
 * <p>This is a <b>translator, not an actor</b>. It takes a value and returns a
 * value; it does not read or write the store, and the engine never re-enters
 * itself through it. That is what keeps two faces able to run in parallel over
 * one store without disagreeing about who did what.
 */
@FunctionalInterface
public interface Coarsening {

    /**
     * @return the coarse form of the value, or {@code null} when this element
     *         has no honest coarse form — in which case it is simply absent,
     *         because a plausible-looking stand-in is worse than saying nothing
     */
    Object coarsen(String typeName, String element, Object value);

    /** For a face that generalises nothing: every element is absent instead. */
    Coarsening NONE = (typeName, element, value) -> null;
}
