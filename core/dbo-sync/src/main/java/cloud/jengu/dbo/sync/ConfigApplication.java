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
    public record Outcome(long read, long applied, long skipped, long withdrawn) {

        public Outcome(long read, long applied, long skipped) {
            this(read, applied, skipped, 0);
        }
    }

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

        /**
         * What this applier holds from this scope, by identity — the other
         * half of a withdrawal, and the half only the applier can answer.
         *
         * <p>Empty means "I cannot say", and nothing is withdrawn. That is the
         * default because the ordinary answer for a store is dangerous: every
         * record of these types that this read does not declare would include
         * the ones the tenant authored itself, sitting beside the projected
         * ones. An applier withdraws only where it can say honestly that
         * everything it names came from this source.
         */
        default List<cloud.jengu.dbo.core.api.Identifier> held(String scope) {
            return List.of();
        }

        /**
         * Undoing one application: this identity is no longer declared.
         *
         * <p>Refusing is the default, and a refusal is a card rather than a
         * silence — so a source that stops declaring something reaches
         * somebody, and nothing is removed by machinery that was never told
         * how to remove it.
         */
        default void withdraw(String scope, cloud.jengu.dbo.core.api.Identifier identity) {
            throw new UnsupportedOperationException(
                    "no longer declared, and this applier was never told how to undo an "
                            + "application: " + identity.system() + "|" + identity.value());
        }
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
        return apply(scope, fetch.marker(), fetch.declarations(), applier, fetch.complete());
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
        // A caller handing over a list says nothing about whether it is all of
        // them, so nothing is withdrawn: only a source can claim completeness,
        // and only a claim can be trusted with a removal.
        return apply(scope, correlation, declarations, applier, false);
    }

    private Outcome apply(String scope, String correlation, List<Declared> declarations,
            Applier applier, boolean complete) {
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
        long withdrawn = 0;
        // A read with a declaration nobody could apply is not a read anything
        // may be subtracted from. The one that failed is usually the one that
        // cannot be identified either, so its own record would be the record
        // taken away — a typo deleting the thing the typo was in. Cards close
        // by re-evaluation, so the pass after the fix withdraws properly.
        if (complete && skipped == 0) {
            // What this scope holds and this read does not name. Keyed on
            // identity because it is the one key both sides can compute: a
            // card names the file somebody has to open, and a record is
            // keyed by what it declares.
            java.util.Set<cloud.jengu.dbo.core.api.Identifier> stillDeclared =
                    new java.util.LinkedHashSet<>();
            for (Declared declared : declarations) {
                stillDeclared.addAll(identityOf(declared));
            }
            for (cloud.jengu.dbo.core.api.Identifier gone : applier.held(scope)) {
                if (stillDeclared.contains(gone)) {
                    continue;
                }
                try {
                    applier.withdraw(scope, gone);
                    withdrawn++;
                } catch (RuntimeException refused) {
                    skipped++;
                    pass.item(gone.value(), Failure.of(refused), String.valueOf(
                            refused.getMessage()));
                }
            }
        }
        pass.counted("read", declarations.size())
                .counted("applied", applied)
                .counted("skipped", skipped)
                .counted("withdrawn", withdrawn)
                .done();
        return new Outcome(declarations.size(), applied, skipped, withdrawn);
    }

    /**
     * As the configuration lane, which is what this is: a type projected from a
     * declaration refuses every other caller, and the lane that may write it
     * has to say so rather than have it inferred from whichever credential was
     * in play.
     *
     * <p><b>Keyed on what the declaration says it is</b>, so applying it twice
     * lands on the same record. A plain create refuses the second pass — the
     * identity is already claimed — which made every changed declaration a
     * card saying so, and left the store holding the version before the
     * change. Declaring the same thing again is the ordinary case for
     * configuration; it is what a source moving forward looks like.
     *
     * <p>The identity comes from the type's own registration rather than from
     * the caller: the store already knows how each type names itself, and
     * being told a second time is a second thing to disagree with. A type
     * whose declarations name nothing is written as it was before — some
     * things really are anonymous, and refusing them here would refuse the
     * only shape that ever worked.
     */
    public void intoTheStore(Declared declared) {
        java.util.List<cloud.jengu.dbo.core.api.Identifier> named =
                identityOf(declared);
        if (named.isEmpty()) {
            store.put(PutRequest.create(declared.typeName(), declared.payload()),
                    Handling.Authority.CONFIG_LANE);
            return;
        }
        cloud.jengu.dbo.core.api.Identifier claim = named.get(0);
        store.putConditional(
                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM.equals(claim.system())
                        ? cloud.jengu.dbo.core.api.IdentityRef.canonical(claim.value())
                        : cloud.jengu.dbo.core.api.IdentityRef.identifier(
                                claim.system(), claim.value()),
                PutRequest.create(declared.typeName(), declared.payload()));
    }

    /**
     * What this declaration calls itself, read the way the store reads it —
     * or nothing, when it cannot be read at all. Being unidentifiable is
     * already a card from the pass above; it is not this method's to report
     * a second time.
     */
    private java.util.List<cloud.jengu.dbo.core.api.Identifier> identityOf(Declared declared) {
        cloud.jengu.dbo.core.api.TypeRegistration registration =
                store.registrationOf(declared.typeName());
        if (registration == null) {
            // An unknown type is not this method's refusal to make: the write
            // below meets it and the pass turns it into a card, which is where
            // the reason belongs.
            return java.util.List.of();
        }
        try {
            return registration.extractor().extract(declared.typeName(), declared.payload())
                    .identifiers();
        } catch (RuntimeException unreadable) {
            return java.util.List.of();
        }
    }
}
