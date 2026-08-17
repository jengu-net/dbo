package cloud.jengu.dbo.core.api.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * A subject that is anonymous <b>on purpose</b> (dbo#39).
 *
 * <p>Two unbound subjects look identical and mean opposite things. One is not
 * yet identified — a patient brought in unconscious, where binding is expected
 * and somebody should be prompted. The other is anonymous by right or by
 * choice: anonymous testing, a protected identity, a person exercising a legal
 * entitlement. There, binding is <b>forbidden</b>, and prompting for it is the
 * failure.
 *
 * <p>Absence of a binding expresses the first perfectly well and cannot
 * express the second, because <b>an intention cannot be stated by an
 * absence</b>. Without a positive declaration, a well-meaning workflow helps
 * somebody identify a patient who had a right not to be, and everybody
 * involved believes they were being careful.
 *
 * <p>Recorded as events like bindings, and for the same reason: a lifted
 * declaration must not erase that it once stood.
 */
public record AnonymityEvent(Kind kind, String subjectId, String actor, Instant at,
                             String basis, String because) {

    public enum Kind {
        /** This subject is not to be identified. */
        DECLARED,
        /**
         * The declaration no longer stands — typically because the person
         * chose to be identified after all, which is theirs to choose.
         */
        LIFTED
    }

    public AnonymityEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(at, "at");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("a declaration names who made it");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException(
                    "anonymity states its basis — a right exercised, a policy, a patient's "
                            + "request — because 'somebody ticked a box' is not something anyone "
                            + "can rely on later");
        }
    }

    public static AnonymityEvent declared(String subjectId, String actor, Instant at,
            String basis, String because) {
        return new AnonymityEvent(Kind.DECLARED, subjectId, actor, at, basis, because);
    }

    public static AnonymityEvent lifted(String subjectId, String actor, Instant at,
            String basis, String because) {
        return new AnonymityEvent(Kind.LIFTED, subjectId, actor, at, basis, because);
    }
}
