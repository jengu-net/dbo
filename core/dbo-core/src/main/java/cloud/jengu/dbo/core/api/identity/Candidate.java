package cloud.jengu.dbo.core.api.identity;

import java.util.List;

/**
 * A subject that might be the person presenting, and how sure the store is
 * (dbo#39).
 *
 * <p>Resolution answers with candidates rather than an answer, because
 * identification is a match and not a key lookup. The confidence is what
 * decides whether anybody has to look.
 */
public record Candidate(String subjectId, Confidence confidence,
                        List<IdentityClaim> matched, boolean previouslyRejected) {

    /** A candidate nobody has judged before. */
    public Candidate(String subjectId, Confidence confidence, List<IdentityClaim> matched) {
        this(subjectId, confidence, matched, false);
    }

    public Candidate {
        matched = List.copyOf(matched);
    }

    /**
     * Whether somebody already looked at this pairing and said no.
     *
     * <p>Marked rather than hidden. Hiding it would make a wrong decision
     * permanent and invisible — the correct match would never be offered
     * again, and nobody would know why. Marked, an adjudicator sees that the
     * question was asked before and can answer differently with new evidence.
     */
    public boolean previouslyRejected() {
        return previouslyRejected;
    }

    /** How sure the store is that this candidate is the person presenting. */
    public enum Confidence {
        /**
         * A single subject, matched by a claim strong enough to stand alone.
         * Safe to resolve without anybody looking.
         */
        CERTAIN,
        /** Matched, but by a claim that has been checked rather than proven now. */
        PROBABLE,
        /**
         * Matched only by something nobody verified, or matched alongside
         * others. Evidence for a person to weigh, never an answer.
         */
        POSSIBLE
    }
}
