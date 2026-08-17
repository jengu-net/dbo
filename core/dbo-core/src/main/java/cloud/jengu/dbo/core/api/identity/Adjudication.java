package cloud.jengu.dbo.core.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A person's decision about who somebody is (dbo#39).
 *
 * <p>When claims do not resolve on their own, a human decides: this is the
 * person we already know, or this is somebody new. The decision is recorded
 * rather than merely acted on, for two reasons.
 *
 * <p><b>The negative half is the valuable half.</b> "These are different
 * people" is a conclusion somebody reached by looking, and without it the next
 * person to meet the same near-match starts from nothing and reaches it again.
 * It is also the evidence if the decision later turns out to have been wrong.
 *
 * <p><b>And it can be wrong.</b> Identification is revisable, so a decision
 * carries who made it, when, and on what — a record that can be revisited
 * rather than a fact that simply became true.
 */
public record Adjudication(Outcome outcome, String subjectId,
                           List<String> rejected, List<IdentityClaim> presented,
                           String decidedBy, Instant decidedAt, String because) {

    public enum Outcome {
        /** The claims belong to somebody the store already knows. */
        BOUND,
        /**
         * Nobody here. A new subject is created.
         *
         * <p>The default when uncertain, deliberately: a duplicate person is
         * recoverable — find it later, link them, nothing lost — while binding
         * to the wrong person merges two humans, and the next clinician reads
         * an allergy list that is not theirs. Duplicates are tidiness; wrong
         * merges are safety.
         */
        CREATED,
        /**
         * Nobody decided yet. The adjudicator may not be present, and care does
         * not wait — this is a state a record lives in, not a failure.
         */
        DEFERRED
    }

    public Adjudication {
        Objects.requireNonNull(outcome, "outcome");
        rejected = List.copyOf(rejected);
        presented = List.copyOf(presented);
        if (outcome != Outcome.DEFERRED) {
            Objects.requireNonNull(subjectId, "subjectId");
            if (decidedBy == null || decidedBy.isBlank()) {
                throw new IllegalArgumentException(
                        "an identification decision needs somebody who made it — an anonymous one "
                                + "cannot be revisited, questioned, or defended");
            }
            Objects.requireNonNull(decidedAt, "decidedAt");
        }
    }

    /** This is somebody already known, and these candidates were considered and were not. */
    public static Adjudication bound(String subjectId, List<String> rejected,
            List<IdentityClaim> presented, String decidedBy, Instant at, String because) {
        return new Adjudication(Outcome.BOUND, subjectId, rejected, presented,
                decidedBy, at, because);
    }

    /** Nobody here: a new subject, with the candidates that were looked at and declined. */
    public static Adjudication created(String subjectId, List<String> rejected,
            List<IdentityClaim> presented, String decidedBy, Instant at, String because) {
        return new Adjudication(Outcome.CREATED, subjectId, rejected, presented,
                decidedBy, at, because);
    }

    /** Nobody has decided; care continues meanwhile. */
    public static Adjudication deferred(List<IdentityClaim> presented) {
        return new Adjudication(Outcome.DEFERRED, null, List.of(), presented, null, null, null);
    }
}
