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
     * Applies a declared set and records the pass.
     *
     * @param scope       what the declaration is about — the zone, the tenant,
     *                    the set's own name. One durable run per scope.
     * @param correlation what the declaring side calls this, echoed and never
     *                    interpreted; null when it gave none
     */
    public Outcome apply(String scope, String correlation, List<Declared> declarations) {
        Run sweep = runs.sweep(PROCESS, STEP, scope, List.of(domain));
        if (correlation != null && sweep.correlated().isEmpty()) {
            sweep = runs.correlated(sweep, correlation);
        }
        Runs.Pass pass = runs.pass(sweep);
        long applied = 0;
        long skipped = 0;
        for (Declared declared : declarations) {
            try {
                // As the configuration lane, which is what this is: a type
                // projected from a declaration refuses every other caller, and
                // the lane that may write it has to say so rather than have it
                // inferred from whichever credential was in play.
                store.put(PutRequest.create(declared.typeName(), declared.payload()),
                        Handling.Authority.CONFIG_LANE);
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
}
