package cloud.jengu.dbo.core.api.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the store makes of the claims somebody presented.
 *
 * <p>Three outcomes, not two: a confident single match, candidates for a human
 * to weigh, and nothing — which is <b>normal</b>, being the person before their
 * first visit, and the moment somebody decides to create a record.
 *
 * <p>The rules here are the <b>mechanism</b>: a claim's strength bounds the
 * confidence it can produce, and ambiguity destroys certainty. What to do with
 * a merely probable match — resolve it, or put it in front of somebody — is
 * policy, and belongs to the zone rather than to the engine (§17).
 */
public record Resolution(List<Candidate> candidates) {

    public Resolution {
        candidates = List.copyOf(candidates);
    }

    /**
     * The single subject this resolves to without anybody looking, if there is
     * one.
     *
     * <p>Empty is not failure. It means a person must decide — or that this is
     * somebody the store has never seen, which is how everybody starts.
     */
    public Optional<String> certain() {
        List<Candidate> sure = candidates.stream()
                .filter(c -> c.confidence() == Candidate.Confidence.CERTAIN).toList();
        return sure.size() == 1 ? Optional.of(sure.get(0).subjectId()) : Optional.empty();
    }

    public boolean needsAdjudication() {
        return certain().isEmpty() && !candidates.isEmpty();
    }

    /**
     * Weighs presented claims against what the store found for each.
     *
     * @param presented the claims the person offered
     * @param matches   for each claim, the subjects already holding it — the
     *                  store's job, done before this is called, because the
     *                  rules below are worth testing without a database
     */
    public static Resolution of(List<IdentityClaim> presented,
            Map<IdentityClaim, List<String>> matches) {
        return of(presented, matches, java.util.Set.of());
    }

    /**
     * As above, knowing which subjects a person has already judged not to be
     * this one.
     *
     * @param previouslyRejected subjects an earlier adjudication declined. They
     *                           are still offered — marked — because hiding
     *                           them would make a wrong decision permanent and
     *                           unexaminable. What they may never be is
     *                           certain: a machine does not silently reverse a
     *                           person's conclusion.
     */
    public static Resolution of(List<IdentityClaim> presented,
            Map<IdentityClaim, List<String>> matches,
            java.util.Set<String> previouslyRejected) {
        Map<String, List<IdentityClaim>> bySubject = new LinkedHashMap<>();
        Map<String, Candidate.Confidence> best = new LinkedHashMap<>();

        for (IdentityClaim claim : presented) {
            for (String subject : matches.getOrDefault(claim, List.of())) {
                bySubject.computeIfAbsent(subject, s -> new ArrayList<>()).add(claim);
                Candidate.Confidence reached = confidenceOf(claim);
                best.merge(subject, reached,
                        (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
            }
        }

        // Two subjects matched means the claims disagree about who this is.
        // Neither can be certain: resolving one of them automatically would be
        // choosing, and choosing is what the human step exists for.
        boolean ambiguous = bySubject.size() > 1;

        List<Candidate> candidates = new ArrayList<>();
        bySubject.forEach((subject, claims) -> {
            Candidate.Confidence confidence = best.get(subject);
            if (ambiguous && confidence == Candidate.Confidence.CERTAIN) {
                confidence = Candidate.Confidence.PROBABLE;
            }
            boolean rejectedBefore = previouslyRejected.contains(subject);
            if (rejectedBefore && confidence == Candidate.Confidence.CERTAIN) {
                confidence = Candidate.Confidence.PROBABLE;
            }
            candidates.add(new Candidate(subject, confidence, claims, rejectedBefore));
        });
        return new Resolution(candidates);
    }

    /**
     * A claim's strength bounds what it can conclude. An unverified claim never
     * resolves anybody automatically — a licence number read off a card, driving
     * a match on its own, attaches one person's care to another's on the
     * strength of something nobody checked.
     */
    private static Candidate.Confidence confidenceOf(IdentityClaim claim) {
        if (!claim.mayMatchAutomatically()) {
            return Candidate.Confidence.POSSIBLE;
        }
        return claim.verification() == IdentityClaim.Verification.AUTHENTICATED
                ? Candidate.Confidence.CERTAIN
                : Candidate.Confidence.PROBABLE;
    }
}
