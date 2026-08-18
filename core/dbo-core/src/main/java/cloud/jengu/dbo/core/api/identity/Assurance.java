package cloud.jengu.dbo.core.api.identity;

/**
 * How well an identity is established.
 *
 * <p>The names are eIDAS's, because identity assurance is a regulatory
 * question rather than a medical one and that vocabulary already exists across
 * the union. A face maps its own scale onto this — FHIR's
 * {@code Person.link.assurance} runs {@code level1}–{@code level4} — rather
 * than the engine learning a domain's spelling.
 *
 * <p>Ordered weakest to strongest, and the order is the point: the whole use
 * of this type is comparing two of them and taking the lesser.
 */
public enum Assurance {

    /** Nothing established. The absence of a link, not a weak one. */
    NONE,

    /**
     * Some confidence — a self-asserted identity, a document number nobody
     * checked.
     */
    LOW,

    /** Verified against an authority, at some point. */
    SUBSTANTIAL,

    /** Verified to the standard a national eID scheme requires. */
    HIGH;

    /**
     * The weaker of two — the rule that stops a strong identity being reached
     * through a weak door.
     *
     * <p>A person may hold several identifiers of different strengths, and any
     * of them resolves the same record. Without this, whoever can assert the
     * weakest one obtains everything the strongest earned, and nothing
     * anywhere reports a problem: the lookup succeeds, the session issues, the
     * grants apply.
     *
     * <p>This is a <b>chain</b> rule and applies to the path from an assertion
     * through a link to a capacity, where every step has to hold. It is not
     * the rule for alternative evidence about one subject, where the best
     * claim wins — confusing the two would let somebody weaken a real login by
     * also mentioning a document.
     */
    public static Assurance weakerOf(Assurance one, Assurance other) {
        return one.ordinal() <= other.ordinal() ? one : other;
    }

    public boolean atLeast(Assurance required) {
        return ordinal() >= required.ordinal();
    }
}
