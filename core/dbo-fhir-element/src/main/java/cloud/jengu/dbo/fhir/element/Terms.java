package cloud.jengu.dbo.fhir.element;

import java.util.Optional;

/**
 * What one tenant's terminology knows about a code — the seam through which
 * validation reaches CURRENT data rather than carried definitions.
 *
 * <p>The carried definitions are static: what a version's specification said
 * when it was published. A tenant's codes are not — its own systems, its
 * aliases, the concepts imported from the terminology baseline all live in the
 * tenant's store and change while the container runs. Validation that only
 * consulted the carried definitions would call a tenant's own codes unknown
 * forever.
 *
 * <p>Three answers, and the difference between the last two is the central
 * distinction:
 *
 * <ul>
 *   <li>{@code Membership(true, display)} — the code is in the system;</li>
 *   <li>{@code Membership(false, null)} — the system is held and the code is
 *       <b>not in it</b>: a fact about the data, reported as invalid;</li>
 *   <li>{@code empty} — the tenant does not hold the system at all:
 *       a fact about the store's coverage, reported as <i>unresolvable</i>,
 *       never as invalid.</li>
 * </ul>
 *
 * <p>This is an argument handed to validation, not a capability of the face:
 * the face stays a translator (§ DomainFace — "a translator, not an actor"),
 * and the tenant-specific answerer arrives as data from the facade that owns
 * the tenant's database.
 */
public interface Terms {

    /** What one tenant holds for {@code system}; empty when it holds nothing. */
    Optional<Membership> membership(String system, String code);

    /** The verdict for a held system: is the code in it, and how is it shown. */
    record Membership(boolean present, String display) {}

    /** A tenant with no terminology of its own: every system is unresolvable. */
    Terms NONE = (system, code) -> Optional.empty();
}
