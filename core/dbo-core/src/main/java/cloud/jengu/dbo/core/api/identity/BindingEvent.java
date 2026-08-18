package cloud.jengu.dbo.core.api.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * An identity attached to, or detached from, a subject.
 *
 * <p><b>Binding is the privileged operation</b>, and it is easy to assume the
 * opposite. Everything else in the membrane protects <em>reads</em> of
 * identifying data — but a subject with no identity bound to it has nothing to
 * read, and no read control touches the act that changes that. Binding is what
 * de-anonymises; it needs its own authority, its own purpose, and its own
 * trace.
 *
 * <p><b>Events, not a mutable link.</b> Binding and withdrawing are both
 * recorded and never edited, so the fact that an identity was once attached
 * survives its removal. A withdrawal that erased the binding would erase the
 * evidence that anybody was ever identified — which is precisely what somebody
 * would want erased if the binding had been wrong.
 *
 * <p>Withdrawal touches no clinical data. The attachment lives on the identity
 * side, so detaching it removes nothing that was recorded about the care.
 */
public record BindingEvent(Kind kind, String identityId, String subjectId, Assurance assurance,
                           String actor, Instant at, String purpose, String because) {

    public enum Kind {
        /** This identity belongs to this subject. */
        BOUND,
        /**
         * It does not, or no longer does.
         *
         * <p>A wrong binding put one person's care in another's record — the
         * next clinician reads an allergy list that is not theirs — so this
         * must always be available, and it must not take the care with it.
         */
        WITHDRAWN
    }

    public BindingEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(at, "at");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException(
                    "binding and withdrawing name who did it — de-anonymising somebody without "
                            + "a trace is the failure this record exists to prevent");
        }
        Objects.requireNonNull(assurance, "assurance");
        if (kind == Kind.BOUND && assurance == Assurance.NONE) {
            throw new IllegalArgumentException(
                    "an identification records how well it was established — NONE means nothing "
                            + "was, which is the absence of a binding rather than a weak one");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException(
                    "binding states its purpose: it discloses who a subject is, and 'why' is "
                            + "what an audit is asked for");
        }
    }

    /**
     * @param assurance how well this identification was established — the
     *                  strength of the evidence in front of whoever made it,
     *                  which later bounds what the binding can be used for
     */
    public static BindingEvent bound(String identityId, String subjectId, Assurance assurance,
            String actor, Instant at, String purpose, String because) {
        return new BindingEvent(Kind.BOUND, identityId, subjectId, assurance,
                actor, at, purpose, because);
    }

    public static BindingEvent withdrawn(String identityId, String subjectId,
            String actor, Instant at, String purpose, String because) {
        return new BindingEvent(Kind.WITHDRAWN, identityId, subjectId, Assurance.NONE,
                actor, at, purpose, because);
    }
}
