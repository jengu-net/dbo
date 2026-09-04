package cloud.jengu.dbo.sync;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.work.Failure;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.util.List;

/**
 * Applying what a declaration says, as a run.
 *
 * <p>Configuration arrives from somewhere that declared it — a git-backed
 * loader, an admin commit, an importer — and applying it is a <b>sweep</b>: it
 * closes when what is here agrees with what was declared, not when a list has
 * been walked once. A declaration nobody can apply stays an open item until a
 * later pass stops finding it, so fixing the declaration closes the card
 * (REQ-DBO-PROC-CLOSE-BY-RE-EVALUATION).
 *
 * <p><b>One bad record does not take the rest with it.</b> Forty-six declared
 * things with two that cannot be applied is forty-four applied, a tally, and two
 * cards — not a zone that silently did nothing. The absence of that sentence is
 * why a partial application presents as "my configuration had no effect" rather
 * than as a failure.
 *
 * <p><b>Nothing reaches back</b>. The correlation the declaration
 * carried is echoed on the run, opaque and never parsed, and whoever declared it
 * closes their own run by re-evaluating against what this one says. dbo does not
 * call them: the moment it does, both systems have to be up together.
 */
public final class ConfigApplication {

    /** What this is called wherever runs are read. */
    public static final String PROCESS = "dbo.config.applied";

    /** Its one step: the application itself. */
    public static final String STEP = "apply";

    private final ObjectStore store;
    private final Runs runs;
    private final String domain;

    public ConfigApplication(ObjectStore store, Runs runs, String domain) {
        this.store = store;
        this.runs = runs;
        this.domain = domain;
    }

    /**
     * One declared thing.
     *
     * @param name what to call it in front of a person — a file, a code, an id.
     *             The engine has no opinion about it and never parses it.
     */
    public record Declared(String typeName, String name, byte[] payload) {}

    /** What a pass did, for the caller that has to answer for it. */
    public record Outcome(long read, long applied, long skipped) {}

    /**
     * How one declared thing is applied where it lands.
     *
     * <p>Writing it into the store is the ordinary answer and the default one.
     * It is not the only one: the face's own vocabulary is a declared set too,
     * and a CodeSystem written the ordinary way is stored whole and answers
     * nothing — it has to arrive at the grain the tenant keeps concepts in or
     * {@code $lookup} cannot resolve the store's own definitions. What differs
     * between those is the writing. The pass, the tally, the cards and the
     * closure are the same either way, which is the whole reason this seam is
     * here rather than a second applier being written beside this one.
     *
     * <p>A throw is the card. Anything the applier considers expected — a
     * definition this tenant may not write because it arrives from its source
     * instead — it swallows, because a card nobody has to act on is how a
     * queue stops being read.
     */
    @FunctionalInterface
    public interface Applier {
        void apply(Declared declared);
    }

    /**
     * Applies a declared set and records the pass.
     *
     * @param scope       what the declaration is about — the zone, the tenant,
     *                    the set's own name. One durable run per scope.
     * @param correlation what the declaring side calls this, echoed and never
     *                    interpreted; null when it gave none
     */
    public Outcome apply(String scope, String correlation, List<Declared> declarations) {
        return apply(scope, correlation, declarations, this::intoTheStore);
    }

    /**
     * Reads a source and applies what it declares — or reads it and stops,
     * when this scope already agrees with exactly that read.
     *
     * <p>The skip has no correctness cost, because the two reasons to run a
     * pass over an unchanged source are the two it checks. A declaration
     * somebody has to fix leaves a card, and a card closes by re-evaluation
     * rather than by a click — so while one is open every pass runs, and the
     * pass that finds the world agreeing is the one that closes it. A pass
     * that left nothing open has nothing to re-evaluate, and re-applying an
     * unchanged set is then a rewrite nobody reads, every couple of seconds,
     * in a store whose feed everything downstream is watching.
     *
     * @return what the pass did, or a read of nothing when there was nothing
     *         to do
     */
    public Outcome applyFrom(String scope, ConfigSource source, Applier applier) {
        ConfigSource.Fetch fetch = source.fetch();
        if (settledAt(scope, fetch.marker())) {
            return new Outcome(0, 0, 0);
        }
        return apply(scope, fetch.marker(), fetch.declarations(), applier);
    }

    /** As above, applying each declaration into the store. */
    public Outcome applyFrom(String scope, ConfigSource source) {
        return applyFrom(scope, source, this::intoTheStore);
    }

    /**
     * Whether this scope already agrees with exactly this read, and the pass
     * that got it there left nothing for anybody. A scope nothing has applied
     * yet has not settled: it has not been asked at all.
     */
    private boolean settledAt(String scope, String marker) {
        return runs.byKey(PROCESS + "/" + STEP + "/" + scope)
                .filter(previous -> !previous.needsAPerson())
                .flatMap(Run::correlated)
                .filter(agreed -> agreed.equals(marker))
                .isPresent();
    }

    /**
     * The same pass, applying each declaration the caller's own way.
     *
     * @param applier what a declared thing means where it is going
     */
    public Outcome apply(String scope, String correlation, List<Declared> declarations,
            Applier applier) {
        Run sweep = runs.sweep(PROCESS, STEP, scope, List.of(domain));
        // Which read this scope last agreed with. Replaced rather than kept
        // from the first pass ever: somebody comparing the two is asking
        // whether what is here came from what is declared NOW, and an answer
        // naming the commit this tenant was born under cannot tell them.
        if (correlation != null && !correlation.equals(sweep.correlated().orElse(null))) {
            sweep = runs.correlated(sweep, correlation);
        }
        Runs.Pass pass = runs.pass(sweep);
        long applied = 0;
        long skipped = 0;
        for (Declared declared : declarations) {
            try {
                applier.apply(declared);
                applied++;
            } catch (RuntimeException refused) {
                skipped++;
                // A declaration the engine refuses is a person's — no pass will
                // apply it until somebody changes it. A store that was
                // unavailable is a retry and nobody's card.
                pass.item(declared.name(), Failure.of(refused), String.valueOf(refused.getMessage()));
            }
        }
        pass.counted("read", declarations.size())
                .counted("applied", applied)
                .counted("skipped", skipped)
                .done();
        return new Outcome(declarations.size(), applied, skipped);
    }

    /**
     * As the configuration lane, which is what this is: a type projected from a
     * declaration refuses every other caller, and the lane that may write it
     * has to say so rather than have it inferred from whichever credential was
     * in play.
     */
    private void intoTheStore(Declared declared) {
        store.put(PutRequest.create(declared.typeName(), declared.payload()),
                Handling.Authority.CONFIG_LANE);
    }
}
