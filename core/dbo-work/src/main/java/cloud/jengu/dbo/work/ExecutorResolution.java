package cloud.jengu.dbo.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Which executor runs a step, decided the same way every time (#72).
 *
 * <p>Resolution walks the overlay chain terminology and configuration already
 * walk — baseline, zone, organisation — and offers the work to the most local
 * candidate that is <b>willing and permitted</b>. Willing is the candidate's
 * answer; permitted is the step's.
 *
 * <p><b>Nothing races.</b> Candidates are tried one at a time in declared
 * order. Racing them makes the same input behave differently under load,
 * doubles external effects — the store makes a losing write a no-op, but
 * nothing makes a losing call to somebody else's registry one — and splits
 * provenance across two actors, so afterwards nobody can say who did the thing.
 *
 * <p><b>Candidates are asked for, never held.</b> Existence follows the face
 * registry: a candidate is available because something providing it is
 * installed, and a provider can be withdrawn. A resolver holding a list would
 * keep selecting an executor that is no longer there, which is the same hazard
 * a held payload provider is, with side effects attached.
 */
public final class ExecutorResolution {

    private final Supplier<List<ExecutorCandidate>> candidates;

    public ExecutorResolution(Supplier<List<ExecutorCandidate>> candidates) {
        this.candidates = candidates;
    }

    /**
     * @param chain    where this work is happening, general to local
     * @param switches the automation declarations in force, from anywhere on
     *                 the chain; the most local one wins
     */
    public Resolution resolve(StepGrant grant, List<Scope> chain, List<Automation> switches,
            Work work) {
        Automation switched = mostLocal(grant, chain, switches);
        if (switched != null && !switched.on()) {
            return Resolution.fallThrough("automation is switched off at " + switched.scope().wire());
        }
        String refused = null;
        for (ExecutorCandidate candidate : byLocality(chain)) {
            Scope scope = candidate.executor().scope();
            if (!candidate.willTake(work)) {
                continue;
            }
            if (!grant.admits(scope)) {
                // Named, because a step nobody may override and a step nobody
                // wanted look identical from the outside — and one of them is
                // somebody's rule being refused.
                refused = refused != null ? refused
                        : "step " + grant.process() + "/" + grant.step()
                                + " is not overridable, and " + scope.wire() + " tried";
                continue;
            }
            return Resolution.selected(candidate.executor(), refused);
        }
        return Resolution.fallThrough(refused != null ? refused
                : "no executor took this work");
    }

    /**
     * The candidates on this chain, most local first, and in declared order
     * within a scope — which is what makes "the next one runs" a sentence with
     * one meaning.
     */
    private List<ExecutorCandidate> byLocality(List<Scope> chain) {
        List<ExecutorCandidate> onChain = new ArrayList<>();
        for (ExecutorCandidate candidate : candidates.get()) {
            if (chain.contains(candidate.executor().scope())) {
                onChain.add(candidate);
            }
        }
        onChain.sort(Comparator.comparingInt(
                candidate -> -candidate.executor().scope().at().ordinal()));
        return onChain;
    }

    /** The automation declaration in force here: the most local one that speaks. */
    private static Automation mostLocal(StepGrant grant, List<Scope> chain,
            List<Automation> switches) {
        Automation found = null;
        for (Automation switched : switches) {
            if (!switched.process().equals(grant.process())
                    || !switched.step().equals(grant.step())
                    || !chain.contains(switched.scope())) {
                continue;
            }
            if (found == null || switched.scope().at().asLocalAs(found.scope().at())) {
                found = switched;
            }
        }
        return found;
    }
}
