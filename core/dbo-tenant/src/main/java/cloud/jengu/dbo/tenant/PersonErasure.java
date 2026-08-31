package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.pdi.PersonVault;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Erasing a person is work, and the run is the receipt (#165).
 *
 * <p>The erasure itself has existed for a long time — destroy the key, drop the
 * index, keep the ledger entry so a restore cannot resurrect anybody. What did
 * not exist was any way to <b>ask</b> for it from outside, and the obvious fix
 * was another verb on the maintenance surface beside archive and reshape.
 *
 * <p>That would have been the only mutation of history in this store that is
 * not a run. An erasure is not an operation on the store the way reindexing
 * is: it is a legally significant act, performed for a named person, on
 * somebody's instruction, and what a tenant needs afterwards is something it
 * can show a regulator. A run is exactly that — who asked, when, how far it
 * got — and it is what a retention sweep and a tenant erasure already are.
 *
 * <p><b>Idempotent because the run is keyed.</b> A repeated request finds the
 * run that already exists rather than opening a second, so a caller never has
 * to implement "erase unless already erased", which is a race with itself.
 *
 * <p><b>The subject is named by reference, never by identity.</b> What arrives
 * is the pseudonymous record id a consumer already holds; resolving it to the
 * vault's person is this side's act. A run naming a national identifier would
 * put identifying data into a work record, and a run's envelope carries state,
 * never subject.
 */
public final class PersonErasure {

    /**
     * What this is, in the catalogue every run names.
     *
     * <p>Two parts, not three, unlike the store's undeclared housekeeping
     * ({@code dbo.policy.retention} and its siblings): a <b>declared</b> step's
     * id is module, process and step, so a three-part process name would leave
     * no room for the step and the declaration would be refused at build time.
     */
    public static final String PROCESS = "dbo.erasure";

    /** Its one step: the shred itself. */
    public static final String STEP = "shred";

    /**
     * The points a half-finished erasure can stop at, in order.
     *
     * <p>This is the case that matters. An erasure that destroyed the key and
     * then failed before writing the ledger has done the irreversible half
     * without the half that makes a restore safe — and an operator has to be
     * able to see that, which a void method could never tell anybody.
     */
    public static final List<String> MILESTONES =
            List.of("key-destroyed", "index-removed", "ledger-written");

    /** The step as the catalogue carries it, so a run can name its version. */
    public static StepDeclaration declaration() {
        return new StepDeclaration(
                cloud.jengu.dbo.core.process.StepId.of(PROCESS + "." + STEP), "1",
                Set.of(), Set.of(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(),
                // Closing is this store's act: the party that asked cannot
                // declare the erasure done, because it cannot see whether the
                // key is gone.
                Set.of("open", "close"),
                Map.of("subject", "reference to the record the person is known by here"),
                MILESTONES);
    }

    private final PersonVault vault;
    private final Runs runs;

    public PersonErasure(PersonVault vault, Runs runs) {
        this.vault = vault;
        this.runs = runs;
    }

    /**
     * Asks for a person to be erased, and returns the run that answers for it.
     *
     * @param personId the vault's person, resolved by the caller's side of the
     *                 door from whatever reference the consumer held
     * @return the run — new, or the one an earlier identical request opened
     */
    public Run erase(String personId) {
        Run run = runs.pipeline(PROCESS, STEP, key(personId));
        if (!run.open()) {
            // Already erased, and this is the second asking. A no-op rather
            // than a refusal: an Article 17 request repeated is not an error,
            // and refusing one would make callers ask whether they had asked.
            return run;
        }
        PersonVault.Shred shred;
        try {
            shred = vault.shred(personId);
        } catch (RuntimeException failed) {
            // Released, not closed. A failed erasure that read as done is the
            // one outcome this whole mechanism exists to prevent.
            runs.released(run, "erasure failed: " + failed.getMessage());
            throw failed;
        }
        if (!shred.known()) {
            // Nobody here by that id. The run closes saying so, because "this
            // store never held them" is a true answer to the request and a
            // different one from "the key is destroyed".
            return runs.closed(runs.checkpoint(run,
                    Map.of("known", 0L, "keyDestroyed", 0L), hold()));
        }
        Run at = runs.milestone(run, "key-destroyed",
                Map.of("keyDestroyed", shred.keyDestroyed() ? 1L : 0L), hold());
        at = runs.milestone(at, "index-removed",
                Map.of("identifiers", (long) shred.identifiers(),
                        "lookups", (long) shred.lookups()), hold());
        at = runs.milestone(at, "ledger-written", Map.of("known", 1L), hold());
        return runs.closed(at);
    }

    /**
     * The run's key, which is the person and nothing else.
     *
     * <p>A pseudonymous id, so the key discloses no more than the record it
     * names — and stable, which is what makes a repeated request find this run
     * instead of starting a second account of the same erasure.
     */
    public static String key(String personId) {
        return PROCESS + "/" + STEP + "/" + personId;
    }

    private static java.time.Instant hold() {
        return java.time.Instant.now().plusSeconds(300);
    }
}
